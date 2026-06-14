package ru.retail.service.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.Proxy;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.retail.service.dto.MyListingInfo;
import ru.retail.service.dto.PartPrice;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DromParser {

    private static final String[] USER_AGENTS = {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:126.0) Gecko/20100101 Firefox/126.0"
    };

    private static final Path DEFAULT_SESSION_FILE = Paths.get("drom-session.json");
    private static final Path NSK_SESSION_FILE = Paths.get("drom-session-nsk.json");

    private static final int DEFAULT_DETAIL_LIMIT = 6;   // сколько детальных страниц реально открываем
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;
    private static final int NAV_TIMEOUT_MS = 30_000;    // тайм-аут навигации: быстрый фейл + ретрай вместо 90с зависаний

    private static final List<String> SKIP_TITLE_KEYWORDS = List.of(
            "ремкомплект", "ремонтный", "поршень", "направляющ",
            "пыльник", "скоба", "уплотнитель", "манжет", "прокладк",
            "болт", "пружин", "шплинт"
    );

    private Playwright playwright;
    private Browser browser;

    // Отдельный Playwright+браузер для параллельного НСК-парсинга (ленивая инициализация на потоке-исполнителе)
    private Playwright nskPlaywright;
    private volatile Browser nskBrowser;

    // Кэш детально разобранных объявлений по URL — ОБЩИЙ для всех дорожек (соседние OEM делят конкурентов)
    private record CachedPart(PartPrice part, long ts) {}
    private static final ConcurrentHashMap<String, CachedPart> detailCache = new ConcurrentHashMap<>();

    // Предохранитель прокси: сколько запросов подряд упали с ошибкой соединения через прокси
    private static final int PROXY_DOWN_THRESHOLD = 5;
    private volatile int consecutiveProxyErrors = 0;

    // Предохранитель капчи: сколько нерешаемых капч подряд (drom помечает IP после интенсивного парсинга).
    // Любая успешная загрузка страницы сбрасывает счётчик (см. proxyOk()).
    private static final int CAPTCHA_BLOCK_THRESHOLD = 3;
    private volatile int consecutiveCaptchaFails = 0;

    // Реактивная ротация IP: при капче дорожка с мобильным прокси крутит IP (mobileproxy.space change-IP ссылка)
    // вместо остановки батча. Жёсткий стоп — только если ротация не лечит MAX_ROTATIONS_NO_PROGRESS раз подряд
    // (ни одной успешной страницы между ротациями → прокси мёртв или drom блокирует всю подсеть).
    private static final int MAX_ROTATIONS_NO_PROGRESS = 4;
    private static final long ROTATE_APPLY_WAIT_MS = 8000;   // пауза, пока новый IP встаёт на модеме (rt ≈ 3–5 с)
    private static final HttpClient ROTATE_HTTP = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(15)).build();
    private volatile int rotationsSinceProgress = 0;
    private volatile boolean captchaHardBlocked = false;
    private volatile long totalRotations = 0;   // монотонный счётчик удачных смен IP (для повтора OEM на свежем IP)

    private final boolean headless;
    private final String proxyUrl;
    private final String changeIpUrl;     // change-IP ссылка mobileproxy для этой дорожки (null → ротация выключена)
    private final Path sessionFile;       // своя сессия на дорожку (cookies/капча не пересекаются между IP)
    private final String laneName;

    private final CaptchaSolverService captchaSolver;
    private final ApifyDromService apifyDromService;
    private final LocalSocksProxy localSocksProxy;
    // Фиксированный UA на весь сеанс — смена UA между запросами инвалидирует сессию Drom.ru
    private final String fixedUserAgent = USER_AGENTS[(int) (Math.random() * USER_AGENTS.length)];

    // Spring-бин: одиночная дорожка (drom.proxy.url, drom.headless) — для веб/одиночного анализа
    @org.springframework.beans.factory.annotation.Autowired
    public DromParser(CaptchaSolverService captchaSolver, ApifyDromService apifyDromService,
                      LocalSocksProxy localSocksProxy,
                      @Value("${drom.proxy.url:}") String proxyUrl,
                      @Value("${drom.headless:true}") boolean headless) {
        this(captchaSolver, apifyDromService, localSocksProxy, proxyUrl, DEFAULT_SESSION_FILE, headless, "L0", null);
    }

    // Дорожка пула: свой прокси + своя сессия + своя change-IP ссылка (создаётся вручную из DromParserPool)
    public DromParser(CaptchaSolverService captchaSolver, ApifyDromService apifyDromService,
                      LocalSocksProxy localSocksProxy,
                      String proxyUrl, Path sessionFile, boolean headless, String laneName, String changeIpUrl) {
        this.captchaSolver = captchaSolver;
        this.apifyDromService = apifyDromService;
        this.localSocksProxy = localSocksProxy;
        this.proxyUrl = proxyUrl;
        this.sessionFile = sessionFile;
        this.headless = headless;
        this.laneName = laneName;
        this.changeIpUrl = changeIpUrl;
    }

    @PostConstruct
    public void init() {
        playwright = createPlaywright();
        ensurePlaywrightBrowsersInstalled();
        browser = playwright.chromium().launch(buildLaunchOptions());
    }

    private BrowserType.LaunchOptions buildLaunchOptions() {
        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                .setHeadless(headless)
                .setArgs(List.of(
                        "--disable-blink-features=AutomationControlled",
                        "--no-sandbox",
                        "--disable-dev-shm-usage",
                        "--disable-features=IsolateOrigins,site-per-process",
                        "--lang=ru-RU",
                        "--window-size=1366,768"
                ));
        if (proxyUrl != null && !proxyUrl.isBlank()) {
            launchOptions.setProxy(buildProxy(proxyUrl));   // HTTP-прокси дорожки (с авторизацией) — приоритет
        } else {
            String localProxyUrl = localSocksProxy.getLocalProxyUrl();
            if (localProxyUrl != null) {
                // SOCKS5 с авторизацией: Chromium не поддерживает, используем локальный HTTP-мост
                launchOptions.setProxy(new Proxy(localProxyUrl));
                log.info("Прокси: через локальный SOCKS5-мост → {}", localProxyUrl);
            }
        }
        return launchOptions;
    }

    /**
     * Ленивая инициализация отдельного браузера для НСК. Создаётся на том же потоке,
     * который его использует (single-thread executor), что соблюдает потоковую модель Playwright.
     */
    private synchronized Browser nskBrowser() {
        if (nskBrowser == null) {
            nskPlaywright = createPlaywright();
            nskBrowser = nskPlaywright.chromium().launch(buildLaunchOptions());
            log.info("НСК-браузер инициализирован (отдельный Playwright)");
        }
        return nskBrowser;
    }

    @PreDestroy
    public void destroy() {
        if (nskBrowser != null) try { nskBrowser.close(); } catch (Exception ignored) {}
        if (nskPlaywright != null) try { nskPlaywright.close(); } catch (Exception ignored) {}
        if (browser != null) try { browser.close(); } catch (Exception ignored) {}
        if (playwright != null) playwright.close();
    }

    private void ensurePlaywrightBrowsersInstalled() {
        try {
            playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true)).close();
        } catch (com.microsoft.playwright.PlaywrightException e) {
            if (e.getMessage() == null || !e.getMessage().contains("Executable doesn't exist")) throw e;
            log.info("Playwright: браузеры не найдены, запускаем установку...");
            runPlaywrightInstallWithProgressUI();
            playwright.close();
            playwright = createPlaywright();
        }
    }

    private void runPlaywrightInstallWithProgressUI() {
        javax.swing.JDialog[] dialogRef = new javax.swing.JDialog[1];
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                javax.swing.JDialog dialog = new javax.swing.JDialog((java.awt.Frame) null, "Первый запуск", false);
                dialog.setDefaultCloseOperation(javax.swing.JDialog.DO_NOTHING_ON_CLOSE);
                javax.swing.JPanel panel = new javax.swing.JPanel();
                panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
                panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(24, 32, 24, 32));

                javax.swing.JLabel title = new javax.swing.JLabel("Устанавливается браузер Chromium (~150 МБ)");
                title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 14f));
                title.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);

                javax.swing.JProgressBar progress = new javax.swing.JProgressBar();
                progress.setIndeterminate(true);
                progress.setPreferredSize(new java.awt.Dimension(420, 18));
                progress.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 18));

                javax.swing.JLabel sub = new javax.swing.JLabel("Пожалуйста, подождите — это требуется только при первом запуске");
                sub.setForeground(java.awt.Color.GRAY);
                sub.setFont(sub.getFont().deriveFont(java.awt.Font.PLAIN, 12f));
                sub.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);

                panel.add(title);
                panel.add(javax.swing.Box.createVerticalStrut(14));
                panel.add(progress);
                panel.add(javax.swing.Box.createVerticalStrut(10));
                panel.add(sub);

                dialog.setContentPane(panel);
                dialog.pack();
                dialog.setLocationRelativeTo(null);
                dialog.setVisible(true);
                dialogRef[0] = dialog;
            });
        } catch (Exception ex) {
            log.warn("Не удалось показать диалог установки: {}", ex.getMessage());
        }

        try {
            runPlaywrightInstall();
        } finally {
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (dialogRef[0] != null) dialogRef[0].dispose();
            });
        }
    }

    private void runPlaywrightInstall() {
        try {
            String javaExe = ProcessHandle.current().info().command()
                    .orElse(System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java");
            String classPath = System.getProperty("java.class.path");

            ProcessBuilder pb = new ProcessBuilder(javaExe, "-cp", classPath,
                    "com.microsoft.playwright.CLI", "install", "chromium");
            pb.redirectErrorStream(true);
            String browsersPath = System.getProperty("playwright.browsers.path");
            if (browsersPath != null && !browsersPath.isBlank()) {
                pb.environment().put("PLAYWRIGHT_BROWSERS_PATH", browsersPath);
            }

            Process process = pb.start();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) log.info("playwright-install: {}", line);
            }
            int exit = process.waitFor();
            if (exit != 0) throw new RuntimeException("playwright install завершился с кодом " + exit);
            log.info("Playwright: Chromium установлен успешно");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Не удалось установить браузеры Playwright: " + e.getMessage(), e);
        }
    }

    private Playwright createPlaywright() {
        String customPath = System.getProperty("playwright.browsers.path");
        if (customPath != null && !customPath.isBlank()) {
            log.info("Playwright: браузеры из бандла → {}", customPath);
            return Playwright.create(new Playwright.CreateOptions()
                    .setEnv(Map.of("PLAYWRIGHT_BROWSERS_PATH", customPath)));
        }
        return Playwright.create();
    }

    private Proxy buildProxy(String url) {
        // Разбираем http://user:pass@host:port — Playwright не принимает credentials в URL напрямую
        try {
            java.net.URI uri = new java.net.URI(url);
            String server = uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort();
            Proxy proxy = new Proxy(server);
            String userInfo = uri.getUserInfo();
            if (userInfo != null) {
                int sep = userInfo.indexOf(':');
                if (sep > 0) {
                    proxy.setUsername(userInfo.substring(0, sep));
                    proxy.setPassword(userInfo.substring(sep + 1));
                }
            }
            log.info("Прокси: {} (пользователь: {})", server, uri.getUserInfo() != null ? userInfo.split(":")[0] : "—");
            return proxy;
        } catch (Exception e) {
            log.warn("Не удалось разобрать URL прокси, передаём как есть: {}", e.getMessage());
            return new Proxy(url);
        }
    }

    // ==================== ПАРСИНГ МОёГО ОБЪЯВЛЕНИЯ ====================

    /**
     * Парсит моё собственное объявление по URL.
     * Извлекает описание, состояние, дату публикации и фотографии.
     */
    public MyListingInfo parseMyListing(String url) {
        log.info("Парсинг моего объявления: {}", url);
        BrowserContext ctx = newContext();
        Page page = newPage(ctx);
        try {
            navigateWithRetry(page, url, null);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            Thread.sleep(1500);

            if (hasCaptcha(page)) {
                boolean solved = tryClickDromCheckbox(page) || captchaSolver.solve(page);
                if (!solved) solved = waitForManualCaptcha(page);
                if (!solved) {
                    noteCaptchaBlocked();
                    log.warn("Капча на моём объявлении не решена. Данные недоступны.");
                    return MyListingInfo.builder()
                            .description("").condition("не указано").manufacturer("не указано")
                            .photoUrls(List.of()).price(BigDecimal.ZERO).build();
                }
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                Thread.sleep(1000);
            }

            String title = "";
            ElementHandle titleEl = page.querySelector("h1.subject span, h1 span");
            if (titleEl != null) title = titleEl.innerText().trim();

            BigDecimal price = extractPriceFromPage(page);

            String condition     = getTextByAttr(page, "data-field", "condition");
            String manufacturer  = getTextByAttr(page, "data-field", "manufacturer");
            String oem           = getTextByAttr(page, "data-field", "autoPartsOemNumber");
            String description   = getTextByAttr(page, "data-field", "text");
            String publishedDate = extractDate(page);
            String city          = extractCity(page);
            List<String> photos  = extractPhotoUrls(page, 5);

            if (oem != null) oem = oem.replaceAll("\\s+", "");

            log.info("Моё объявление: {} — {}₽, дата: {}, фото: {}",
                    title.length() > 50 ? title.substring(0, 50) : title, price, publishedDate, photos.size());

            proxyOk();   // объявление загрузилось — прокси жив
            return MyListingInfo.builder()
                    .title(title)
                    .description(description != null ? description : "")
                    .condition(condition != null ? condition : "не указано")
                    .manufacturer(manufacturer != null ? manufacturer : "не указано")
                    .oem(oem != null ? oem : "")
                    .city(city)
                    .publishedDate(publishedDate != null ? publishedDate : "")
                    .photoUrls(photos)
                    .price(price)
                    .build();

        } catch (Exception e) {
            noteError(e);
            log.error("Ошибка парсинга моего объявления: {}", e.getMessage());
            return MyListingInfo.builder()
                    .description("").condition("").manufacturer("").photoUrls(List.of())
                    .price(BigDecimal.ZERO).build();
        } finally {
            page.close();
            ctx.close();
        }
    }

    // ==================== ПАРСИНГ КОНКУРЕНТОВ В ГОРОДЕ ====================

    public List<PartPrice> parseParts(String oemNumber, String region) {
        return parsePartsImpl(oemNumber, region, 10, DEFAULT_DETAIL_LIMIT, Set.of(), browser, sessionFile);
    }

    public List<PartPrice> parseParts(String oemNumber, String region, int limit) {
        return parsePartsImpl(oemNumber, region, limit, DEFAULT_DETAIL_LIMIT, Set.of(), browser, sessionFile);
    }

    public List<PartPrice> parseParts(String oemNumber, String region, int limit, Set<String> excludeUrls) {
        return parsePartsImpl(oemNumber, region, limit, DEFAULT_DETAIL_LIMIT, excludeUrls, browser, sessionFile);
    }

    /** Параллельный парсинг НСК — отдельный браузер/сессия, вызывается из выделенного потока. */
    public List<PartPrice> parsePartsBackground(String oemNumber, String region, int limit) {
        return parsePartsImpl(oemNumber, region, limit, DEFAULT_DETAIL_LIMIT, Set.of(), nskBrowser(), NSK_SESSION_FILE);
    }

    /** Результат комбинированного парсинга города: URL моего объявления + конкуренты (за одну загрузку поиска). */
    public record CityParseResult(String myListingUrl, List<PartPrice> competitors, List<MyListingCandidate> myCandidates) {}
    /** Кандидат «моего объявления» на странице поиска: URL + цена. Нужен для выбора по цене и кэша конкурентов на батч. */
    public record MyListingCandidate(String url, BigDecimal price) {}

    private List<PartPrice> parsePartsImpl(String oemNumber, String region, int limit, int detailLimit,
                                           Set<String> excludeUrls, Browser b, Path sessionFile) {
        if (apifyDromService.isEnabled()) {
            log.info("Apify: делегируем парсинг [{}] OEM={}", region, oemNumber);
            Set<String> normalizedExcludes = excludeUrls.stream()
                    .map(u -> u.toLowerCase().replaceAll("/+$", ""))
                    .collect(Collectors.toSet());
            return apifyDromService.parseParts(oemNumber, region, limit).stream()
                    .filter(p -> normalizedExcludes.isEmpty() ||
                            !normalizedExcludes.contains(p.getUrl().toLowerCase().replaceAll("/+$", "")))
                    .collect(Collectors.toList());
        }

        String searchUrl = buildSearchUrl(oemNumber, region);
        log.info("Парсинг конкурентов [{}]: {}", region, searchUrl);

        BrowserContext ctx = newContext(b, sessionFile);
        Page page = newPage(ctx);
        try {
            List<SearchEntry> entries = loadSearchEntries(page, ctx, sessionFile, searchUrl, region);
            if (entries == null) return List.of();   // капча не пройдена
            return processSearchEntries(page, ctx, sessionFile, searchUrl, entries,
                    oemNumber, region, limit, detailLimit, excludeUrls);
        } catch (Exception e) {
            noteError(e);
            log.error("Ошибка парсинга [{}]: {}", region, e.getMessage());
            return List.of();
        } finally {
            page.close();
            ctx.close();
        }
    }

    /**
     * За ОДНУ загрузку страницы поиска: находит URL моего объявления (по имени компании) и парсит конкурентов.
     * Раньше {@code findMyListingUrl} + {@code parseParts} грузили эту страницу дважды на каждый OEM.
     */
    public CityParseResult parseCityWithMyListing(String oemNumber, String region, int limit, String myCompany, BigDecimal myPrice) {
        // Apify-режим: комбинированную загрузку не делаем (по умолчанию apify выключен)
        if (apifyDromService.isEnabled()) {
            String myUrl = findMyListingUrl(oemNumber, region, myCompany);
            if (myUrl == null) return new CityParseResult(null, List.of(), List.of());
            return new CityParseResult(myUrl, parseParts(oemNumber, region, limit, Set.of(myUrl)), List.of());
        }

        String searchUrl = buildSearchUrl(oemNumber, region);
        log.info("Поиск моего объявления + конкурентов [{}]: {}", region, searchUrl);

        BrowserContext ctx = newContext(browser, sessionFile);
        Page page = newPage(ctx);
        try {
            List<SearchEntry> entries = loadSearchEntries(page, ctx, sessionFile, searchUrl, region);
            if (entries == null) return new CityParseResult(null, List.of(), List.of());

            // Деталь определяется тройкой компания + OEM + ЦЕНА: один OEM может быть у нас несколькими
            // объявлениями (разное качество/цена). Среди объявлений компании берём то, чья цена ближе
            // к каталожной (точное совпадение приоритетно). Без цены — первое (старое поведение).
            String companyLower = myCompany.toLowerCase();
            List<SearchEntry> mine = entries.stream()
                    .filter(e -> e.dealer() != null && e.dealer().toLowerCase().contains(companyLower))
                    .collect(Collectors.toList());
            List<MyListingCandidate> candidates = mine.stream()
                    .map(e -> new MyListingCandidate(e.url(), e.price()))
                    .collect(Collectors.toList());
            SearchEntry chosen = pickMyListingByPrice(mine, myPrice);
            String myUrl = chosen != null ? chosen.url() : null;
            // Моего объявления нет — детали не открываем (как и раньше при пустом findMyListingUrl)
            if (myUrl == null) return new CityParseResult(null, List.of(), List.of());
            if (myPrice != null && myPrice.signum() > 0 && chosen.price() != null)
                log.info("Моё объявление выбрано по цене: каталог {}₽ → объявление {}₽ ({} вариантов компании)",
                        myPrice, chosen.price(), mine.size());

            List<PartPrice> competitors = processSearchEntries(page, ctx, sessionFile, searchUrl, entries,
                    oemNumber, region, limit, DEFAULT_DETAIL_LIMIT, Set.of(myUrl));
            return new CityParseResult(myUrl, competitors, candidates);
        } catch (Exception e) {
            noteError(e);
            log.error("Ошибка поиска объявления/конкурентов [{}]: {}", region, e.getMessage());
            return new CityParseResult(null, List.of(), List.of());
        } finally {
            page.close();
            ctx.close();
        }
    }

    /**
     * Выбирает наше объявление среди вариантов компании по близости цены к каталожной.
     * Точное совпадение выигрывает; при равенстве дистанций — первое по порядку поиска.
     * Нет цены в каталоге / ни у одного варианта нет цены → первое (прежнее поведение по компании).
     */
    private SearchEntry pickMyListingByPrice(List<SearchEntry> mine, BigDecimal targetPrice) {
        if (mine.isEmpty()) return null;
        if (targetPrice == null || targetPrice.signum() <= 0) return mine.get(0);
        SearchEntry best = null;
        double bestDiff = Double.MAX_VALUE;
        for (SearchEntry e : mine) {
            if (e.price() == null) continue;
            double diff = Math.abs(e.price().doubleValue() - targetPrice.doubleValue());
            if (diff < bestDiff) { bestDiff = diff; best = e; }
        }
        return best != null ? best : mine.get(0);
    }

    /** Загружает страницу поиска (с обработкой капчи) и возвращает сырые объявления; null — капча не пройдена. */
    private List<SearchEntry> loadSearchEntries(Page page, BrowserContext ctx, Path sessionFile,
                                                String searchUrl, String region) throws InterruptedException {
        navigateWithRetry(page, searchUrl, null);
        Thread.sleep(1500);

        if (hasCaptcha(page)) {
            boolean solved = tryClickDromCheckbox(page) || captchaSolver.solve(page);
            if (!solved) solved = waitForManualCaptcha(page);
            if (!solved) {
                noteCaptchaBlocked();
                log.error("Капча на поиске [{}] не решена — пустой результат.", region);
                return null;
            }
            saveSession(ctx, sessionFile);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        }

        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        Thread.sleep(1000);

        List<SearchEntry> entries = collectSearchEntries(page);
        proxyOk();   // страница поиска загрузилась — прокси жив

        // Сигнал о поломке скрейпера: объявления есть, но ни у одного не извлеклась цена → селектор сломан
        if (!entries.isEmpty() && entries.stream().noneMatch(e -> e.price() != null)) {
            log.error("СЕЛЕКТОР ЦЕНЫ СЛОМАН [{}]: {} объявлений, но 0 цен — проверьте DOM baza.drom.ru (collectSearchEntries)",
                    region, entries.size());
        }
        return entries;
    }

    /** Из сырых объявлений страницы поиска делает список конкурентов: фильтры, дедуп, сортировка, заход на детали. */
    private List<PartPrice> processSearchEntries(Page page, BrowserContext ctx, Path sessionFile, String searchUrl,
                                                 List<SearchEntry> entries, String oemNumber, String region,
                                                 int limit, int detailLimit, Set<String> excludeUrls) {
        Set<String> normalizedExcludes = excludeUrls.stream()
                .map(u -> u.toLowerCase().replaceAll("/+$", ""))
                .collect(Collectors.toSet());
        String regionSlug = "/" + region + "/";
        String normalizedOem = oemNumber.replaceAll("\\s+", "").toUpperCase();

        // Исключаем свои URL + фильтр по региону (с фолбэком на все, если /регион/ нет в пути)
        List<SearchEntry> regional = entries.stream()
                .filter(e -> normalizedExcludes.isEmpty()
                        || !normalizedExcludes.contains(e.url().toLowerCase().replaceAll("/+$", "")))
                .filter(e -> e.url().contains(regionSlug))
                .collect(Collectors.toList());
        if (regional.isEmpty()) {
            regional = entries.stream()
                    .filter(e -> normalizedExcludes.isEmpty()
                            || !normalizedExcludes.contains(e.url().toLowerCase().replaceAll("/+$", "")))
                    .collect(Collectors.toList());
        }

        // Пре-фильтр по OEM со страницы поиска
        List<SearchEntry> oemFiltered = regional.stream()
                .filter(e -> {
                    String pageOem = (e.oem() == null || e.oem().isBlank()) ? null : e.oem().toUpperCase();
                    return pageOem == null || pageOem.equals(normalizedOem)
                            || pageOem.contains(normalizedOem) || normalizedOem.contains(pageOem);
                })
                .collect(Collectors.toList());
        if (oemFiltered.isEmpty()) oemFiltered = regional;

        // Пред-фильтр по заголовку — ремкомплекты/компоненты не открываем
        List<SearchEntry> candidates = oemFiltered.stream()
                .filter(e -> {
                    String t = e.title() == null ? "" : e.title().toLowerCase();
                    return SKIP_TITLE_KEYWORDS.stream().noneMatch(t::contains);
                })
                .collect(Collectors.toList());

        // Дедуп по URL с сохранением порядка
        LinkedHashMap<String, SearchEntry> uniq = new LinkedHashMap<>();
        for (SearchEntry e : candidates) uniq.putIfAbsent(e.url(), e);
        List<SearchEntry> deduped = new ArrayList<>(uniq.values());

        // Сортировка по цене (с ценой — дешёвые первыми; без цены — в конец)
        deduped.sort(Comparator.comparing(e -> e.price() == null
                ? BigDecimal.valueOf(Long.MAX_VALUE) : e.price()));

        int skippedByTitle = oemFiltered.size() - candidates.size();
        log.info("Ссылок всего: {} | региональных: {} | после OEM-фильтра: {} | пред-фильтр: −{} | исключено: {}",
                entries.size(), regional.size(), oemFiltered.size(), skippedByTitle, excludeUrls.size());

        // Рабочий набор — дешёвые `limit`. Детально открываем только первые `detailLimit`.
        List<SearchEntry> working = deduped.size() > limit ? deduped.subList(0, limit) : deduped;
        int toVisit = Math.min(detailLimit, working.size());

        List<PartPrice> results = new ArrayList<>();
        int visited = 0;
        int attempt = 0;

        for (int i = 0; i < working.size(); i++) {
            SearchEntry e = working.get(i);

            // Кэш детальных данных (соседние OEM выдают тех же конкурентов)
            CachedPart cached = detailCache.get(e.url());
            if (cached != null && System.currentTimeMillis() - cached.ts() < CACHE_TTL_MS) {
                results.add(cached.part());
                continue;
            }

            // За пределами detailLimit — лёгкая запись со страницы поиска (без захода)
            if (i >= toVisit) {
                if (e.price() != null) results.add(lightweightPart(e, region));
                continue;
            }

            if (attempt > 0) {
                try { Thread.sleep(500 + (long) (Math.random() * 500)); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            attempt++;
            log.info("[деталь {}/{}] {}", visited + 1, toVisit, e.url());

            try {
                navigateWithRetry(page, e.url(), searchUrl);
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                Thread.sleep(500);

                if (hasCaptcha(page)) {
                    log.info("Капча на детальной странице: {}", e.url());
                    boolean solved = tryClickDromCheckbox(page) || captchaSolver.solve(page);
                    if (!solved) solved = waitForManualCaptcha(page);
                    if (!solved) {
                        noteCaptchaBlocked();
                        log.warn("Капча не решена; используем данные со страницы поиска: {}", e.url());
                        if (e.price() != null) results.add(lightweightPart(e, region));
                        continue;
                    }
                    saveSession(ctx, sessionFile);
                    page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                    Thread.sleep(500);
                }

                PartPrice part = extractPartFromPage(page, e.url());
                if (part != null) {
                    results.add(part);
                    detailCache.put(e.url(), new CachedPart(part, System.currentTimeMillis()));
                    visited++;
                } else if (e.price() != null) {
                    results.add(lightweightPart(e, region));   // детально не вышло, но цена со страницы поиска есть
                }
            } catch (Exception ex) {
                log.warn("Ошибка [{}]: {}", e.url(), ex.getMessage());
                if (e.price() != null) results.add(lightweightPart(e, region));   // фолбэк на данные поиска
            }
        }

        results.sort((a, b2) -> a.getPrice().compareTo(b2.getPrice()));
        log.info("Собрано в [{}]: {} предложений (детально: {})", region, results.size(), visited);
        return results;
    }

    /** Лёгкая запись конкурента из данных страницы поиска (без захода на детальную). */
    private PartPrice lightweightPart(SearchEntry e, String region) {
        return PartPrice.builder()
                .title(e.title())
                .price(e.price())
                .url(e.url())
                .oem(e.oem() == null || e.oem().isBlank() ? null : e.oem().replaceAll("\\s+", "").toUpperCase())
                .location(region)
                .dealer(e.dealer())
                .publishedDate(e.date())
                .photoUrls(List.of())
                .description("")
                .build();
    }

    // ==================== ДЕТАЛЬНАЯ СТРАНИЦА ====================

    /** Извлекает данные объявления из уже загруженной страницы. */
    private PartPrice extractPartFromPage(Page page, String originalUrl) {
        try {
            String actualUrl = page.url();

            String priceText = getTextByAttr(page, "data-field", "price");
            if (priceText == null) return null;
            priceText = priceText.replaceAll("[^\\d.]", "");
            if (priceText.isEmpty()) return null;
            BigDecimal price = new BigDecimal(priceText);

            String title = "";
            ElementHandle titleEl = page.querySelector("h1.subject span");
            if (titleEl != null) title = titleEl.innerText().trim();

            String condition    = getTextByAttr(page, "data-field", "condition");
            String authenticity = getTextByAttr(page, "data-field", "autoPartsAuthenticity");
            String manufacturer = getTextByAttr(page, "data-field", "manufacturer");
            String oem          = getTextByAttr(page, "data-field", "autoPartsOemNumber");
            String desc         = getTextByAttr(page, "data-field", "text");
            String publishedDate = extractDate(page);
            String city          = extractCity(page);
            List<String> photos  = extractPhotoUrls(page, 3);

            String seller = "";
            ElementHandle sellerEl = page.querySelector(".userNick a");
            if (sellerEl != null) seller = sellerEl.innerText().trim();

            StringBuilder sb = new StringBuilder();
            if (condition != null)    sb.append("Состояние: ").append(condition).append("\n");
            if (authenticity != null) sb.append("Оригинальность: ").append(authenticity).append("\n");
            if (manufacturer != null) sb.append("Производитель: ").append(manufacturer).append("\n");
            if (oem != null)          sb.append("Номер: ").append(oem.replaceAll("\\s+", "")).append("\n");
            if (!seller.isEmpty())    sb.append("Продавец: ").append(seller).append("\n");
            if (!city.isEmpty())      sb.append("Город: ").append(city).append("\n");
            if (desc != null && desc.length() > 10)
                sb.append("Описание: ").append(desc, 0, Math.min(300, desc.length())).append("\n");

            log.info("[{}] {}₽ | фото:{}", actualUrl.substring(Math.max(0, actualUrl.length() - 40)), price, photos.size());

            return PartPrice.builder()
                    .title(title).price(price).url(actualUrl)
                    .oem(oem != null ? oem.replaceAll("\\s+", "").toUpperCase() : null)
                    .location(city).dealer(seller)
                    .publishedDate(publishedDate)
                    .photoUrls(photos)
                    .description(sb.toString())
                    .build();

        } catch (Exception e) {
            log.warn("Ошибка извлечения данных {}: {}", originalUrl, e.getMessage());
            return null;
        }
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ ====================

    /** Данные объявления, извлечённые прямо со страницы результатов поиска. */
    record SearchEntry(String url, String dealer, String oem, String title, BigDecimal price, String date) {
        SearchEntry(String url, String dealer) { this(url, dealer, "", "", null, ""); }
        SearchEntry(String url, String dealer, String oem) { this(url, dealer, oem, "", null, ""); }
    }

    @SuppressWarnings("unchecked")
    private List<SearchEntry> collectSearchEntries(Page page) {
        try {
            // Объявления имеют URL вида /g[ID].html (без /sell_spare_parts/ в пути)
            Object raw = page.evaluate("""
                JSON.stringify(
                  Array.from(document.querySelectorAll('tr.bull-list-item-js')).map(row => {
                    const a = row.querySelector('a[data-role="bulletin-link"]');
                    if (!a || !a.href) return null;
                    const dealer = row.querySelector('.ellipsis-text__left-side');
                    // OEM: из .searchSnippet или второй .bull-item__annotation-row
                    const snippetEl = row.querySelector('.searchSnippet, .searchMatchHilight');
                    const annRows = row.querySelectorAll('.bull-item__annotation-row');
                    let oem = snippetEl ? snippetEl.textContent.trim().replace(/\\s+/g,'') : '';
                    if (!oem && annRows.length >= 2) oem = annRows[1].textContent.trim().replace(/\\s+/g,'');
                    // Цена: строгий приоритет [data-role="price"] (полная цена), иначе фолбэки.
                    // Берём первое число, чтобы не склеить платёж по рассрочке + полную цену.
                    const priceEl = row.querySelector('[data-role="price"]')
                                 || row.querySelector('.price-block__price')
                                 || row.querySelector('.bull-item__price');
                    let priceText = '';
                    if (priceEl) { const pm = priceEl.textContent.replace(/\\s/g,'').match(/\\d+/); priceText = pm ? pm[0] : ''; }
                    // Заголовок
                    const titleText = a.textContent.trim();
                    // Дата
                    const dateEl = row.querySelector('.date, .bull-item__date, .viewbull-actual-date');
                    const dateText = dateEl ? dateEl.textContent.trim() : '';
                    return { url: a.href, dealer: dealer ? dealer.textContent.trim() : '',
                             oem: oem, title: titleText, price: priceText, date: dateText };
                  }).filter(e => e && e.url && e.url.length > 15)
                )
                """);
            if (raw == null) return List.of();
            List<Map<String, Object>> list = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(raw.toString(), List.class);
            List<SearchEntry> entries = new ArrayList<>();
            for (Map<String, Object> m : list) {
                String url    = (String) m.get("url");
                String dealer = m.get("dealer") instanceof String s ? s : "";
                String oem    = m.get("oem")    instanceof String o ? o : "";
                String title  = m.get("title")  instanceof String t ? t : "";
                String date   = m.get("date")   instanceof String d ? d : "";
                String priceStr = m.get("price") instanceof String p ? p : "";
                BigDecimal price = null;
                try { if (!priceStr.isBlank()) price = new BigDecimal(priceStr); } catch (Exception ignored) {}
                if (url != null && !url.isBlank()) entries.add(new SearchEntry(url, dealer, oem, title, price, date));
            }
            if (!entries.isEmpty()) return entries;
        } catch (Exception e) {
            log.warn("collectSearchEntries JS error: {}", e.getMessage());
        }
        // fallback: ищем по data-role или классу bulletinLink
        List<SearchEntry> entries = new ArrayList<>();
        List<ElementHandle> allLinks = page.querySelectorAll("a[data-role='bulletin-link'], a.bulletinLink");
        for (ElementHandle link : allLinks) {
            String href = link.getAttribute("href");
            if (href != null && href.length() > 15) {
                String full = href.startsWith("http") ? href : "https://baza.drom.ru" + href;
                entries.add(new SearchEntry(full, ""));
            }
        }
        return entries;
    }

    /**
     * Находит URL объявления компании myCompany прямо на странице результатов поиска,
     * не заходя на детальные страницы. Возвращает null, если не найдено.
     */
    public String findMyListingUrl(String oemNumber, String region, String myCompany) {
        String searchUrl = buildSearchUrl(oemNumber, region);
        BrowserContext ctx = newContext();
        Page page = newPage(ctx);
        try {
            navigateWithRetry(page, searchUrl, null);
            Thread.sleep(3000);
            if (hasCaptcha(page)) {
                boolean solved = tryClickDromCheckbox(page) || captchaSolver.solve(page);
                if (!solved) solved = waitForManualCaptcha(page);
                if (!solved) { noteCaptchaBlocked(); return null; }
                saveSession(ctx);
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            }
            Thread.sleep(1500);
            proxyOk();   // страница загрузилась — прокси жив
            String companyLower = myCompany.toLowerCase();
            return collectSearchEntries(page).stream()
                    .filter(e -> e.dealer().toLowerCase().contains(companyLower))
                    .map(SearchEntry::url)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            noteError(e);
            log.warn("findMyListingUrl error: {}", e.getMessage());
            return null;
        } finally {
            page.close();
            ctx.close();
        }
    }

    /**
     * Извлекает URL фото с baza.drom.ru.
     * Способ 1: атрибут data-image-info (JSON с полями src/msrc) — самый точный.
     * Способ 2: img с доменом static.baza.drom.ru — все фото товара на этом домене.
     * Способ 3: og:image мета-тег — главное фото.
     * Фото на baza.drom.ru не имеют расширений в URL, поэтому нельзя фильтровать по *.jpg.
     */
    private List<String> extractPhotoUrls(Page page, int maxCount) {
        try {
            // Способ 1: data-image-info (специфичен для baza.drom.ru, содержит _default URL)
            String json = (String) page.evaluate(String.format("""
                JSON.stringify(
                    Array.from(document.querySelectorAll('[data-image-info]'))
                        .map(el => { try { return JSON.parse(el.dataset.imageInfo).src; } catch(e) { return null; } })
                        .filter(s => s && s.includes('static.baza.drom.ru'))
                        .filter((s, i, a) => a.indexOf(s) === i)
                        .slice(0, %d)
                )
                """, maxCount));
            List<String> result = parseStringJsonArray(json);
            if (!result.isEmpty()) return result;

            // Способ 2: img по домену static.baza.drom.ru
            json = (String) page.evaluate(String.format("""
                JSON.stringify(
                    Array.from(document.querySelectorAll('img'))
                        .map(img => img.src || '')
                        .filter(s => s.includes('static.baza.drom.ru'))
                        .filter((s, i, a) => a.indexOf(s) === i)
                        .slice(0, %d)
                )
                """, maxCount));
            result = parseStringJsonArray(json);
            if (!result.isEmpty()) return result;

            // Способ 3: og:image
            Object og = page.evaluate(
                    "document.querySelector('meta[property=\"og:image\"]')?.getAttribute('content')");
            if (og instanceof String s && !s.isBlank()) return List.of(s);

        } catch (Exception e) {
            log.debug("Фото не извлечены: {}", e.getMessage());
        }
        return List.of();
    }

    private List<String> parseStringJsonArray(String json) {
        if (json == null || json.equals("[]") || json.isBlank()) return List.of();
        try {
            json = json.trim();
            if (!json.startsWith("[")) return List.of();
            json = json.substring(1, json.length() - 1);
            if (json.isBlank()) return List.of();
            List<String> result = new ArrayList<>();
            for (String part : json.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
                String cleaned = part.trim().replaceAll("^\"|\"$", "");
                if (!cleaned.isBlank() && !cleaned.equals("null")) result.add(cleaned);
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String extractDate(Page page) {
        // baza.drom.ru: дата публикации находится в .viewbull-actual-date
        try {
            ElementHandle el = page.querySelector(".viewbull-actual-date");
            if (el != null) {
                String date = el.innerText().trim();
                if (!date.isBlank()) return date;
            }
        } catch (Exception ignored) {}
        // Запасные варианты
        String date = getTextByAttr(page, "data-field", "date");
        if (date == null) date = getTextByAttr(page, "data-field", "dateCreate");
        if (date == null) {
            try {
                ElementHandle el = page.querySelector("time");
                if (el != null) date = el.innerText().trim();
            } catch (Exception ignored) {}
        }
        return date;
    }

    private String extractCity(Page page) {
        try {
            List<ElementHandle> divs = page.querySelectorAll(".seller-summary > div");
            for (ElementHandle div : divs) {
                String text = div.innerText().trim();
                if (text.length() > 1 && text.length() < 30
                        && !text.contains("Рейтинг") && !text.contains("отзыв")
                        && !text.contains("Продавец") && !text.contains("на сайте")
                        && !text.contains("предложений") && !text.matches(".*\\d{2,}.*")) {
                    return text;
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private boolean hasCaptcha(Page page) {
        String body = page.innerText("body");
        return body.contains("Вы не робот") || body.contains("подозрительный трафик");
    }

    /**
     * Пытается пройти собственную капчу drom.ru кликом по чекбоксу "Я не робот".
     * На /verify странице ждёт редиректа после клика (URL меняется).
     * Возвращает true если капча пройдена.
     */
    boolean tryClickDromCheckbox(Page page) {
        try {
            boolean isVerifyPage = page.url().contains("/verify");
            if (isVerifyPage) {
                log.debug("Страница /verify: тело — {}", page.innerText("body").substring(0, Math.min(300, page.innerText("body").length())));
            }

            // Шаг 1: кликаем по чекбоксу (расширенный набор селекторов)
            String[] checkboxSelectors = {
                "label:has-text('Я не робот')",
                "label:has-text('не робот')",
                "input[type='checkbox']",
                ".checkbox__input",
                ".verify-checkbox",
                ".checkbox",
                "[class*='checkbox']",
                "[class*='verify']",
                "[class*='robot']"
            };
            boolean clicked = false;
            for (String sel : checkboxSelectors) {
                ElementHandle el = page.querySelector(sel);
                if (el != null) {
                    log.info("Кликаем по чекбоксу капчи: {}", sel);
                    el.click();
                    Thread.sleep(1500);
                    clicked = true;
                    break;
                }
            }

            // Если ни один CSS-селектор не сработал — пробуем JS (нестандартный чекбокс /verify)
            if (!clicked && isVerifyPage) {
                log.info("CSS-селекторы не нашли чекбокс, пробуем JS-клик");
                page.evaluate("""
                        (function() {
                            var el = document.querySelector('input[type="checkbox"]')
                                  || document.querySelector('[class*="checkbox"]')
                                  || document.querySelector('[class*="verify"]')
                                  || document.querySelector('label');
                            if (el) el.click();
                        })()
                        """);
                Thread.sleep(2000);
            }

            // Шаг 2: кликаем по кнопке "Продолжить"
            String[] submitSelectors = {
                "button:has-text('Продолжить')",
                "a:has-text('Продолжить')",
                "input[type='submit']",
                "button[type='submit']"
            };
            for (String sel : submitSelectors) {
                ElementHandle el = page.querySelector(sel);
                if (el != null) {
                    log.info("Кликаем по кнопке продолжить: {}", sel);
                    el.click();
                    Thread.sleep(3000);
                    break;
                }
            }

            // Шаг 3: на /verify ждём редирект (URL уходит с /verify)
            if (isVerifyPage) {
                try {
                    page.waitForURL(u -> !u.contains("/verify"),
                            new Page.WaitForURLOptions().setTimeout(8_000));
                    log.info("Капча /verify пройдена — редирект выполнен на {}", page.url());
                    saveSession(page.context());
                    return true;
                } catch (Exception e) {
                    log.debug("Редирект с /verify не произошёл: {}", e.getMessage());
                }
            }

            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            if (!hasCaptcha(page)) {
                log.info("Капча пройдена автоматически");
                saveSession(page.context());
                return true;
            }
        } catch (Exception e) {
            log.debug("Автоклик по капче не сработал: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Извлекает цену со страницы baza.drom.ru с несколькими запасными вариантами.
     * 1. data-field="price"  — стандартный атрибут
     * 2. itemprop="price"    — structured data
     * 3. og:description      — мета-тег: "N ₽" / "N руб"
     */
    private BigDecimal extractPriceFromPage(Page page) {
        // Попытка 1: стандартный data-field
        String text = getTextByAttr(page, "data-field", "price");
        if (text != null && !text.isBlank()) {
            String d = text.replaceAll("[^\\d.]", "");
            if (!d.isEmpty()) return new BigDecimal(d);
        }

        // Попытка 2: structured data itemprop или content
        try {
            Object val = page.evaluate("""
                (() => {
                    const el = document.querySelector('[itemprop="price"]');
                    if (el) return el.getAttribute('content') || el.innerText;
                    return null;
                })()""");
            if (val instanceof String s && !s.isBlank()) {
                String d = s.replaceAll("[^\\d.]", "");
                if (!d.isEmpty()) return new BigDecimal(d);
            }
        } catch (Exception ignored) {}

        // Попытка 3: og:description — "2 500 ₽" или "2500 руб"
        try {
            Object ogDesc = page.evaluate(
                "(() => { const m = document.querySelector('meta[property=\"og:description\"]'); return m ? m.content : ''; })()");
            if (ogDesc instanceof String s && !s.isBlank()) {
                // Ищем: одно или два слова из цифр, разделённые пробелом, перед ₽/руб
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("(\\d[\\d\\s]{1,5})\\s*(?:₽|руб)").matcher(s);
                if (m.find()) {
                    String d = m.group(1).replaceAll("[^\\d]", "");
                    if (!d.isEmpty() && d.length() <= 7) return new BigDecimal(d);
                }
            }
        } catch (Exception ignored) {}

        log.warn("Не удалось извлечь цену со страницы {}", page.url());
        return BigDecimal.ZERO;
    }

    private String getTextByAttr(Page page, String attr, String value) {
        try {
            ElementHandle el = page.querySelector("[" + attr + "='" + value + "']");
            return el != null ? el.innerText().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private BrowserContext newContext() {
        return newContext(browser, sessionFile);
    }

    private BrowserContext newContext(Browser b, Path sessionFile) {
        Browser.NewContextOptions opts = new Browser.NewContextOptions()
                .setUserAgent(fixedUserAgent)
                .setViewportSize(1366, 768)
                .setLocale("ru-RU");
        String localProxyUrl = localSocksProxy.getLocalProxyUrl();
        if (localProxyUrl != null) {
            opts.setProxy(new Proxy(localProxyUrl));
            log.debug("Контекст: прокси через локальный мост → {}", localProxyUrl);
        } else if (proxyUrl != null && !proxyUrl.isBlank()) {
            opts.setProxy(buildProxy(proxyUrl));
            log.debug("Используем прокси: {}", proxyUrl.replaceAll(":[^@]+@", ":***@"));
        }
        if (Files.exists(sessionFile)) {
            opts.setStorageStatePath(sessionFile);
            log.debug("Загружена сохранённая сессия из {}", sessionFile);
        }
        BrowserContext ctx = b.newContext(opts);
        // Ускорение: не качаем картинки/медиа/шрифты/CSS — фото берём из HTML-атрибутов (data-image-info),
        // а DOM-селекторы не зависят от визуального рендера. Через медленный мобильный прокси это +2–3× к скорости.
        ctx.route("**/*", route -> {
            String type = route.request().resourceType();
            if (type.equals("image") || type.equals("media") || type.equals("font") || type.equals("stylesheet")) {
                route.abort();
            } else {
                route.resume();
            }
        });
        return ctx;
    }

    private void saveSession(BrowserContext ctx) {
        saveSession(ctx, sessionFile);
    }

    private void saveSession(BrowserContext ctx, Path sessionFile) {
        try {
            ctx.storageState(new BrowserContext.StorageStateOptions().setPath(sessionFile));
            log.debug("Сессия сохранена в {}", sessionFile);
        } catch (Exception e) {
            log.debug("Не удалось сохранить сессию: {}", e.getMessage());
        }
    }

    private Page newPage(BrowserContext ctx) {
        Page page = ctx.newPage();
        page.setDefaultNavigationTimeout(NAV_TIMEOUT_MS);
        page.setDefaultTimeout(NAV_TIMEOUT_MS);
        page.addInitScript("""
                // Скрываем признаки headless/automation
                Object.defineProperty(navigator, 'webdriver', { get: () => false });
                delete navigator.__proto__.webdriver;

                Object.defineProperty(navigator, 'plugins', {
                    get: () => [
                        { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
                        { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai', description: '' },
                        { name: 'Native Client', filename: 'internal-nacl-plugin', description: '' }
                    ]
                });
                Object.defineProperty(navigator, 'languages', { get: () => ['ru-RU', 'ru', 'en-US', 'en'] });
                Object.defineProperty(navigator, 'platform', { get: () => 'Win32' });

                window.chrome = { runtime: {}, loadTimes: function(){}, csi: function(){}, app: {} };

                const origQuery = window.navigator.permissions.query;
                window.navigator.permissions.query = (params) =>
                    params.name === 'notifications'
                        ? Promise.resolve({ state: Notification.permission })
                        : origQuery(params);

                const getParam = WebGLRenderingContext.prototype.getParameter;
                WebGLRenderingContext.prototype.getParameter = function(p) {
                    if (p === 37445) return 'Intel Inc.';
                    if (p === 37446) return 'Intel Iris OpenGL Engine';
                    return getParam.call(this, p);
                };

                if (typeof RTCPeerConnection !== 'undefined') {
                    const OrigRTC = window.RTCPeerConnection;
                    window.RTCPeerConnection = function(...args) {
                        const cfg = args[0] || {};
                        cfg.iceServers = [];
                        return new OrigRTC(cfg);
                    };
                    window.RTCPeerConnection.prototype = OrigRTC.prototype;
                }
                """);
        return page;
    }

    private String buildSearchUrl(String oem, String region) {
        return String.format("https://baza.drom.ru/%s/sell_spare_parts/?query=%s", region, oem);
    }

    /**
     * Единый фолбэк после неудачного авто-решения капчи.
     * В режиме видимого браузера даёт человеку шанс решить капчу вручную (до 120 сек).
     * В headless решать некому → false. Если капча уже признана блокирующей — не ждём впустую.
     * @return true, если после ожидания капчи на странице больше нет.
     */
    private boolean waitForManualCaptcha(Page page) {
        if (headless || isCaptchaBlocked()) return false;
        try {
            log.info("Капча — жду ручного решения 120 сек...");
            page.waitForFunction(
                    "() => { try { return !document.body.innerText.includes('Вы не робот') && !location.href.includes('/verify'); } catch(e) { return false; } }",
                    null, new Page.WaitForFunctionOptions().setTimeout(120_000));
            return !hasCaptcha(page);
        } catch (Exception e) {
            log.warn("Ручное решение капчи не выполнено за 120 сек: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Навигация с коротким тайм-аутом ({@link #NAV_TIMEOUT_MS}) и повтором.
     * Раньше единичное «зависание» страницы держало поток все 90 с и портило данные
     * (страница не загрузилась → конкурент уходил «без фото» → ложный worse → завышенная цена).
     * <p>
     * {@code ERR_TUNNEL_CONNECTION_FAILED} мобильного прокси «мигает» (падает быстро и восстанавливается),
     * поэтому туннельные ошибки повторяем до 3 раз с нарастающей паузой — это поглощает кратковременные
     * провалы IP без ручной ротации. Дорогие тайм-ауты (по 30 с) повторяем лишь 1 раз, чтобы не вернуть
     * 90-секундные зависания. Если прокси лёг надолго — серия неудач взведёт предохранитель (см. noteError).
     */
    private void navigateWithRetry(Page page, String url, String referer) {
        RuntimeException last = null;
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                Page.NavigateOptions opt = new Page.NavigateOptions()
                        .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED);
                if (referer != null) opt.setReferer(referer);
                page.navigate(url, opt);
                return;
            } catch (RuntimeException e) {
                last = e;
                boolean tunnel = isProxyConnectionError(e);
                int maxAttempts = tunnel ? 3 : 2;   // туннель падает быстро → больше попыток; тайм-аут дорогой → меньше
                log.warn("Навигация не удалась (попытка {}/{}, {}) {}: {}",
                        attempt, maxAttempts, tunnel ? "ERR_TUNNEL" : "прочее", url, e.getMessage());
                if (attempt >= maxAttempts) throw last;
                long backoff = tunnel ? 1500L * attempt : 1000L;   // ERR_TUNNEL: 1.5с, 3с — дать IP восстановиться
                try { Thread.sleep(backoff); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
    }

    // ==================== ПРЕДОХРАНИТЕЛЬ ПРОКСИ ====================

    /** Прокси «упал»: подряд PROXY_DOWN_THRESHOLD+ ошибок соединения — пора остановить батч. */
    public boolean isProxyDown() {
        return consecutiveProxyErrors >= PROXY_DOWN_THRESHOLD;
    }

    /**
     * drom блокирует капчей. Если ротация IP доступна — стоп наступает лишь когда смена IP не лечит
     * (captchaHardBlocked). Без ротации — старое поведение: CAPTCHA_BLOCK_THRESHOLD+ капч подряд.
     */
    public boolean isCaptchaBlocked() {
        if (canRotate()) return captchaHardBlocked;
        return consecutiveCaptchaFails >= CAPTCHA_BLOCK_THRESHOLD;
    }

    /** Сброс предохранителей перед новым прогоном (после починки прокси/смены IP). */
    public void resetBreakers() {
        consecutiveProxyErrors = 0;
        consecutiveCaptchaFails = 0;
        rotationsSinceProgress = 0;
        captchaHardBlocked = false;
    }

    private boolean canRotate() {
        return changeIpUrl != null && !changeIpUrl.isBlank();
    }

    /** Сколько раз дорожка успешно сменила IP (монотонно). Рост во время прогона OEM = была капча → стоит повторить. */
    public long rotationCount() {
        return totalRotations;
    }

    /**
     * Капча не решена (ни авто-клик, ни 2captcha, ни ручное в GUI).
     * Если есть change-IP ссылка — реактивно крутим IP и продолжаем (счётчик капч сбрасываем).
     * Жёсткий стоп — только после MAX_ROTATIONS_NO_PROGRESS ротаций без единой успешной страницы.
     */
    private void noteCaptchaBlocked() {
        int n = ++consecutiveCaptchaFails;
        if (canRotate()) {
            if (rotationsSinceProgress >= MAX_ROTATIONS_NO_PROGRESS) {
                captchaHardBlocked = true;
                log.warn("Дорожка {}: капча держится после {} смен IP — прокси/подсеть заблокированы, стоп.",
                        laneName, rotationsSinceProgress);
                return;
            }
            log.warn("Дорожка {}: нерешаемая капча — меняю IP (ротация {}/{})...",
                    laneName, rotationsSinceProgress + 1, MAX_ROTATIONS_NO_PROGRESS);
            if (rotateIp()) {
                consecutiveCaptchaFails = 0;   // свежий IP — дорожка снова рабочая, следующий OEM пойдёт с него
            }
            return;
        }
        log.warn("Нерешаемая капча ({} подряд){}", n,
                n >= CAPTCHA_BLOCK_THRESHOLD ? " — drom БЛОКИРУЕТ IP, батч будет остановлен" : "");
    }

    /**
     * Меняет внешний IP мобильного прокси через change-IP ссылку mobileproxy.space.
     * Чистит сессию (куки привязаны к старому IP), ждёт применения IP. Возвращает true при успехе.
     */
    private boolean rotateIp() {
        if (!canRotate()) return false;
        String newIp = callRotate(changeIpUrl);
        // Резервные хосты на случай недоступности основного (см. кабинет mobileproxy)
        if (newIp == null) newIp = callRotate(changeIpUrl.replace("changeip.mobileproxy.space", "aproxy.site"));
        if (newIp == null) newIp = callRotate(changeIpUrl.replace("changeip.mobileproxy.space", "81.200.155.214")
                .replace("https://", "http://"));
        rotationsSinceProgress++;
        if (newIp == null) {
            log.warn("Дорожка {}: смена IP не удалась (ни основная, ни резервные ссылки).", laneName);
            return false;
        }
        try { Files.deleteIfExists(sessionFile); } catch (Exception ignored) {}   // куки старого IP → выбросить
        try { Thread.sleep(ROTATE_APPLY_WAIT_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        totalRotations++;
        log.info("Дорожка {}: IP сменён → {} (ротаций без успеха: {})", laneName, newIp, rotationsSinceProgress);
        return true;
    }

    /** GET по change-IP ссылке; в ответ JSON {"status":"OK","new_ip":...}. Возвращает new_ip или null. */
    private String callRotate(String url) {
        try {
            String full = url + (url.contains("?") ? "&" : "?") + "format=json";
            HttpResponse<String> resp = ROTATE_HTTP.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(full))
                            .header("User-Agent", fixedUserAgent)   // программный вызов требует UA браузера
                            .timeout(java.time.Duration.ofSeconds(30))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            String body = resp.body();
            if (body == null) return null;
            Matcher m = Pattern.compile("\"new_ip\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
            if (m.find()) return m.group(1);
            log.debug("Дорожка {}: ротация без new_ip в ответе: {}", laneName,
                    body.substring(0, Math.min(160, body.length())));
            return null;
        } catch (Exception e) {
            log.debug("Дорожка {}: ошибка вызова change-IP {}: {}", laneName, url, e.getMessage());
            return null;
        }
    }

    /** Страница успешно загрузилась — значит ни прокси, ни капча сейчас не блокируют. */
    private void proxyOk() {
        consecutiveProxyErrors = 0;
        consecutiveCaptchaFails = 0;
        rotationsSinceProgress = 0;   // есть прогресс на текущем IP — счётчик «ротаций без толку» обнуляем
        captchaHardBlocked = false;
    }

    private void noteError(Exception e) {
        if (isProxyConnectionError(e)) {
            int n = ++consecutiveProxyErrors;
            log.warn("Ошибка соединения через прокси ({} подряд){}", n,
                    n >= PROXY_DOWN_THRESHOLD ? " — ПРОКСИ НЕДОСТУПЕН, батч будет остановлен" : "");
        }
    }

    private boolean isProxyConnectionError(Exception e) {
        String m = e == null ? null : e.getMessage();
        return m != null && (m.contains("ERR_TUNNEL_CONNECTION_FAILED")
                || m.contains("ERR_PROXY_CONNECTION_FAILED")
                || m.contains("ERR_ABORTED"));
    }
}
