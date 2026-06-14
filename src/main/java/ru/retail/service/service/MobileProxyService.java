package ru.retail.service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Доступ к API mobileproxy.space (mproxy.site).
 * Главная задача — по account-токену получить актуальные change-IP ссылки по каждому порту,
 * чтобы дорожки пула могли реактивно крутить IP при капче без ручного ведения ключей.
 *
 * Документация: GET https://mobileproxy.space/api.html?command=get_my_proxy
 *               Authorization: Bearer &lt;token&gt;
 * В ответе по каждому прокси есть proxy_independent_port и готовый proxy_change_ip_url.
 */
@Slf4j
@Service
public class MobileProxyService {

    @Value("${mobileproxy.api.url:https://mobileproxy.space/api.html}")
    private String apiUrl;

    @Value("${mobileproxy.api.token:}")
    private String apiToken;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public boolean isEnabled() {
        return apiToken != null && !apiToken.isBlank();
    }

    /**
     * Возвращает карту «порт → change-IP ссылка» по всем активным прокси аккаунта.
     * При ошибке/выключенном токене — пустая карта (ротация просто не активируется).
     */
    public Map<Integer, String> changeIpUrlsByPort() {
        Map<Integer, String> result = new HashMap<>();
        if (!isEnabled()) {
            log.info("mobileproxy: api-token не задан — авто-дискавери change-IP ссылок выключено");
            return result;
        }
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(apiUrl + "?command=get_my_proxy"))
                            .header("Authorization", "Bearer " + apiToken)
                            .timeout(Duration.ofSeconds(30))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            String body = resp.body();
            if (resp.statusCode() != 200 || body == null || body.isBlank() || body.contains("\"status\":\"err\"")) {
                log.warn("mobileproxy: get_my_proxy вернул не-OK (HTTP {}): {}", resp.statusCode(),
                        body == null ? "" : body.substring(0, Math.min(200, body.length())));
                return result;
            }

            // Парсим без зависимостей: вытаскиваем пары (proxy_independent_port, proxy_change_ip_url) из JSON-массива.
            Matcher portM = Pattern.compile("\"proxy_independent_port\"\\s*:\\s*\"?(\\d+)\"?").matcher(body);
            Matcher urlM  = Pattern.compile("\"proxy_change_ip_url\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
            while (portM.find() && urlM.find()) {
                int port = Integer.parseInt(portM.group(1));
                String url = urlM.group(1).replace("\\/", "/");   // JSON-экранирование слэшей
                result.put(port, url);
            }
            log.info("mobileproxy: получено change-IP ссылок: {} (порты: {})", result.size(), result.keySet());
        } catch (Exception e) {
            log.warn("mobileproxy: не удалось получить список прокси: {}", e.getMessage());
        }
        return result;
    }
}
