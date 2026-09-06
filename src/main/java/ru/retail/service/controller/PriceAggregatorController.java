package ru.retail.service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;
import ru.retail.service.config.AnalysisProfiles;
import ru.retail.service.dto.AvailabilityResult;
import ru.retail.service.service.BazonClient;
import ru.retail.service.service.CatalogLoader;
import ru.retail.service.service.PriceAnalyzer;
import ru.retail.service.service.TunnelService;
import ru.retail.service.ui.ExcelReport;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
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
    private final AnalysisProfiles analysisProfiles;
    private final BazonClient bazonClient;

    /** Сколько страниц выдачи листать в режиме «точный подсчёт». 1 страница = 50 объявлений. */
    @org.springframework.beans.factory.annotation.Value("${drom.deep-max-pages:5}")
    private int deepMaxPages;

    /** Глубина скана: по умолчанию одна страница (прежнее поведение), точный подсчёт — по запросу клиента. */
    private int pagesFor(boolean deep) { return deep ? Math.max(1, deepMaxPages) : 1; }

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

    private static final java.time.format.DateTimeFormatter DATE =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");

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

    /** Список имён профилей анализа (типов деталей) для выпадающего списка в UI. */
    @GetMapping("/profiles")
    public List<String> profiles() {
        return analysisProfiles.names();
    }

    // ── Предпросмотр каталога из файла (как в десктоп-UI) ──

    /**
     * Разбирает файл каталога и отдаёт список деталей выбранного типа старше 6 мес (с дедупом по OEM) —
     * без анализа. Позволяет вебу показать таблицу сразу после выбора файла, как это делает
     * {@code MainWindow.populateCatalog}. Порядок совпадает с порядком прогона в {@code /batch/stream},
     * поэтому индекс строки здесь = {@code index} в событиях SSE.
     */
    @PostMapping("/catalog/preview")
    public ResponseEntity<Map<String, Object>> catalogPreview(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String profile) throws IOException {
        byte[] bytes = file.getBytes();
        synchronized (batchLock) {
            if (batchRunning()) return conflict();
            List<CatalogLoader.CatalogItem> items = loadCatalog(bytes, analysisProfiles.byName(profile));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (CatalogLoader.CatalogItem it : items) rows.add(itemFields(it));
            // Каталог кладём в состояние прогона — перезагрузка страницы покажет ту же таблицу
            batchRun = new BatchRun(rows, "loaded");
            return ResponseEntity.ok(state(batchRun, 0));
        }
    }

    // ── Анализ одной позиции по OEM (SSE) ──────────────────

    @PostMapping(value = "/catalog-item/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter catalogItemStream(
            @RequestParam String oem,
            @RequestParam(defaultValue = "0") BigDecimal catalogPrice,
            @RequestParam(defaultValue = "YARD86") String company,
            @RequestParam(required = false) String profile,
            @RequestParam(defaultValue = "false") boolean deep,
            HttpServletResponse response) {
        List<String> stops = stopWords(profile);

        response.setHeader("X-Accel-Buffering", "no");
        // Парсер один на всех: одиночная проверка во время батча просто встала бы в очередь на весь прогон
        if (batchRunning()) {
            SseEmitter busy = new SseEmitter(0L);
            send(busy, "error", "Идёт пакетный анализ — дождитесь его окончания.");
            busy.complete();
            return busy;
        }
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
                AvailabilityResult result = priceAnalyzer.analyzeByOem(oem, catalogPrice, company, stops, pagesFor(deep));
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
            @RequestParam(defaultValue = "YARD86") String company,
            @RequestParam(required = false) String profile,
            @RequestParam(defaultValue = "false") boolean deep) {
        List<String> stops = stopWords(profile);
        if (batchRunning()) return Map.of("error", "Идёт пакетный анализ — дождитесь его окончания.");
        String jobId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jobs.put(jobId, new JobResult("pending", null, null, now));
        executor.submit(() -> {
            jobs.put(jobId, new JobResult("running", null, null, now));
            try {
                AvailabilityResult result = priceAnalyzer.analyzeByOem(oem, catalogPrice, company, stops, pagesFor(deep));
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

    // ── Пакетный анализ по файлу (прогон живёт на сервере) ──

    /**
     * Состояние пакетного прогона. Живёт на сервере до следующего запуска, поэтому перезагрузка
     * страницы (или второй браузер) видит ту же таблицу и тот же прогресс, а не пустой экран.
     */
    static class BatchRun {
        final String id = UUID.randomUUID().toString();
        final List<Map<String, Object>> items;                              // каталог = строки таблицы
        final Map<Integer, Map<String, Object>> rows = new ConcurrentHashMap<>();  // результаты по индексу строки
        final AtomicBoolean stop = new AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        volatile String status;      // loaded | running | done | stopped | error
        volatile String error;
        volatile int total;          // сколько позиций прогоняем (с учётом лимита)
        volatile int lanes = 1;      // дорожек пула (= мобильных IP) в прогоне
        volatile String profile = "";// тип детали прогона (идёт в отчёт отдельной колонкой)
        volatile int maxPages = 1;   // глубина скана: 1 — как раньше, больше — точный подсчёт

        BatchRun(List<Map<String, Object>> items, String status) {
            this.items = items;
            this.status = status;
            this.total = items.size();
        }
    }

    private final Object batchLock = new Object();
    private volatile BatchRun batchRun;

    private boolean batchRunning() {
        BatchRun run = batchRun;
        return run != null && "running".equals(run.status);
    }

    /** Запуск пакетного анализа. Пока прогон идёт — повторный запуск отбивается (409). */
    @PostMapping("/batch/start")
    public ResponseEntity<Map<String, Object>> batchStart(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "YARD86") String company,
            @RequestParam(defaultValue = "0") int limit,
            @RequestParam(required = false) String profile,
            @RequestParam(defaultValue = "false") boolean deep) throws IOException {
        AnalysisProfiles.Profile pr = analysisProfiles.byName(profile);
        List<String> stops = pr == null ? List.of() : pr.getStopWords();
        byte[] bytes = file.getBytes();

        synchronized (batchLock) {
            if (batchRunning()) return conflict();

            // Каталог: только целевой тип (профиль) старше 6 мес по «Создан». Дедуп по OEM (один номер — один прогон).
            List<CatalogLoader.CatalogItem> items = loadCatalog(bytes, pr);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (CatalogLoader.CatalogItem it : items) rows.add(itemFields(it));

            BatchRun run = new BatchRun(rows, "running");
            run.profile = pr == null ? "" : pr.getName();
            run.maxPages = pagesFor(deep);
            run.total = (limit > 0 && limit < items.size()) ? limit : items.size();
            batchRun = run;
            executor.submit(() -> runBatch(run, items, company, stops));
            return ResponseEntity.ok(state(run, 0));
        }
    }

    /**
     * Снимок прогона. {@code since} — индекс, с которого отдавать строки результатов
     * (строки до него уже окончательные у клиента). При {@code since<=0} отдаётся и весь каталог.
     */
    @GetMapping("/batch/state")
    public Map<String, Object> batchState(@RequestParam(defaultValue = "0") int since) {
        return state(batchRun, since);
    }

    /**
     * Excel-отчёт по текущему прогону — тот же {@link ExcelReport}, что пишет десктоп
     * (13 колонок, заливка строки по цвету, кликабельная ссылка). Берутся уже прогнанные строки
     * в порядке каталога; недошедшие («Ожидает») не попадают.
     */
    @GetMapping("/batch/report")
    public ResponseEntity<byte[]> batchReport() throws IOException {
        BatchRun run = batchRun;
        if (run == null || run.done.get() == 0) {
            return ResponseEntity.status(409).body("Нет результатов анализа".getBytes(StandardCharsets.UTF_8));
        }

        Path tmp = Files.createTempFile("availability-report_", ".xlsx");
        byte[] bytes;
        try {
            ExcelReport report = new ExcelReport(tmp, run.profile, bazonClient.itemUrlTemplate());
            for (int i = 0; i < run.items.size(); i++) {
                Map<String, Object> row = run.rows.get(i);
                if (row == null || "analyzing".equals(row.get("status"))) continue;   // ещё не прогнали
                Object res = row.get("result");
                report.append(
                        str(row.get("oem")), str(row.get("itemNumber")), str(row.get("partName")),
                        (str(row.get("brand")) + " " + str(row.get("model"))).trim(),
                        price(row.get("price")), str(row.get("created")), str(row.get("priceChanged")),
                        res instanceof AvailabilityResult r ? r : null);
            }
            report.close();
            bytes = Files.readAllBytes(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }

        String name = "availability-report_"
                + java.time.LocalDateTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".xlsx";
        return ResponseEntity.ok()
                .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
                .body(bytes);
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    private static BigDecimal price(Object o) {
        try { return o == null || o.toString().isBlank() ? null : new BigDecimal(o.toString()); }
        catch (NumberFormatException e) { return null; }
    }

    // ── Переоценка −10% в Bazon (как кнопка в десктопе) ─────

    /** План переоценки одной позиции (те же поля, что в десктопном RepricePlan). */
    record RepricePlan(int id, String oem, long oldPrice, long newPrice, String err) {
        boolean applicable() { return err == null && newPrice > 0 && newPrice < oldPrice; }
    }

    private volatile List<RepricePlan> repricePlan;    // посчитанный dry-run, ждёт подтверждения
    private volatile String repriceToken;
    private volatile Path repricePreviewFile;          // последнее превью — для скачивания из браузера
    private volatile Path repriceAppliedFile;          // последний лог применения

    /**
     * ШАГ 1 — dry-run: по строкам с «Переоценка=true» и числовым «Номер товара» считает −10%
     * (округление до 10 ₽) от каталожной цены, пишет {@code reprice-preview_*.csv}. Сеть не трогается.
     * {@code limit} — тестовый режим «первые N» (0 = все), как в десктопном диалоге.
     */
    @PostMapping("/reprice/preview")
    public ResponseEntity<Map<String, Object>> repricePreview(@RequestParam(defaultValue = "0") int limit) {
        BatchRun run = batchRun;
        if (run == null || batchRunning()) return conflict();

        List<RepricePlan> targets = new ArrayList<>();
        for (int i = 0; i < run.items.size(); i++) {
            Map<String, Object> row = run.rows.get(i);
            if (row == null || !(row.get("result") instanceof AvailabilityResult r) || !r.isReprice()) continue;
            String num = str(row.get("itemNumber")).trim();
            if (!num.matches("\\d+")) continue;
            BigDecimal pr = price(row.get("price"));
            long cur = pr == null ? 0 : pr.longValue();
            targets.add(new RepricePlan(Integer.parseInt(num), str(row.get("oem")),
                    cur, roundTo10(Math.round(cur * 0.9)), cur <= 0 ? "нет цены" : null));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        if (targets.isEmpty()) {
            resp.put("candidates", 0);
            resp.put("error", "Нет деталей с «Переоценка=true» и валидным «Номер товара».");
            return ResponseEntity.ok(resp);
        }

        List<RepricePlan> plans = (limit > 0 && limit < targets.size())
                ? new ArrayList<>(targets.subList(0, limit)) : targets;
        Path preview = writeRepricePreview(plans);

        repricePlan = plans;
        repriceToken = UUID.randomUUID().toString();
        repricePreviewFile = preview;

        long willChange = plans.stream().filter(RepricePlan::applicable).count();
        resp.put("token", repriceToken);
        resp.put("candidates", targets.size());
        resp.put("selected", plans.size());
        resp.put("testMode", plans.size() < targets.size());
        resp.put("willChange", willChange);
        resp.put("skipped", plans.size() - willChange);
        resp.put("previewFile", preview.getFileName().toString());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RepricePlan p : plans) rows.add(Map.of("id", p.id(), "oem", p.oem(),
                "oldPrice", p.oldPrice(), "newPrice", p.newPrice(),
                "note", p.applicable() ? "" : (p.err() != null ? p.err() : "нет цены/не ниже")));
        resp.put("rows", rows);
        return ResponseEntity.ok(resp);
    }

    /**
     * ШАГ 2 — применение: пишет цены одним батчем ({@code setProducts}, чанк 100), лог
     * {@code reprice-applied_*.csv}. Необратимо, поэтому только по токену из шага 1 и один раз.
     */
    @PostMapping("/reprice/apply")
    public ResponseEntity<Map<String, Object>> repriceApply(@RequestParam String token) {
        List<RepricePlan> plans = repricePlan;
        if (plans == null || repriceToken == null || !repriceToken.equals(token)) {
            return ResponseEntity.status(409).body(Map.of("error",
                    "План переоценки устарел — посчитайте заново."));
        }
        repricePlan = null;          // одноразово: повторный apply тем же токеном не пройдёт
        repriceToken = null;

        List<BazonClient.PriceUpdate> updates = new ArrayList<>();
        for (RepricePlan p : plans)
            if (p.applicable()) updates.add(new BazonClient.PriceUpdate(p.id(), p.newPrice()));

        Map<Integer, BazonClient.PriceResult> byId = new HashMap<>();
        String applyErr = null;
        try { for (BazonClient.PriceResult r : bazonClient.setPrices(updates)) byId.put(r.id(), r); }
        catch (Exception e) { applyErr = e.getMessage(); }

        Path applied = repriceReportPath("reprice-applied");
        int ok = 0, err = 0;
        try (java.io.PrintWriter w = new java.io.PrintWriter(Files.newBufferedWriter(applied))) {
            w.println("id;OEM;было;стало;статус");
            for (RepricePlan p : plans) {
                if (!p.applicable()) {
                    w.println(p.id() + ";" + p.oem() + ";" + p.oldPrice() + ";" + p.newPrice()
                            + ";ПРОПУЩЕНО " + (p.err() == null ? "" : p.err()));
                    continue;
                }
                BazonClient.PriceResult r = byId.get(p.id());
                if (applyErr != null) {
                    err++; w.println(p.id() + ";" + p.oem() + ";" + p.oldPrice() + ";" + p.newPrice() + ";ОШИБКА " + applyErr);
                } else if (r != null && r.ok() && r.appliedPrice() == p.newPrice()) {
                    ok++;  w.println(p.id() + ";" + p.oem() + ";" + p.oldPrice() + ";" + p.newPrice() + ";OK");
                } else {
                    err++; w.println(p.id() + ";" + p.oem() + ";" + p.oldPrice() + ";" + p.newPrice()
                            + ";ОТКАЗ " + (r == null ? "нет ответа" : (r.error() == null ? "" : r.error())));
                }
            }
        } catch (Exception e) {
            log.warn("Переоценка: не записать отчёт: {}", e.getMessage());
        }

        repriceAppliedFile = applied;

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("changed", ok);
        resp.put("errors", err);
        resp.put("appliedFile", applied.getFileName().toString());
        if (applyErr != null) resp.put("error", applyErr);
        return ResponseEntity.ok(resp);
    }

    /** Скачать превью переоценки (dry-run) на своё устройство. */
    @GetMapping("/reprice/preview.csv")
    public ResponseEntity<byte[]> downloadPreview() throws IOException {
        return csvDownload(repricePreviewFile, "Превью переоценки ещё не считали.");
    }

    /** Скачать лог применённой переоценки на своё устройство. */
    @GetMapping("/reprice/applied.csv")
    public ResponseEntity<byte[]> downloadApplied() throws IOException {
        return csvDownload(repriceAppliedFile, "Переоценка ещё не применялась.");
    }

    /** Отдаёт CSV-файл вложением. BOM добавляем, чтобы Excel не ломал кириллицу при открытии. */
    private ResponseEntity<byte[]> csvDownload(Path file, String absentMsg) throws IOException {
        if (file == null || !Files.exists(file)) {
            return ResponseEntity.status(404).body(absentMsg.getBytes(StandardCharsets.UTF_8));
        }
        byte[] data = Files.readAllBytes(file);
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        boolean hasBom = data.length >= 3 && data[0] == bom[0] && data[1] == bom[1] && data[2] == bom[2];
        byte[] body = data;
        if (!hasBom) {
            body = new byte[bom.length + data.length];
            System.arraycopy(bom, 0, body, 0, bom.length);
            System.arraycopy(data, 0, body, bom.length, data.length);
        }
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"" + file.getFileName() + "\"")
                .body(body);
    }

    /** Округление до ближайших 10 ₽ (half-up: 6345 → 6350) — как в десктопе. */
    private static long roundTo10(long v) { return Math.round(v / 10.0) * 10; }

    private static Path repriceReportPath(String prefix) {
        return Path.of(prefix + "_" + java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".csv").toAbsolutePath();
    }

    private Path writeRepricePreview(List<RepricePlan> plans) {
        Path p = repriceReportPath("reprice-preview");
        try (java.io.PrintWriter w = new java.io.PrintWriter(Files.newBufferedWriter(p))) {
            w.println("id;OEM;было;станет;примечание");
            for (RepricePlan pl : plans) {
                String note = pl.err() != null ? "ОШИБКА " + pl.err()
                        : (pl.applicable() ? "" : "ПРОПУСК (нет цены/не ниже)");
                w.println(pl.id() + ";" + pl.oem() + ";" + pl.oldPrice() + ";" + pl.newPrice() + ";" + note);
            }
        } catch (Exception e) {
            log.warn("Переоценка: не записать превью: {}", e.getMessage());
        }
        return p;
    }

    /** Остановить текущий прогон (аварийный выход, если анализ завис). */
    @PostMapping("/batch/stop")
    public Map<String, Object> batchStop() {
        BatchRun run = batchRun;
        if (run != null) run.stop.set(true);
        return state(run, 0);
    }

    /**
     * Тело прогона: как в десктоп-батче — воркер на каждую дорожку пула (1 дорожка = 1 мобильный IP),
     * общий курсор по каталогу. Результат каждой позиции пишется в состояние, его и опрашивает страница.
     */
    private void runBatch(BatchRun run, List<CatalogLoader.CatalogItem> items,
                          String company, List<String> stops) {
        int lanes = Math.max(1, priceAnalyzer.laneCount());
        run.lanes = lanes;
        try {
            priceAnalyzer.resetBreakers();
            java.util.concurrent.atomic.AtomicInteger cursor = new java.util.concurrent.atomic.AtomicInteger();
            ExecutorService lanePool = Executors.newFixedThreadPool(lanes, r -> {
                Thread t = new Thread(r, "web-lane");
                t.setDaemon(true);
                return t;
            });
            List<Future<?>> workers = new ArrayList<>();

            for (int L = 0; L < lanes; L++) {
                final int laneId = L;
                workers.add(lanePool.submit(() -> {
                    while (!run.stop.get()) {
                        // предохранители у каждой дорожки свои: упавшая не роняет остальные
                        if (priceAnalyzer.isProxyDown(laneId) || priceAnalyzer.isCaptchaBlocked(laneId)) {
                            run.error = "Дорожка L" + laneId + ": прокси недоступен или капча. Смените IP/прокси.";
                            break;
                        }
                        int idx = cursor.getAndIncrement();
                        if (idx >= run.total) break;

                        CatalogLoader.CatalogItem it = items.get(idx);
                        Map<String, Object> row = new LinkedHashMap<>(run.items.get(idx));
                        row.put("index", idx);
                        row.put("lane", "L" + laneId);
                        row.put("status", "analyzing");
                        run.rows.put(idx, row);

                        Map<String, Object> finished = new LinkedHashMap<>(row);
                        try {
                            finished.put("result", priceAnalyzer.analyzeCatalogLane(
                                    it.oem(), it.price(), company, laneId, stops, run.maxPages));
                            finished.put("status", "done");
                        } catch (Exception e) {
                            finished.put("status", "error");
                            finished.put("error", e.getMessage() != null ? e.getMessage() : "Ошибка анализа");
                        }
                        run.rows.put(idx, finished);
                        run.done.incrementAndGet();
                    }
                }));
            }
            lanePool.shutdown();
            for (Future<?> f : workers) { try { f.get(); } catch (Exception ignored) {} }

            if (run.stop.get())                                    run.status = "stopped";
            else if (run.done.get() < run.total && run.error != null) run.status = "error";
            else                                                   run.status = "done";
        } catch (Exception e) {
            run.error = e.getMessage() != null ? e.getMessage() : "Ошибка";
            run.status = "error";
        }
    }

    private ResponseEntity<Map<String, Object>> conflict() {
        Map<String, Object> body = state(batchRun, 0);
        body.put("error", "Анализ уже идёт — дождитесь окончания или нажмите «Стоп».");
        return ResponseEntity.status(409).body(body);
    }

    /** Состояние прогона для страницы. */
    private Map<String, Object> state(BatchRun run, int since) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (run == null) {
            m.put("status", "idle");
            m.put("total", 0);
            m.put("done", 0);
            m.put("items", List.of());
            m.put("rows", List.of());
            return m;
        }
        m.put("runId", run.id);
        m.put("status", run.status);
        m.put("total", run.total);
        m.put("done", run.done.get());
        m.put("lanes", run.lanes);
        m.put("deep", run.maxPages > 1);
        m.put("catalogSize", run.items.size());
        if (run.error != null) m.put("error", run.error);
        if (since <= 0) m.put("items", run.items);

        // Дорожки завершают строки вразнобой, поэтому «всё уже окончательно» — только до первой
        // незаконченной строки. Этот водяной знак страница шлёт обратно как since.
        int syncFrom = 0;
        while (syncFrom < run.items.size()) {
            Map<String, Object> r = run.rows.get(syncFrom);
            if (r == null || "analyzing".equals(r.get("status"))) break;
            syncFrom++;
        }
        m.put("syncFrom", syncFrom);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = Math.max(0, since); i < run.items.size(); i++) {
            Map<String, Object> r = run.rows.get(i);
            if (r != null) rows.add(r);
        }
        m.put("rows", rows);
        return m;
    }

    // ── Утилиты ────────────────────────────────────────────

    /** Каталог из файла: только тип профиля, старше 6 мес, дедуп по OEM (один номер — один прогон). */
    private List<CatalogLoader.CatalogItem> loadCatalog(byte[] bytes, AnalysisProfiles.Profile pr) throws IOException {
        String keyword = pr == null ? "" : pr.getKeyword();
        List<String> stops = pr == null ? List.of() : pr.getStopWords();
        // bazonClient.login() — позиции, которым цену менял сам парсер >1 мес назад, снова идут в переоценку
        List<CatalogLoader.CatalogItem> all = CatalogLoader.loadOlderThan6Months(bytes, keyword, stops, bazonClient.login());
        List<CatalogLoader.CatalogItem> items = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (CatalogLoader.CatalogItem it : all) {
            String key = it.oem().replaceAll("[^A-Za-z0-9]", "").toUpperCase();
            if (key.isBlank() || seen.add(key)) items.add(it);
        }
        return items;
    }

    /** Поля позиции каталога для веба — те же колонки, что в таблице десктопа. */
    private Map<String, Object> itemFields(CatalogLoader.CatalogItem it) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemNumber", it.itemNumber());
        m.put("oem", it.oem());
        m.put("partName", it.name());
        m.put("brand", it.brand());
        m.put("model", it.model());
        m.put("price", it.price() == null ? "" : it.price().toPlainString());
        m.put("created", it.created() == null ? "" : it.created().format(DATE));
        m.put("priceChanged", it.priceChanged() == null ? "" : it.priceChanged().format(DATE));
        return m;
    }

    /** Стоп-слова профиля по имени (пустое/неизвестное имя → дефолтный профиль). */
    private List<String> stopWords(String profileName) {
        AnalysisProfiles.Profile p = analysisProfiles.byName(profileName);
        return p == null ? List.of() : p.getStopWords();
    }

    private void send(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            log.warn("SSE send FAILED event='{}': {}", event, e.getMessage());
        }
    }
}
