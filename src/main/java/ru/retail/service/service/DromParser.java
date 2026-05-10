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
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36",
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
                                "--disable-gpu",
                                "--incognito"
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
                    page.waitForURL(url, new Page.WaitForURLOptions().setTimeout(120000));
                    page.waitForLoadState(LoadState.NETWORKIDLE);
                    log.info("Капча решена, продолжаю...");
                } else {
                    log.error("Капча в headless-режиме.");
                    return results;
                }
            }

            page.waitForLoadState(LoadState.NETWORKIDLE);
            Thread.sleep(2000);

            // Собираем ссылки на детальные страницы
            List<String> detailUrls = new ArrayList<>();
            List<ElementHandle> allLinks = page.querySelectorAll("a[href*='/sell_spare_parts/']");
            for (ElementHandle link : allLinks) {
                String href = link.getAttribute("href");
                if (href != null && href.contains("sell_spare_parts") && href.length() > 30
                        && (href.contains("-g") || href.contains(".html"))) {
                    String fullUrl = href.startsWith("http") ? href : "https://baza.drom.ru" + href;
                    if (!detailUrls.contains(fullUrl)) {
                        detailUrls.add(fullUrl);
                    }
                }
            }
            log.info("Найдено ссылок на объявления: {}", detailUrls.size());

            // Дедубликация по полному URL
            Set<String> seenUrls = new HashSet<>();
            int count = 0;

            for (String detailUrl : detailUrls) {
                if (count >= 10) break;

                // Дедубликация по полному URL
                if (!seenUrls.add(detailUrl)) {
                    log.debug("Пропущен дубликат URL: {}", detailUrl);
                    continue;
                }

                // Задержка между запросами
                if (count > 0) {
                    Thread.sleep(1500 + (long)(Math.random() * 2000));
                }

                log.info("[{}/10] Загружаю: {}", count + 1, detailUrl);

                PartPrice partPrice = parseDetailPage(ctx, detailUrl);

                if (partPrice != null) {
                    results.add(partPrice);
                    count++;
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
     * Загружает детальную страницу и извлекает из неё все данные
     */
    private PartPrice parseDetailPage(BrowserContext ctx, String url) {
        Page detailPage = null;
        try {
            detailPage = ctx.newPage();
            detailPage.navigate(url);
            detailPage.waitForLoadState(LoadState.NETWORKIDLE);
            Thread.sleep(1500);

            String actualUrl = detailPage.url();

            // Цена
            String priceText = getTextByAttribute(detailPage, "data-field", "price");
            if (priceText == null) return null;
            priceText = priceText.replaceAll("[^\\d.]", "");
            if (priceText.isEmpty()) return null;
            BigDecimal price = new BigDecimal(priceText);

            // Название
            String title = "";
            ElementHandle titleEl = detailPage.querySelector("h1.subject span");
            if (titleEl != null) {
                title = titleEl.innerText().trim();
            }

            // Состояние
            String condition = getTextByAttribute(detailPage, "data-field", "condition");

            // Оригинальность
            String authenticity = getTextByAttribute(detailPage, "data-field", "autoPartsAuthenticity");

            // Производитель
            String manufacturer = getTextByAttribute(detailPage, "data-field", "manufacturer");

            // Номер запчасти
            String oem = getTextByAttribute(detailPage, "data-field", "autoPartsOemNumber");

            // Продавец
            String seller = "";
            ElementHandle sellerEl = detailPage.querySelector(".userNick a");
            if (sellerEl != null) seller = sellerEl.innerText().trim();

            // Рейтинг
            String rating = "";
            ElementHandle ratingEl = detailPage.querySelector(".ratingPositive");
            if (ratingEl != null) rating = ratingEl.innerText().trim();

            // Город
            String city = "";
            List<ElementHandle> sellerDivs = detailPage.querySelectorAll(".seller-summary > div");
            for (ElementHandle div : sellerDivs) {
                String text = div.innerText().trim();
                if (text.length() > 1 && text.length() < 30
                        && !text.contains("Рейтинг")
                        && !text.contains("отзыв")
                        && !text.contains("Продавец")
                        && !text.contains("на сайте")
                        && !text.contains("предложений")
                        && !text.matches(".*\\d{2,}.*")) {
                    city = text;
                }
            }

            // Описание
            String desc = getTextByAttribute(detailPage, "data-field", "text");

            // Собираем описание
            StringBuilder sb = new StringBuilder();
            if (condition != null) sb.append("Состояние: ").append(condition).append("\n");
            if (authenticity != null) sb.append("Оригинальность: ").append(authenticity).append("\n");
            if (manufacturer != null) sb.append("Производитель: ").append(manufacturer).append("\n");
            if (oem != null) sb.append("Номер запчасти: ").append(oem.replaceAll("\\s+", "")).append("\n");
            if (!seller.isEmpty()) sb.append("Продавец: ").append(seller).append("\n");
            if (!rating.isEmpty()) sb.append("Рейтинг: ").append(rating).append("\n");
            if (!city.isEmpty()) sb.append("Город: ").append(city).append("\n");
            if (desc != null && desc.length() > 10)
                sb.append("Описание: ").append(desc.substring(0, Math.min(300, desc.length()))).append("\n");

            log.info("[{}] ✅ {} — {}₽",
                    actualUrl.substring(Math.max(0, actualUrl.length() - 40)),
                    title.length() > 50 ? title.substring(0, 50) : title, price);

            return PartPrice.builder()
                    .title(title)
                    .price(price)
                    .url(actualUrl)
                    .location(city)
                    .dealer(seller)
                    .description(sb.toString())
                    .build();

        } catch (Exception e) {
            log.warn("Ошибка {}: {}", url, e.getMessage());
            return null;
        } finally {
            if (detailPage != null) detailPage.close();
        }
    }

    private String getTextByAttribute(Page page, String attr, String value) {
        try {
            ElementHandle el = page.querySelector("[" + attr + "='" + value + "']");
            return el != null ? el.innerText().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String randomUserAgent() {
        return USER_AGENTS[(int)(Math.random() * USER_AGENTS.length)];
    }

    private String buildSearchUrl(String oem, String region) {
        return String.format("https://baza.drom.ru/%s/oem/%s/", region, oem);
    }
}
