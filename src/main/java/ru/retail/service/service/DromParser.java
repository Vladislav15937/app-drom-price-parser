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
import ru.retail.service.dto.PartPrice;

import java.math.BigDecimal;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class DromParser {

    private static final String[] USER_AGENTS = {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:126.0) Gecko/20100101 Firefox/126.0"
    };

    private Playwright playwright;
    private Browser browser;

    @Value("${drom.proxy.url:}")
    private String proxyUrl;

    @Value("${drom.headless:true}")
    private boolean headless;

    @PostConstruct
    public void init() {
        playwright = Playwright.create();

        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                .setHeadless(headless)
                .setArgs(List.of(
                        "--disable-blink-features=AutomationControlled",
                        "--no-sandbox",
                        "--disable-dev-shm-usage",
                        "--disable-gpu",
                        "--disable-setuid-sandbox"
                ));

        // Прокси (если настроен)
        if (proxyUrl != null && !proxyUrl.isEmpty()) {
            launchOptions.setProxy(new Proxy(proxyUrl));
            log.info("Использую прокси: {}", proxyUrl);
        }

        browser = playwright.chromium().launch(launchOptions);
        log.info("Браузер запущен (headless={})", headless);
    }

    @PreDestroy
    public void destroy() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        log.info("Браузер закрыт");
    }

    public List<PartPrice> parseParts(String oemNumber, String region) {
        String url = buildSearchUrl(oemNumber, region);
        log.info("Парсинг URL: {}", url);

        List<PartPrice> results = new ArrayList<>();

        // Новый контекст для каждого запроса = новый fingerprint
        BrowserContext ctx = browser.newContext(
                new Browser.NewContextOptions()
                        .setUserAgent(randomUserAgent())
                        .setViewportSize(1920, 1080)
                        .setLocale("ru-RU")
                        .setTimezoneId("Europe/Moscow")
        );

        Page page = ctx.newPage();

        // Антидетект — скрываем WebDriver
        page.addInitScript("""
            Object.defineProperty(navigator, 'webdriver', { get: () => false });
            Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
            Object.defineProperty(navigator, 'languages', { get: () => ['ru-RU', 'ru'] });
        """);

        try {
            // Навигация со случайной задержкой
            page.navigate(url);

            // Случайная пауза 3-8 секунд (эмуляция чтения страницы)
            int pause = 3000 + (int) (Math.random() * 5000);
            log.debug("Пауза {} мс", pause);
            Thread.sleep(pause);

            // Имитация скролла человеком
            for (int i = 0; i < 3; i++) {
                page.evaluate("window.scrollBy(0, " + (200 + Math.random() * 500) + ")");
                Thread.sleep(300 + (int) (Math.random() * 700));
            }

            page.waitForLoadState(LoadState.NETWORKIDLE);
            Thread.sleep(2000 + (int) (Math.random() * 2000));

            // Ищем все объявления
            List<ElementHandle> bulls = page.querySelectorAll(".bull-item");
            log.info("Найдено объявлений: {}", bulls.size());

            int count = 0;
            for (ElementHandle bull : bulls) {
                if (count >= 10) break; // Топ-10 самых дешёвых

                try {
                    String fullText = bull.innerText().trim();
                    if (fullText.length() < 30) continue;

                    String[] lines = fullText.split("\n");

                    BigDecimal price = null;
                    String title = "";
                    String location = "";
                    String dealer = "";

                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i].trim();

                        if (line.matches("^(передний|задний|[ЛП])$")) continue;

                        if (price == null && line.matches(".*\\d+.*₽.*")) {
                            String priceText = line.replaceAll("[^\\d.]", "");
                            if (!priceText.isEmpty()) {
                                try {
                                    price = new BigDecimal(priceText);
                                } catch (Exception e) {
                                    // не число
                                }
                            }
                            continue;
                        }

                        if (title.isEmpty() &&
                                !line.contains("₽") &&
                                !line.startsWith("в ") &&
                                !line.equals("Защищённая сделка") &&
                                !line.startsWith("доставка") &&
                                !line.equals("Доставка почтой") &&
                                line.length() > 15) {
                            title = line;
                        }

                        if (location.isEmpty() && line.startsWith("в ") && line.length() < 30) {
                            location = line;
                        }

                        if (i == lines.length - 1 ||
                                (i > 0 && (lines[i - 1].contains("вчера") ||
                                        lines[i - 1].contains("сегодня") ||
                                        lines[i - 1].contains("мая") ||
                                        lines[i - 1].contains("апреля")))) {
                            String candidate = line.trim();
                            if (!candidate.contains("₽") && candidate.length() > 3 && !candidate.startsWith("в ")) {
                                dealer = candidate;
                            }
                        }
                    }

                    if (price != null) {
                        results.add(PartPrice.builder()
                                .title(title.isEmpty() ? "Деталь" : title)
                                .price(price)
                                .location(location)
                                .dealer(dealer)
                                .build());
                        count++;
                    }

                } catch (Exception e) {
                    log.debug("Ошибка элемента: {}", e.getMessage());
                }
            }

            // Сортируем по цене
            results.sort((a, b) -> a.getPrice().compareTo(b.getPrice()));

        } catch (Exception e) {
            log.error("Ошибка парсинга: {}", e.getMessage());
        } finally {
            page.close();
            ctx.close();
        }

        log.info("Собрано цен: {}", results.size());
        return results;
    }

    private String randomUserAgent() {
        return USER_AGENTS[(int) (Math.random() * USER_AGENTS.length)];
    }

    private String buildSearchUrl(String oem, String region) {
        return String.format("https://baza.drom.ru/%s/oem/%s/", region, oem);
    }
}