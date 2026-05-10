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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    @Value("${drom.headless:true}")
    private boolean headless;

    @PostConstruct
    public void init() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setHeadless(headless)
                        .setArgs(List.of(
                                "--disable-blink-features=AutomationControlled",
                                "--no-sandbox",
                                "--disable-dev-shm-usage",
                                "--disable-gpu"
                        ))
        );
    }

    @PreDestroy
    public void destroy() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    public List<PartPrice> parseParts(String oemNumber, String region) {
        String url = buildSearchUrl(oemNumber, region);
        log.info("Парсинг URL: {}", url);

        List<PartPrice> results = new ArrayList<>();
        BrowserContext ctx = browser.newContext(
                new Browser.NewContextOptions()
                        .setUserAgent(randomUserAgent())
                        .setViewportSize(1920, 1080)
                        .setLocale("ru-RU")
        );

        Page page = ctx.newPage();
        page.addInitScript("""
        Object.defineProperty(navigator, 'webdriver', { get: () => false });
        delete navigator.__proto__.webdriver;
    """);

        try {
            page.navigate(url);
            Thread.sleep(3000);

            String bodyText = page.innerText("body");
            if (bodyText.contains("Вы не робот") || bodyText.contains("подозрительный трафик")) {
                log.warn("Обнаружена капча!");
                if (!headless) {
                    log.info("Решите капчу в браузере. Ожидаю 60 секунд...");
                    page.waitForSelector(".bull-item, [data-ftid='component_bull'], [class*='css-']",
                            new Page.WaitForSelectorOptions().setTimeout(60000));
                } else {
                    log.error("Капча в headless-режиме.");
                    return results;
                }
            }

            page.waitForLoadState(LoadState.NETWORKIDLE);
            Thread.sleep(2000);

            // Поиск объявлений
            List<ElementHandle> bulls = page.querySelectorAll(".bull-item");
            if (bulls.isEmpty()) {
                bulls = page.querySelectorAll("[data-ftid='component_bull']");
            }

            log.info("Найдено объявлений: {}", bulls.size());

            if (bulls.isEmpty()) {
                log.error("Объявления не найдены");
                page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("error.png")));
                return results;
            }

            // Парсим с дедубликацией
            Set<String> seen = new HashSet<>();
            int count = 0;

            for (ElementHandle bull : bulls) {
                if (count >= 10) break;

                try {
                    String text = bull.innerText().trim();
                    if (text.length() < 30) continue;

                    PartPrice.PartPriceBuilder builder = PartPrice.builder();
                    extractBasicInfo(text, builder);

                    // Извлекаем ссылку
                    String detailUrl = extractDetailUrl(bull);

                    // Дедубликация по названию + цене + продавцу
                    PartPrice temp = builder.build();
                    String key = temp.getTitle() + "|" + temp.getPrice() + "|" + temp.getDealer();
                    if (seen.contains(key)) continue;
                    seen.add(key);

                    // Загружаем детальную страницу
                    if (detailUrl != null) {
                        log.debug("Загружаю: {}", detailUrl);
                        String description = fetchDetailPage(ctx, detailUrl);
                        builder.description(description);
                    }

                    PartPrice partPrice = builder.build();
                    if (partPrice.getPrice() != null) {
                        results.add(partPrice);
                        count++;
                    }

                } catch (Exception e) {
                    log.debug("Ошибка элемента: {}", e.getMessage());
                }
            }

            results.sort((a, b) -> a.getPrice().compareTo(b.getPrice()));

        } catch (Exception e) {
            log.error("Ошибка парсинга: {}", e.getMessage());
        } finally {
            page.close();
            ctx.close();
        }

        log.info("Собрано уникальных цен: {}", results.size());
        return results;
    }

    /**
     * Извлекает ссылку на детальную страницу из карточки
     */
    private String extractDetailUrl(ElementHandle bull) {
        String[] linkSelectors = {
                "a[href*='/sell_spare_parts/']",
                "a[href*='baza.drom.ru']",
                "[data-ftid='bull_title']",
                "a"
        };

        for (String selector : linkSelectors) {
            try {
                ElementHandle el = bull.querySelector(selector);
                if (el != null) {
                    String href = el.getAttribute("href");
                    if (href != null && href.contains("sell_spare_parts") && href.length() > 20) {
                        return href.startsWith("http") ? href : "https://baza.drom.ru" + href;
                    }
                }
            } catch (Exception e) {}
        }
        return null;
    }

    /**
     * Извлекает базовую информацию из карточки поиска
     */
    private void extractBasicInfo(String text, PartPrice.PartPriceBuilder builder) {
        String[] lines = text.split("\n");

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
                    try { price = new BigDecimal(priceText); } catch (Exception e) {}
                }
                continue;
            }

            if (title.isEmpty() && !line.contains("₽") && !line.startsWith("в ")
                    && !line.equals("Защищённая сделка") && !line.startsWith("доставка")
                    && !line.equals("Доставка почтой") && line.length() > 15) {
                title = line;
            }

            if (location.isEmpty() && line.startsWith("в ") && line.length() < 30) {
                location = line;
            }

            if (i == lines.length - 1 || (i > 0 && (lines[i-1].contains("вчера")
                    || lines[i-1].contains("сегодня") || lines[i-1].contains("мая")
                    || lines[i-1].contains("апреля")))) {
                String candidate = line.trim();
                if (!candidate.contains("₽") && candidate.length() > 3 && !candidate.startsWith("в ")) {
                    dealer = candidate;
                }
            }
        }

        builder.title(title.isEmpty() ? "Деталь" : title)
                .price(price)
                .location(location)
                .dealer(dealer);
    }

    /**
     * Заходит на детальную страницу и собирает полное описание
     */
    private String fetchDetailPage(BrowserContext ctx, String url) {
        Page detailPage = null;
        try {
            detailPage = ctx.newPage();
            detailPage.navigate(url);
            detailPage.waitForLoadState(LoadState.NETWORKIDLE);
            Thread.sleep(2000 + (int)(Math.random() * 2000));

            StringBuilder sb = new StringBuilder();

            // Состояние
            String condition = extractField(detailPage, "Состояние");
            if (condition != null) sb.append("Состояние: ").append(condition).append("\n");

            // Производитель
            String manufacturer = extractField(detailPage, "Производитель");
            if (manufacturer != null) sb.append("Производитель: ").append(manufacturer).append("\n");

            // Номер запчасти
            String partNumber = extractField(detailPage, "Номер запчасти");
            if (partNumber != null) sb.append("Номер запчасти: ").append(partNumber).append("\n");

            // Подходит (автомобили)
            String cars = extractCars(detailPage);
            if (cars != null) sb.append("Подходит: ").append(cars).append("\n");

            // Примечание
            String note = extractField(detailPage, "Примечание");
            if (note != null) sb.append("Примечание: ").append(note).append("\n");

            // Рейтинг и отзывы
            String rating = extractRating(detailPage);
            if (rating != null) sb.append("Продавец: ").append(rating).append("\n");

            // Наличие
            String availability = extractAvailability(detailPage);
            if (availability != null) sb.append("Наличие: ").append(availability).append("\n");

            // Полный текст для контекста
            String bodyText = detailPage.innerText("body");
            if (bodyText.length() > 1500) bodyText = bodyText.substring(0, 1500) + "...";
            sb.append("\nПолное описание:\n").append(bodyText);

            return sb.toString();

        } catch (Exception e) {
            log.warn("Ошибка при загрузке детальной страницы {}: {}", url, e.getMessage());
            return null;
        } finally {
            if (detailPage != null) detailPage.close();
        }
    }

    private String extractField(Page page, String fieldName) {
        try {
            String text = page.innerText("body");
            String[] lines = text.split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].trim().equals(fieldName) && i + 1 < lines.length) {
                    return lines[i + 1].trim();
                }
            }
        } catch (Exception e) {}
        return null;
    }

    private String extractCars(Page page) {
        try {
            StringBuilder cars = new StringBuilder();
            ElementHandle container = page.querySelector("[class*='css-'][class*='list']");
            if (container != null) {
                String text = container.innerText();
                return text.replace("\n", ", ").trim();
            }
        } catch (Exception e) {}
        return null;
    }

    private String extractRating(Page page) {
        try {
            String text = page.innerText("body");
            if (text.contains("Рейтинг")) {
                int idx = text.indexOf("Рейтинг");
                return text.substring(idx, Math.min(idx + 80, text.length()))
                        .replace("\n", " ").trim();
            }
        } catch (Exception e) {}
        return null;
    }

    private String extractAvailability(Page page) {
        try {
            String text = page.innerText("body");
            if (text.contains("В наличии") || text.contains("Под заказ")) {
                int idx = text.indexOf("Наличие товара");
                if (idx == -1) idx = text.indexOf("В наличии");
                if (idx >= 0) {
                    return text.substring(idx, Math.min(idx + 50, text.length()))
                            .replace("\n", " ").trim();
                }
            }
        } catch (Exception e) {}
        return null;
    }

    private String randomUserAgent() {
        return USER_AGENTS[(int)(Math.random() * USER_AGENTS.length)];
    }

    private String buildSearchUrl(String oem, String region) {
        return String.format("https://baza.drom.ru/%s/oem/%s/", region, oem);
    }
}