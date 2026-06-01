package ru.retail.service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ru.retail.service.dto.AggregationResult;
import ru.retail.service.service.PriceAnalyzer;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PriceAggregatorController {

    private final PriceAnalyzer priceAnalyzer;
    private final ObjectMapper objectMapper;

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

    // ── Одиночный анализ (SSE) ──────────────────────────────

    @PostMapping(value = "/analyze/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeStream(
            @RequestParam String oem,
            @RequestParam String region,
            @RequestParam String myListingUrl,
            @RequestParam(required = false, defaultValue = "0") BigDecimal myPrice) {

        SseEmitter emitter = new SseEmitter(0L);   // 0 = без таймаута на уровне Tomcat
        AtomicBoolean done = new AtomicBoolean(false);
        emitter.onCompletion(() -> done.set(true));
        emitter.onTimeout(() -> done.set(true));
        emitter.onError(e -> done.set(true));

        // keep-alive каждые 25 с — не даём соединению упасть по idle-timeout
        ScheduledFuture<?> ping = keepAliveScheduler.scheduleAtFixedRate(() -> {
            if (done.get()) return;
            send(emitter, "ping", "");
        }, 25, 25, TimeUnit.SECONDS);

        executor.submit(() -> {
            try {
                send(emitter, "status", "Анализ идёт...");
                AggregationResult result = priceAnalyzer.analyze(oem, region, myListingUrl, myPrice);
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

    // ── Пакетный анализ по CSV (SSE) ───────────────────────

    @PostMapping(value = "/batch/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter batchStream(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "barnaul") String region,
            @RequestParam(defaultValue = "YARD86") String company,
            @RequestParam(defaultValue = "0") int limit) throws IOException {

        byte[] csvBytes = file.getBytes();
        SseEmitter emitter = new SseEmitter(0L);   // 0 = без таймаута на уровне Tomcat
        AtomicBoolean stopped = new AtomicBoolean(false);
        emitter.onCompletion(() -> stopped.set(true));
        emitter.onTimeout(() -> stopped.set(true));
        emitter.onError(e -> stopped.set(true));

        // keep-alive каждые 25 с
        ScheduledFuture<?> ping = keepAliveScheduler.scheduleAtFixedRate(() -> {
            if (stopped.get()) return;
            send(emitter, "ping", "");
        }, 25, 25, TimeUnit.SECONDS);

        executor.submit(() -> {
            try {
                List<String[]> rows = parseCsv(csvBytes);
                int total = (limit > 0 && limit < rows.size()) ? limit : rows.size();
                send(emitter, "total", String.valueOf(total));

                int processed = 0;
                for (int i = 0; i < rows.size() && processed < total; i++) {
                    if (stopped.get()) break;
                    String[] row = rows.get(i);
                    String oem = col(row, 4);
                    if (oem.isBlank()) continue;

                    BigDecimal catalogPrice = parseBd(col(row, 5));
                    Map<String, Object> progress = new LinkedHashMap<>();
                    progress.put("index", processed);
                    progress.put("oem", oem);
                    progress.put("partName", col(row, 3));
                    progress.put("brand", col(row, 8));
                    progress.put("model", col(row, 9));
                    progress.put("catalogPrice", catalogPrice.toPlainString());
                    progress.put("status", "analyzing");
                    send(emitter, "progress", objectMapper.writeValueAsString(progress));

                    try {
                        AggregationResult result = priceAnalyzer.analyzeFromCatalog(oem, catalogPrice, region, company);
                        boolean found = result.getRecommendedPrice() != null
                                && result.getRecommendedPrice().compareTo(BigDecimal.ZERO) > 0;
                        progress.put("result", result);
                        progress.put("status", found ? "done" : "notFound");
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
            log.debug("SSE sent OK: event={} dataLen={}", event, data.length());
        } catch (IOException e) {
            log.warn("SSE send FAILED event='{}': {}", event, e.getMessage());
        }
    }

    private List<String[]> parseCsv(byte[] bytes) throws Exception {
        List<String[]> result = new ArrayList<>();
        String content = new String(bytes, "Windows-1251");
        List<String> row = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        boolean firstRow = true;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ';' && !inQuotes) {
                row.add(sb.toString().trim()); sb.setLength(0);
            } else if (c == '\n' && !inQuotes) {
                row.add(sb.toString().trim()); sb.setLength(0);
                if (!row.stream().allMatch(String::isEmpty)) {
                    if (firstRow) firstRow = false;
                    else result.add(row.toArray(new String[0]));
                }
                row = new ArrayList<>();
            } else if (c != '\r') {
                sb.append(c);
            }
        }
        if (!sb.isEmpty()) row.add(sb.toString().trim());
        if (!row.isEmpty() && !row.stream().allMatch(String::isEmpty) && !firstRow)
            result.add(row.toArray(new String[0]));
        return result;
    }

    private String col(String[] row, int i) {
        if (row == null || i >= row.length || row[i] == null) return "";
        return row[i].trim();
    }

    private BigDecimal parseBd(String s) {
        try { return new BigDecimal(s.replaceAll("[^\\d.]", "")); }
        catch (Exception e) { return BigDecimal.ZERO; }
    }
}
