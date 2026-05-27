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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static final Path SESSION_FILE = Paths.get("drom-session.json");

    // Регионы Сибири для широкого поиска
    static final List<String> SIBERIA_REGIONS = List.of(
            "barnaul", "novosibirsk", "omsk", "tomsk", "kemerovo", "krasnoyarsk"
    );

    private Playwright playwright;
    private Browser browser;

    @Value("${drom.headless:true}")
    private boolean headless;

    @Value("${drom.proxy.url:}")
    private String proxyUrl;

    private final CaptchaSolverService captchaSolver;
    private final ApifyDromService apifyDromService;
    private final LocalSocksProxy localSocksProxy;
    // Фиксированный UA на весь сеанс — смена UA между запросами инвалидирует сессию Drom.ru
    private final String fixedUserAgent = USER_AGENTS[(int) (Math.random() * USER_AGENTS.length)];

    public DromParser(CaptchaSolverService captchaSolver, ApifyDromService apifyDromService,
                      LocalSocksProxy localSocksProxy) {
        this.captchaSolver = captchaSolver;
        this.apifyDromService = apifyDromService;
        this.localSocksProxy = localSocksProxy;
    }

    @PostConstruct
    public void init() {
        playwright = Playwright.create();
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
        String localProxyUrl = localSocksProxy.getLocalProxyUrl();
        if (localProxyUrl != null) {
            // SOCKS5 с авторизацией: Chromium не поддерживает, используем локальный HTTP-мост
            launchOptions.setProxy(new Proxy(localProxyUrl));
            log.info("Прокси: через локальный SOCKS5-мост → {}", localProxyUrl);
        } else if (proxyUrl != null && !proxyUrl.isBlank()) {
            launchOptions.setProxy(buildProxy(proxyUrl));
        }
        browser = playwright.chromium().launch(launchOptions);
    }

    @PreDestroy
    public void destroy() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
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
            page.navigate(url, new Page.NavigateOptions().setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            Thread.sleep(1500);

            if (hasCaptcha(page)) {
                boolean solved = tryClickDromCheckbox(page) || captchaSolver.solve(page);
                if (!solved) {
                    if (!headless) {
                        log.info("Капча на объявлении — жду ручного решения 120 сек...");
                        page.waitForFunction("() => { try { return !document.body.innerText.includes('Вы не робот'); } catch(e) { return false; } }",
                                null, new Page.WaitForFunctionOptions().setTimeout(120_000));
                    } else {
                        log.warn("Капча на моём объявлении, автоматическое решение не помогло. Данные недоступны.");
                        return MyListingInfo.builder()
                                .description("").condition("не указано").manufacturer("не указано")
                                .photoUrls(List.of()).price(BigDecimal.ZERO).build();
                    }
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
        return parseParts(oemNumber, region, 10, Set.of());
    }

    public List<PartPrice> parseParts(String oemNumber, String region, int limit) {
        return parseParts(oemNumber, region, limit, Set.of());
    }

    public List<PartPrice> parseParts(String oemNumber, String region, int limit, Set<String> excludeUrls) {
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

        BrowserContext ctx = newContext();
        Page page = newPage(ctx);

        try {
            // ── Шаг 1: загружаем страницу поиска ──
            page.navigate(searchUrl, new Page.NavigateOptions().setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
            Thread.sleep(3000);

            if (hasCaptcha(page)) {
                boolean solved = tryClickDromCheckbox(page) || captchaSolver.solve(page);
                if (solved) saveSession(ctx);
                if (!solved) {
                    if (!headless) {
                        log.info("Капча на поиске [{}] — жду ручного решения 120 сек...", region);
                        page.waitForURL(searchUrl, new Page.WaitForURLOptions().setTimeout(120_000));
                        saveSession(ctx);
                    } else {
                        log.error("Капча не решена автоматически, возвращаем пустой результат для [{}].", region);
                        return List.of();
                    }
                }
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            }

            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            Thread.sleep(2000);

            // ── Шаг 2: собираем ссылки с именами дилеров ──
            List<SearchEntry> entries = collectSearchEntries(page);
            Set<String> normalizedExcludes = excludeUrls.stream()
                    .map(u -> u.toLowerCase().replaceAll("/+$", ""))
                    .collect(Collectors.toSet());
            List<String> allUrls = entries.stream()
                    .map(SearchEntry::url)
                    .filter(u -> normalizedExcludes.isEmpty() ||
                            !normalizedExcludes.contains(u.toLowerCase().replaceAll("/+$", "")))
                    .collect(Collectors.toList());
            // Объявления на baza.drom.ru используют /g[ID].html — регион в URL отсутствует.
            // Страница поиска уже отфильтрована по региону, поэтому используем все URL напрямую.
            String regionSlug = "/" + region + "/";
            String normalizedOem = oemNumber.replaceAll("\\s+", "").toUpperCase();
            List<String> regional = allUrls.stream()
                    .filter(u -> u.contains(regionSlug))
                    .distinct()
                    .collect(Collectors.toList());
            if (regional.isEmpty()) {
                // Новый формат URL /g[ID].html без региона в пути — берём все
                regional = new ArrayList<>(allUrls);
            }

            // Пре-фильтр по OEM прямо со страницы поиска: исключаем чужие OEM без захода на детальную
            Map<String, String> urlToOem = entries.stream()
                    .filter(e -> !e.oem().isBlank())
                    .collect(Collectors.toMap(SearchEntry::url, e -> e.oem().toUpperCase(),
                            (a1, b) -> a1));
            List<String> oemFiltered = regional.stream()
                    .filter(u -> {
                        String pageOem = urlToOem.get(u);
                        return pageOem == null || pageOem.equals(normalizedOem)
                                || pageOem.contains(normalizedOem) || normalizedOem.contains(pageOem);
                    })
                    .collect(Collectors.toList());
            // Если пре-фильтр убрал всё — значит OEM не распознан, берём все
            if (oemFiltered.isEmpty()) oemFiltered = regional;

            // Приоритет: OEM в URL (старый формат) идут первыми
            String oemLower = oemNumber.toLowerCase().replaceAll("\\s+", "");
            Set<String> withOemSet = oemFiltered.stream()
                    .filter(u -> u.toLowerCase().contains(oemLower))
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            List<String> prioritized = new ArrayList<>(withOemSet);
            oemFiltered.stream().filter(u -> !withOemSet.contains(u)).forEach(prioritized::add);
            log.info("Ссылок всего: {} | региональных: {} | после OEM-фильтра: {} | исключено: {}",
                    entries.size(), regional.size(), oemFiltered.size(), excludeUrls.size());

            // ── Шаг 3: обходим детальные страницы В ТОМ ЖЕ контексте ──
            List<PartPrice> results = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int count = 0;
            int attempt = 0;

            for (String detailUrl : prioritized) {
                if (count >= limit) break;
                if (!seen.add(detailUrl)) continue;
                if (attempt > 0) try { Thread.sleep(1000 + (long) (Math.random() * 800)); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                attempt++;
                log.info("[попытка {}, результат {}/{}] {}", attempt, count + 1, limit, detailUrl);

                try {
                    page.navigate(detailUrl, new Page.NavigateOptions()
                            .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                            .setReferer(searchUrl));
                    page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                    Thread.sleep(800);

                    if (hasCaptcha(page)) {
                        log.info("Капча на детальной странице: {}", detailUrl);
                        boolean solved = tryClickDromCheckbox(page) || captchaSolver.solve(page);
                        if (solved) saveSession(ctx);
                        if (!solved) {
                            if (!headless) {
                                log.info("Капча — жду ручного решения 120 сек...");
                                page.waitForFunction("() => { try { return !document.body.innerText.includes('Вы не робот'); } catch(e) { return false; } }",
                                        null, new Page.WaitForFunctionOptions().setTimeout(120_000));
                                saveSession(ctx);
                            } else {
                                log.warn("Капча не решена, пропускаем: {}", detailUrl);
                                continue;
                            }
                        }
                        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                        Thread.sleep(800);
                    }

                    PartPrice part = extractPartFromPage(page, detailUrl);
                    if (part != null) { results.add(part); count++; }
                } catch (Exception e) {
                    log.warn("Ошибка [{}]: {}", detailUrl, e.getMessage());
                }
            }

            results.sort((a, b) -> a.getPrice().compareTo(b.getPrice()));
            log.info("Собрано в [{}]: {} предложений", region, results.size());
            return results;

        } catch (Exception e) {
            log.error("Ошибка парсинга [{}]: {}", region, e.getMessage());
            return List.of();
        } finally {
            page.close();
            ctx.close();
        }
    }

    // ==================== ПАРСИНГ ПО СИБИРИ (ограниченный) ====================

    /**
     * Быстрый сбор цен по Сибирским регионам. Берёт не более 3 объявлений на регион.
     * Исключает регион myCity, чтобы не дублировать городской поиск.
     */
    public List<PartPrice> parseSiberia(String oemNumber, String myCity) {
        log.info("Поиск по Сибири (исключая {})", myCity);
        List<PartPrice> all = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();

        for (String region : SIBERIA_REGIONS) {
            if (region.equals(myCity)) continue;
            if (all.size() >= 18) break;

            try {
                List<PartPrice> regionResults = parseParts(oemNumber, region, 3);
                int added = 0;
                for (PartPrice p : regionResults) {
                    if (seenUrls.add(p.getUrl())) {
                        all.add(p);
                        added++;
                    }
                }
                log.info("Сибирь [{}]: {} предложений, добавлено уникальных: {}", region, regionResults.size(), added);
            } catch (Exception e) {
                log.warn("Ошибка парсинга Сибирь [{}]: {}", region, e.getMessage());
            }
        }

        log.info("Итого по Сибири: {} уникальных предложений", all.size());
        return all;
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

    /** Открывает собственный контекст для парсинга одной страницы (не используется в parseParts). */
    private PartPrice parseDetailPage(String url) {
        BrowserContext ctx = newContext();
        Page detailPage = null;
        try {
            detailPage = newPage(ctx);
            detailPage.navigate(url, new Page.NavigateOptions().setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
            detailPage.waitForLoadState(LoadState.DOMCONTENTLOADED);
            Thread.sleep(1200);

            if (hasCaptcha(detailPage)) {
                log.info("Капча на детальной странице: {}", url);
                boolean solved = tryClickDromCheckbox(detailPage) || captchaSolver.solve(detailPage);
                if (solved) saveSession(ctx);
                if (!solved) {
                    if (!headless) {
                        log.info("Капча — жду ручного решения 120 сек...");
                        detailPage.waitForFunction("() => { try { return !document.body.innerText.includes('Вы не робот'); } catch(e) { return false; } }",
                                null, new Page.WaitForFunctionOptions().setTimeout(120_000));
                        saveSession(ctx);
                    } else {
                        log.warn("Капча не решена, пропускаем: {}", url);
                        return null;
                    }
                }
                detailPage.waitForLoadState(LoadState.DOMCONTENTLOADED);
                Thread.sleep(800);
            }

            return extractPartFromPage(detailPage, url);

        } catch (Exception e) {
            log.warn("Ошибка {}: {}", url, e.getMessage());
            return null;
        } finally {
            if (detailPage != null) detailPage.close();
            ctx.close();
        }
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ ====================

    /** Данные объявления, извлечённые прямо со страницы результатов поиска. */
    record SearchEntry(String url, String dealer, String oem, String title, BigDecimal price, String date) {
        SearchEntry(String url, String dealer) { this(url, dealer, "", "", null, ""); }
        SearchEntry(String url, String dealer, String oem) { this(url, dealer, oem, "", null, ""); }
    }

    private List<String> collectListingLinks(Page page) {
        return collectSearchEntries(page).stream().map(SearchEntry::url).collect(Collectors.toList());
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
                    // Цена: [data-field="price"] или .bull-item__price
                    const priceEl = row.querySelector('[data-field="price"], .bull-item__price, .price-value');
                    let priceText = priceEl ? priceEl.textContent.replace(/[^\\d]/g,'') : '';
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
            page.navigate(searchUrl, new Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
            Thread.sleep(3000);
            if (hasCaptcha(page)) {
                boolean solved = tryClickDromCheckbox(page) || captchaSolver.solve(page);
                if (solved) saveSession(ctx);
                if (!solved && !headless) {
                    page.waitForURL(searchUrl, new Page.WaitForURLOptions().setTimeout(120_000));
                    saveSession(ctx);
                } else if (!solved) return null;
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            }
            Thread.sleep(1500);
            String companyLower = myCompany.toLowerCase();
            return collectSearchEntries(page).stream()
                    .filter(e -> e.dealer().toLowerCase().contains(companyLower))
                    .map(SearchEntry::url)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
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
        if (Files.exists(SESSION_FILE)) {
            opts.setStorageStatePath(SESSION_FILE);
            log.debug("Загружена сохранённая сессия из {}", SESSION_FILE);
        }
        return browser.newContext(opts);
    }

    private void saveSession(BrowserContext ctx) {
        try {
            ctx.storageState(new BrowserContext.StorageStateOptions().setPath(SESSION_FILE));
            log.debug("Сессия сохранена в {}", SESSION_FILE);
        } catch (Exception e) {
            log.debug("Не удалось сохранить сессию: {}", e.getMessage());
        }
    }

    private Page newPage(BrowserContext ctx) {
        Page page = ctx.newPage();
        page.setDefaultNavigationTimeout(90_000);
        page.setDefaultTimeout(90_000);
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

    private String randomUserAgent() {
        return USER_AGENTS[(int) (Math.random() * USER_AGENTS.length)];
    }

    private String buildSearchUrl(String oem, String region) {
        return String.format("https://baza.drom.ru/%s/sell_spare_parts/?query=%s", region, oem);
    }
}
