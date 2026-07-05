package ru.retail.service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;
import ru.retail.service.dto.AvailabilityResult;
import ru.retail.service.service.CatalogLoader;
import ru.retail.service.service.PriceAnalyzer;
import ru.retail.service.service.TunnelService;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * REST/SSE-фасад над анализом наличия (без ИИ). Все эндпоинты работают по OEM и возвращают
 * {@link AvailabilityResult} (счётчики конкурентов Барнаул/Сибирь + цвет + ссылка на наше объявление).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PriceAggregatorController {

    private final PriceAnalyzer priceAnalyzer;
    private final ObjectMapper objectMapper;
    private final TunnelService tunnelService;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "web-analysis");
        t.setDaemon(true);
        return t;
    });

    private final ScheduledExecutorService keepAliveScheduler = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "sse-keepalive");
        t.setDaemon(true);
        return t;
    });

    record JobResult(String status, Object result, String error, long createdAt) {}
    private final ConcurrentHashMap<String, JobResult> jobs = new ConcurrentHashMap<>();

    {
        keepAliveScheduler.scheduleAtFixedRate(() -> {
            long cutoff = System.currentTimeMillis() - 30 * 60 * 1000L;
            jobs.entrySet().removeIf(e -> e.getValue().createdAt() < cutoff);
        }, 10, 10, TimeUnit.MINUTES);
    }

    // ── Информация о сервере ────────────────────────────────

    @GetMapping("/info")
    public Map<String, String> info() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("localUrl",  tunnelService.getLocalUrl() != null ? tunnelService.getLocalUrl() : "");
        map.put("publicUrl", tunnelService.getPublicUrl() != null ? tunnelService.getPublicUrl() : "");
        return map;
    }

    // ── Анализ одной позиции по OEM (SSE) ──────────────────

    @PostMapping(value = "/catalog-item/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter catalogItemStream(
            @RequestParam String oem,
            @RequestParam(defaultValue = "0") BigDecimal catalogPrice,
            @RequestParam(defaultValue = "YARD86") String company,
            HttpServletResponse response) {

        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean done = new AtomicBoolean(false);
        emitter.onCompletion(() -> done.set(true));
        emitter.onTimeout(() -> done.set(true));
        emitter.onError(e -> done.set(true));

        ScheduledFuture<?> ping = keepAliveScheduler.scheduleAtFixedRate(() -> {
            if (done.get()) return;
            send(emitter, "ping", "");
        }, 25, 25, TimeUnit.SECONDS);

        executor.submit(() -> {
            try {
                priceAnalyzer.resetBreakers();
                AvailabilityResult result = priceAnalyzer.analyzeByOem(oem, catalogPrice, company);
                send(emitter, "result", objectMapper.writeValueAsString(result));
            } catch (Exception e) {
                send(emitter, "error", e.getMessage() != null ? e.getMessage() : "Ошибка анализа");
            } finally {
                ping.cancel(false);
                emitter.complete();
            }
        });
        return emitter;
    }

    // ── Polling: отправить задание и получить результат ────────

    @PostMapping("/catalog-item/submit")
    public Map<String, String> submitCatalogItem(
            @RequestParam String oem,
            @RequestParam(defaultValue = "0") BigDecimal catalogPrice,
            @RequestParam(defaultValue = "YARD86") String company) {
        String jobId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jobs.put(jobId, new JobResult("pending", null, null, now));
        executor.submit(() -> {
            jobs.put(jobId, new JobResult("running", null, null, now));
            try {
                AvailabilityResult result = priceAnalyzer.analyzeByOem(oem, catalogPrice, company);
                jobs.put(jobId, new JobResult("done", result, null, now));
            } catch (Exception e) {
                jobs.put(jobId, new JobResult("error", null,
                        e.getMessage() != null ? e.getMessage() : "Ошибка анализа", now));
            }
        });
        return Map.of("jobId", jobId);
    }

    @GetMapping("/job/{jobId}")
    public Map<String, Object> getJobResult(@PathVariable String jobId) {
        JobResult jr = jobs.get(jobId);
        if (jr == null) return Map.of("status", "notFound");
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", jr.status());
        if (jr.result() != null) resp.put("result", jr.result());
        if (jr.error()  != null) resp.put("error",  jr.error());
        return resp;
    }

    // ── Пакетный анализ по CSV (SSE) ───────────────────────

    @PostMapping(value = "/batch/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter batchStream(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "YARD86") String company,
            @RequestParam(defaultValue = "0") int limit,
            HttpServletResponse response) throws IOException {

        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        byte[] csvBytes = file.getBytes();
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean stopped = new AtomicBoolean(false);
        emitter.onCompletion(() -> stopped.set(true));
        emitter.onTimeout(() -> stopped.set(true));
        emitter.onError(e -> stopped.set(true));

        ScheduledFuture<?> ping = keepAliveScheduler.scheduleAtFixedRate(() -> {
            if (stopped.get()) return;
            send(emitter, "ping", "");
        }, 25, 25, TimeUnit.SECONDS);

        executor.submit(() -> {
            try {
                // Каталог: только суппорты старше 6 мес по «Создан». Дедуп по OEM (один номер — один прогон).
                List<CatalogLoader.CatalogItem> all = CatalogLoader.loadOlderThan6Months(csvBytes);
                List<CatalogLoader.CatalogItem> items = new ArrayList<>();
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (CatalogLoader.CatalogItem it : all) {
                    String key = it.oem().replaceAll("[^A-Za-z0-9]", "").toUpperCase();
                    if (key.isBlank() || seen.add(key)) items.add(it);
                }
                int total = (limit > 0 && limit < items.size()) ? limit : items.size();
                send(emitter, "total", String.valueOf(total));

                priceAnalyzer.resetBreakers();

                int processed = 0;
                for (int i = 0; i < items.size() && processed < total; i++) {
                    if (stopped.get()) break;
                    if (priceAnalyzer.isProxyDown()) {
                        send(emitter, "error", "Прокси недоступен: батч остановлен. Смените IP/прокси и запустите заново.");
                        break;
                    }
                    if (priceAnalyzer.isCaptchaBlocked()) {
                        send(emitter, "error", "drom показывает нерешаемую капчу (IP заблокирован). Смените IP/прокси и запустите заново.");
                        break;
                    }
                    CatalogLoader.CatalogItem it = items.get(i);

                    Map<String, Object> progress = new LinkedHashMap<>();
                    progress.put("index", processed);
                    progress.put("oem", it.oem());
                    progress.put("partName", it.name());
                    progress.put("brand", it.brand());
                    progress.put("model", it.model());
                    progress.put("price", it.price() == null ? "" : it.price().toPlainString());
                    progress.put("status", "analyzing");
                    send(emitter, "progress", objectMapper.writeValueAsString(progress));

                    try {
                        AvailabilityResult result = priceAnalyzer.analyzeByOem(it.oem(), it.price(), company);
                        progress.put("result", result);
                        progress.put("status", "done");
                    } catch (Exception e) {
                        progress.put("status", "error");
                        progress.put("error", e.getMessage());
                    }
                    send(emitter, "row", objectMapper.writeValueAsString(progress));
                    processed++;
                }
                send(emitter, "done", stopped.get() ? "stopped" : "ok");
            } catch (Exception e) {
                send(emitter, "error", e.getMessage() != null ? e.getMessage() : "Ошибка");
            } finally {
                ping.cancel(false);
                emitter.complete();
            }
        });
        return emitter;
    }

    // ── Утилиты ────────────────────────────────────────────

    private void send(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            log.warn("SSE send FAILED event='{}': {}", event, e.getMessage());
        }
    }
}
