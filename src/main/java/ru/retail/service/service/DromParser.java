package ru.retail.service.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.retail.service.dto.MyListingInfo;
import ru.retail.service.dto.PartPrice;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    // Регионы Сибири для широкого поиска
    static final List<String> SIBERIA_REGIONS = List.of(
            "barnaul", "novosibirsk", "omsk", "tomsk", "kemerovo", "krasnoyarsk"
    );

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
            page.navigate(url);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            Thread.sleep(1500);

            if (hasCaptcha(page)) {
                if (!headless) {
                    log.info("Капча на странице объявления — жду ручного решения 120 сек...");
                    page.waitForFunction("() => !document.body.innerText.includes('Вы не робот')",
                            new Page.WaitForFunctionOptions().setTimeout(120_000));
                    page.waitForLoadState(LoadState.NETWORKIDLE);
                    Thread.sleep(1000);
                } else {
                    log.warn("Капча на моём объявлении в headless-режиме. Данные недоступны.");
                    return MyListingInfo.builder()
                            .description("").condition("не указано").manufacturer("не указано")
                            .photoUrls(List.of()).price(BigDecimal.ZERO).build();
                }
            }

            String title = "";
            ElementHandle titleEl = page.querySelector("h1.subject span, h1 span");
            if (titleEl != null) title = titleEl.innerText().trim();

            String priceText = getTextByAttr(page, "data-field", "price");
            BigDecimal price = BigDecimal.ZERO;
            if (priceText != null) {
                String digits = priceText.replaceAll("[^\\d.]", "");
                if (!digits.isEmpty()) price = new BigDecimal(digits);
            }

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
        return parseParts(oemNumber, region, 10);
    }

    public List<PartPrice> parseParts(String oemNumber, String region, int limit) {
        String url = buildSearchUrl(oemNumber, region);
        log.info("Парсинг конкурентов [{}]: {}", region, url);

        List<PartPrice> results = new ArrayList<>();
        BrowserContext ctx = newContext();
        Page page = newPage(ctx);

        try {
            page.navigate(url);
            Thread.sleep(3000);

            if (hasCaptcha(page)) {
                if (!headless) {
                    log.info("Капча — ожидаю ручного решения 120 сек...");
                    page.waitForURL(url, new Page.WaitForURLOptions().setTimeout(120_000));
                    page.waitForLoadState(LoadState.NETWORKIDLE);
                } else {
                    log.error("Капча в headless-режиме.");
                    return results;
                }
            }

            page.waitForLoadState(LoadState.NETWORKIDLE);
            Thread.sleep(2000);

            List<String> allUrls = collectListingLinks(page);
            String regionSlug = "/" + region + "/";
            List<String> detailUrls = allUrls.stream()
                    .filter(u -> u.contains(regionSlug))
                    .collect(Collectors.toList());
            log.info("Найдено ссылок: {} (в регионе {}: {})", allUrls.size(), region, detailUrls.size());

            Set<String> seen = new HashSet<>();
            int count = 0;
            for (String detailUrl : detailUrls) {
                if (count >= limit) break;
                if (!seen.add(detailUrl)) continue;

                if (count > 0) Thread.sleep(1500 + (long) (Math.random() * 2000));
                log.info("[{}/{}] {}", count + 1, limit, detailUrl);

                PartPrice part = parseDetailPage(ctx, detailUrl);
                if (part != null) { results.add(part); count++; }
            }

            results.sort((a, b) -> a.getPrice().compareTo(b.getPrice()));
        } catch (Exception e) {
            log.error("Ошибка парсинга [{}]: {}", region, e.getMessage());
        } finally {
            page.close();
            ctx.close();
        }

        log.info("Собрано в [{}]: {} предложений", region, results.size());
        return results;
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

    private PartPrice parseDetailPage(BrowserContext ctx, String url) {
        Page detailPage = null;
        try {
            detailPage = ctx.newPage();
            detailPage.navigate(url);
            detailPage.waitForLoadState(LoadState.NETWORKIDLE);
            Thread.sleep(1200);

            String actualUrl = detailPage.url();

            String priceText = getTextByAttr(detailPage, "data-field", "price");
            if (priceText == null) return null;
            priceText = priceText.replaceAll("[^\\d.]", "");
            if (priceText.isEmpty()) return null;
            BigDecimal price = new BigDecimal(priceText);

            String title = "";
            ElementHandle titleEl = detailPage.querySelector("h1.subject span");
            if (titleEl != null) title = titleEl.innerText().trim();

            String condition    = getTextByAttr(detailPage, "data-field", "condition");
            String authenticity = getTextByAttr(detailPage, "data-field", "autoPartsAuthenticity");
            String manufacturer = getTextByAttr(detailPage, "data-field", "manufacturer");
            String oem          = getTextByAttr(detailPage, "data-field", "autoPartsOemNumber");
            String desc         = getTextByAttr(detailPage, "data-field", "text");
            String publishedDate = extractDate(detailPage);
            String city          = extractCity(detailPage);
            List<String> photos  = extractPhotoUrls(detailPage, 3);

            String seller = "";
            ElementHandle sellerEl = detailPage.querySelector(".userNick a");
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
                    .location(city).dealer(seller)
                    .publishedDate(publishedDate)
                    .photoUrls(photos)
                    .description(sb.toString())
                    .build();

        } catch (Exception e) {
            log.warn("Ошибка {}: {}", url, e.getMessage());
            return null;
        } finally {
            if (detailPage != null) detailPage.close();
        }
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ ====================

    private List<String> collectListingLinks(Page page) {
        List<String> detailUrls = new ArrayList<>();
        List<ElementHandle> allLinks = page.querySelectorAll("a[href*='/sell_spare_parts/']");
        for (ElementHandle link : allLinks) {
            String href = link.getAttribute("href");
            if (href != null && href.contains("sell_spare_parts") && href.length() > 30
                    && (href.contains("-g") || href.contains(".html"))) {
                String full = href.startsWith("http") ? href : "https://baza.drom.ru" + href;
                if (!detailUrls.contains(full)) detailUrls.add(full);
            }
        }
        return detailUrls;
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

    private String getTextByAttr(Page page, String attr, String value) {
        try {
            ElementHandle el = page.querySelector("[" + attr + "='" + value + "']");
            return el != null ? el.innerText().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private BrowserContext newContext() {
        return browser.newContext(new Browser.NewContextOptions()
                .setUserAgent(randomUserAgent())
                .setViewportSize(1920, 1080)
                .setLocale("ru-RU"));
    }

    private Page newPage(BrowserContext ctx) {
        Page page = ctx.newPage();
        page.addInitScript("""
                Object.defineProperty(navigator, 'webdriver', { get: () => false });
                delete navigator.__proto__.webdriver;
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
