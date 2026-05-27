package ru.retail.service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.retail.service.dto.DromListingItem;
import ru.retail.service.dto.PartPrice;
import ru.retail.service.dto.RunActorRequest;
import ru.retail.service.dto.RunResponse;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ApifyDromService {

    private static final int POLL_INTERVAL_MS  = 5_000;
    private static final int MAX_POLL_ATTEMPTS = 72;  // 6 минут максимум

    @Value("${apify.api.token:}")
    private String token;

    @Value("${apify.api.base-url:https://api.apify.com}")
    private String baseUrl;

    @Value("${apify.actor.drom-scraper-id:}")
    private String actorId;

    @Value("${apify.enabled:false}")
    private boolean enabled;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .build();
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public boolean isEnabled() {
        return enabled
                && token   != null && !token.isBlank()
                && actorId != null && !actorId.isBlank();
    }

    /**
     * Парсит конкурентов через Apify.
     * Актор должен принимать startUrls и возвращать массив элементов,
     * совместимых с DromListingItem (поля: url, title, price, dealer_name,
     * location, description, images, attributes.oem).
     */
    public List<PartPrice> parseParts(String oemNumber, String region, int limit) {
        if (!isEnabled()) return List.of();

        String searchUrl = "https://baza.drom.ru/" + region
                + "/sell_spare_parts/?query=" + oemNumber.replaceAll("\\s+", "");
        log.info("Apify: парсинг [{}] {}", region, searchUrl);

        try {
            String runId = startRun(searchUrl, limit);
            if (runId == null) return List.of();
            log.info("Apify: запущен runId={}", runId);

            String datasetId = waitForCompletion(runId);
            if (datasetId == null) return List.of();

            List<DromListingItem> items = fetchDataset(datasetId, limit);
            log.info("Apify: получено {} элементов", items.size());

            return items.stream()
                    .filter(i -> i.getEffectivePrice() != null
                            && i.getEffectivePrice().compareTo(BigDecimal.ZERO) > 0)
                    .map(i -> PartPrice.builder()
                            .url(i.getUrl())
                            .price(i.getEffectivePrice())
                            .title(i.getTitle())
                            .dealer(i.getDealerName())
                            .location(i.getLocation() != null ? i.getLocation() : region)
                            .description(i.getDescription() != null ? i.getDescription() : "")
                            .photoUrls(i.getImages() != null ? i.getImages() : List.of())
                            .oem(i.getAttributes() != null ? i.getAttributes().get("oem") : null)
                            .publishedDate("")
                            .build())
                    .sorted(Comparator.comparing(PartPrice::getPrice))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Apify parseParts ошибка: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Запуск актора ──────────────────────────────────────────────────────

    private String startRun(String searchUrl, int limit) throws Exception {
        RunActorRequest input = RunActorRequest.builder()
                .startUrls(List.of(Map.of("url", searchUrl)))
                .maxItems(limit)
                .proxyConfiguration(RunActorRequest.ProxyConfiguration.builder()
                        .useApifyProxy(true)
                        .apifyProxyGroups(List.of("RESIDENTIAL"))
                        .build())
                .build();

        String body = mapper.writeValueAsString(input);
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/v2/acts/" + actorId + "/runs?token=" + token))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 201) {
            log.error("Apify startRun failed: HTTP {} — {}", resp.statusCode(), resp.body());
            return null;
        }
        RunResponse run = mapper.readValue(resp.body(), RunResponse.class);
        return run.getData().getId();
    }

    // ── Ожидание завершения ─────────────────────────────────────────────────

    private String waitForCompletion(String runId) throws Exception {
        for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
            Thread.sleep(POLL_INTERVAL_MS);

            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/v2/actor-runs/" + runId + "?token=" + token))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            RunResponse run = mapper.readValue(resp.body(), RunResponse.class);
            String status = run.getData().getStatus();
            log.debug("Apify run {} → {}", runId, status);

            switch (status) {
                case "SUCCEEDED"  -> { return run.getData().getDefaultDatasetId(); }
                case "FAILED", "ABORTED", "TIMED-OUT" -> {
                    log.error("Apify run {} завершился: {}", runId, status);
                    return null;
                }
                // READY, RUNNING — продолжаем ждать
            }
        }
        log.error("Apify: таймаут ожидания runId={}", runId);
        return null;
    }

    // ── Скачивание датасета ─────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<DromListingItem> fetchDataset(String datasetId, int limit) throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/v2/datasets/" + datasetId
                                + "/items?token=" + token + "&format=json&limit=" + limit))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        String body = resp.body().trim();
        // Apify возвращает либо [массив], либо {"items":[...]}
        if (body.startsWith("[")) {
            return Arrays.asList(mapper.readValue(body, DromListingItem[].class));
        }
        Map<String, Object> wrapper = mapper.readValue(body, Map.class);
        Object itemsObj = wrapper.get("items");
        if (itemsObj == null) return List.of();
        return Arrays.asList(mapper.readValue(mapper.writeValueAsString(itemsObj), DromListingItem[].class));
    }
}
