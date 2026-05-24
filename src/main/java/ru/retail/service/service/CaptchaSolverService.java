package ru.retail.service.service;

import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
public class CaptchaSolverService {

    private static final String IN_URL  = "https://rucaptcha.com/in.php";
    private static final String RES_URL = "https://rucaptcha.com/res.php";

    @Value("${twocaptcha.api-key:}")
    private String apiKey;

    @Value("${twocaptcha.enabled:false}")
    private boolean enabled;

    private final HttpClient http = HttpClient.newHttpClient();

    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /**
     * Решает reCAPTCHA на странице через сервис 2captcha.
     * Возвращает true если капча решена и токен внедрён в страницу.
     */
    public boolean solve(Page page) {
        if (!isEnabled()) return false;

        String pageUrl = page.url();
        log.info("2captcha: решаем капчу на {}", pageUrl);

        String siteKey = findSiteKey(page);
        if (siteKey == null) {
            log.warn("2captcha: sitekey не найден на странице, автоматическое решение невозможно");
            return false;
        }
        log.info("2captcha: sitekey найден — {}", siteKey);

        try {
            String taskId = submitTask(siteKey, pageUrl);
            if (taskId == null) return false;
            log.info("2captcha: задача создана #{}", taskId);

            String token = waitForResult(taskId, 180);
            if (token == null) return false;

            injectToken(page, token);
            log.info("2captcha: токен внедрён, ждём загрузки страницы");
            Thread.sleep(3000);
            return true;

        } catch (Exception e) {
            log.error("2captcha ошибка: {}", e.getMessage());
            return false;
        }
    }

    private String findSiteKey(Page page) {
        try {
            Object key = page.evaluate("""
                    document.querySelector('.g-recaptcha')?.dataset?.sitekey
                    || document.querySelector('[data-sitekey]')?.dataset?.sitekey
                    || null
                    """);
            return key instanceof String s && !s.isBlank() ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String submitTask(String siteKey, String pageUrl) throws Exception {
        String body = "key=" + apiKey
                + "&method=userrecaptcha"
                + "&googlekey=" + URLEncoder.encode(siteKey, StandardCharsets.UTF_8)
                + "&pageurl=" + URLEncoder.encode(pageUrl, StandardCharsets.UTF_8)
                + "&json=1";

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(IN_URL))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        String resp = response.body();
        if (resp.contains("\"status\":1")) {
            return extractField(resp, "request");
        }
        log.error("2captcha: ошибка отправки задачи — {}", resp);
        return null;
    }

    private String waitForResult(String taskId, int maxSeconds) throws Exception {
        String url = RES_URL + "?key=" + apiKey + "&action=get&id=" + taskId + "&json=1";
        Thread.sleep(15_000);
        int elapsed = 15;

        while (elapsed < maxSeconds) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            String body = response.body();

            if (body.contains("\"status\":1")) {
                return extractField(body, "request");
            }
            if (!body.contains("CAPCHA_NOT_READY")) {
                log.error("2captcha: неожиданный ответ — {}", body);
                return null;
            }
            Thread.sleep(5_000);
            elapsed += 5;
        }
        log.warn("2captcha: таймаут {}с — задача #{} не решена", maxSeconds, taskId);
        return null;
    }

    private void injectToken(Page page, String token) {
        page.evaluate("""
                (token) => {
                    const el = document.getElementById('g-recaptcha-response');
                    if (el) { el.innerHTML = token; el.value = token; }
                    if (typeof ___grecaptcha_cfg !== 'undefined') {
                        for (const client of Object.values(___grecaptcha_cfg.clients || {})) {
                            for (const v of Object.values(client)) {
                                if (v && typeof v.callback === 'function') {
                                    try { v.callback(token); } catch (e) {}
                                }
                            }
                        }
                    }
                }
                """, token);
    }

    private String extractField(String json, String field) {
        String search = "\"" + field + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        return end < 0 ? null : json.substring(start, end);
    }
}
