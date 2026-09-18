package ru.retail.service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.retail.service.dto.AvailabilityResult;
import ru.retail.service.dto.AvailabilityResult.Color;
import ru.retail.service.dto.PartPrice;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Оркестрация анализа наличия/плотности рынка по OEM (без ИИ).
 *
 * Логика (компания YARD86, все счётчики — БЕЗ объявлений нашей компании):
 *   1. Барнаул: считаем конкурентов (тот же OEM, б/у, узлы в сборе) + находим ссылку на наше объявление.
 *   2. Если в Барнауле конкурентов < {@link #SIBERIA_TRIGGER} — сканируем всю Сибирь по регионам целиком
 *      ({@link #SIBERIA_REGIONS}) и суммируем конкурентов строго по регионам (altaiskii-krai уже покрывает Барнаул).
 *   3. Цвет строки (приоритет): красный (Барнаул<4 И Сибирь<10) > фиолетовый (Сибирь<10) >
 *      жёлтый (Барнаул<4) > без заливки.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceAnalyzer {

    /** Домашний город (порог <4). */
    private static final String HOME_CITY = "barnaul";

    /** «Сибирь» — поиск ПО РЕГИОНАМ ЦЕЛИКОМ (drom по geo-slug региона показывает все его города).
     *  Алтайский край включает Барнаул, поэтому S считаем ТОЛЬКО по регионам (без отдельного +Барнаул). */
    private static final List<String> SIBERIA_REGIONS = List.of(
            "altai-resp",        // Республика Алтай
            "altaiskii-krai",    // Алтайский край (вкл. Барнаул)
            "irkutskaya-obl",    // Иркутская область
            "kemerovskaya-obl",  // Кемеровская область
            "krasnoyarskii-krai",// Красноярский край
            "novosibirskaya-obl",// Новосибирская область
            "omskaya-obl",       // Омская область
            "khakasiya-resp",    // Республика Хакасия
            "tomskaya-obl",      // Томская область
            "tyva-resp");        // Республика Тыва

    private static final int BARNAUL_MIN     = 4;    // < 4 конкурентов в Барнауле → жёлтый/красный
    private static final int SIBERIA_TRIGGER = 10;   // < 10 в Барнауле → идём в Сибирь
    private static final int SIBERIA_MIN     = 10;   // < 10 по всей Сибири → фиолетовый/красный

    // Сколько раз гонять один OEM, если во время прогона случилась капча со сменой IP (повтор на свежем IP).
    private static final int MAX_OEM_ATTEMPTS = 3;

    /**
     * Настройки скана, которые клиент выбирает сам. Значения по умолчанию повторяют прежнее
     * поведение: одна страница выдачи и только б/у.
     *
     * @param maxPages   сколько страниц выдачи листать (1 — как раньше, 50 объявлений)
     * @param includeNew считать конкурентами и НОВЫЕ детали, а не только б/у. Заказчик смотрит
     *                   на рынок целиком: если новых предложений много (пример 78228 — рынок 85),
     *                   поднимать цену бессмысленно, даже когда б/у почти нет.
     */
    public record ScanOptions(int maxPages, boolean includeNew, boolean byApplicability) {
        public static final ScanOptions DEFAULT = new ScanOptions(1, false, false);
        public ScanOptions { maxPages = Math.max(1, maxPages); }
        public ScanOptions(int maxPages, boolean includeNew) { this(maxPages, includeNew, false); }
        /** Фильтр «Б/у» на стороне drom: при учёте новых он снимается. */
        boolean usedOnly() { return !includeNew; }
    }

    private final DromParser dromParser;   // одиночная дорожка (веб / одиночный анализ)
    private final DromParserPool pool;     // пул дорожек (IP) для параллельного батча

    // Кэш сканов на батч (ключ OEM+регион, общий на дорожки): дубли OEM не перезапрашивают страницу поиска drom.
    // Кэшируем ТОЛЬКО непустой результат — капчевую пустышку нельзя (иначе ломается повтор-на-свежем-IP).
    private final Map<String, DromParser.RegionScan> scanCache = new ConcurrentHashMap<>();

    // ==================== ПРЕДОХРАНИТЕЛИ / ПУЛ ====================

    public int laneCount() { return pool.size(); }
    public boolean isProxyDown(int lane) { return pool.lane(lane).isProxyDown(); }
    public boolean isCaptchaBlocked(int lane) { return pool.lane(lane).isCaptchaBlocked(); }
    public boolean isProxyDown() { return dromParser.isProxyDown(); }
    public boolean isCaptchaBlocked() { return dromParser.isCaptchaBlocked(); }

    public void resetBreakers() {
        dromParser.resetBreakers();
        for (int i = 0; i < pool.size(); i++) pool.lane(i).resetBreakers();
        scanCache.clear();
    }

    // ==================== ПУБЛИЧНЫЕ ВХОДЫ ====================

    /** Веб/одиночный анализ по OEM — одиночная дорожка. stopWords — профиль типа детали. */
    public AvailabilityResult analyzeByOem(String oem, BigDecimal price, String myCompany, List<String> stopWords) {
        return analyzeByOem(oem, price, myCompany, stopWords, ScanOptions.DEFAULT);
    }

    public AvailabilityResult analyzeByOem(String oem, BigDecimal price, String myCompany,
                                           List<String> stopWords, ScanOptions opts) {
        return analyzeWithRetry(oem, price, myCompany, dromParser, stopWords, opts);
    }

    /** Параллельный батч — конкретная дорожка пула (свой IP). */
    public AvailabilityResult analyzeCatalogLane(String oem, BigDecimal price, String myCompany, int lane, List<String> stopWords) {
        return analyzeCatalogLane(oem, price, myCompany, lane, stopWords, ScanOptions.DEFAULT);
    }

    public AvailabilityResult analyzeCatalogLane(String oem, BigDecimal price, String myCompany, int lane,
                                                 List<String> stopWords, ScanOptions opts) {
        return analyzeCatalogLane(oem, null, price, myCompany, lane, stopWords, opts);
    }

    /**
     * Прогон позиции каталога. {@code applicabilityQuery} — поисковая строка «тип детали + марка +
     * модель»; используется вместо OEM, когда включён поиск по применимости. Нужно кузовным деталям:
     * у части марок (пример заказчика — Ford) номер индивидуален, и по OEM рынок выглядит пустым,
     * хотя деталь на модель продаётся.
     */
    public AvailabilityResult analyzeCatalogLane(String oem, String applicabilityQuery, BigDecimal price,
                                                 String myCompany, int lane, List<String> stopWords, ScanOptions opts) {
        boolean byApp = opts.byApplicability() && applicabilityQuery != null && !applicabilityQuery.isBlank();
        String query = byApp ? applicabilityQuery.trim() : oem;
        return analyzeWithRetry(query, oem, price, myCompany, pool.lane(lane), stopWords, opts, !byApp);
    }

    // ==================== ОРКЕСТРАЦИЯ ====================

    /**
     * Прогон OEM с повтором при капче со сменой IP: если за время прогона дорожка сменила IP
     * (была нерешаемая капча), OEM прогоняется заново на свежем IP.
     */
    private AvailabilityResult analyzeWithRetry(String oem, BigDecimal price, String myCompany, DromParser p,
                                                List<String> stopWords, ScanOptions opts) {
        return analyzeWithRetry(oem, oem, price, myCompany, p, stopWords, opts, true);
    }

    /**
     * @param query      что ищем на drom (OEM либо «деталь марка модель»)
     * @param oem        OEM позиции — идёт в результат и в сверку номера
     * @param matchOem   сверять ли OEM конкурента с нашим. При поиске по применимости — нет:
     *                   у конкурентов номера свои, отбор держится на стоп-словах и запросе.
     */
    private AvailabilityResult analyzeWithRetry(String query, String oem, BigDecimal price, String myCompany,
                                                DromParser p, List<String> stopWords, ScanOptions opts, boolean matchOem) {
        AvailabilityResult res = null;
        for (int attempt = 1; attempt <= MAX_OEM_ATTEMPTS; attempt++) {
            long rotBefore = p.rotationCount();
            res = analyzeOnce(query, oem, price, myCompany, p, stopWords, opts, matchOem);
            boolean rotatedDuringRun = p.rotationCount() > rotBefore;
            // Повторяем, если выдача была получена не целиком (капча/сбой) или IP сменился посреди прогона.
            boolean retry = rotatedDuringRun || res.isIncomplete();
            if (!retry || p.isCaptchaBlocked() || attempt >= MAX_OEM_ATTEMPTS) {
                if (retry && attempt > 1)
                    log.info("Запрос {}: завершён после повтора (попыток: {}, неполный: {})", query, attempt, res.isIncomplete());
                return res;
            }
            log.warn("Запрос {}: {} — повтор (попытка {}/{})", query,
                    rotatedDuringRun ? "во время прогона сменился IP из-за капчи" : "drom не отдал выдачу",
                    attempt + 1, MAX_OEM_ATTEMPTS);
        }
        return res;
    }

    private AvailabilityResult analyzeOnce(String query, String oem, BigDecimal price, String myCompany, DromParser p,
                                           List<String> stopWords, ScanOptions opts, boolean matchOem) {
        log.info("=== Наличие: {} | Компания: {} | {}{} ===", query, myCompany,
                opts.includeNew() ? "б/у + новые" : "только б/у",
                matchOem ? "" : " | по применимости");

        // 1. Барнаул (город): конкуренты (used+present) + наше объявление. Порог <4.
        DromParser.RegionScan barnaul = scan(p, query, HOME_CITY, myCompany, opts.usedOnly(), opts.maxPages(), matchOem);
        if (barnaul.failed()) return incomplete(oem, price, HOME_CITY, 0, 0, false);
        int b = countCompetitors(barnaul.competitors(), oem, stopWords, matchOem);
        String myUrl = findMyListing(p, query, oem, barnaul, myCompany, price, opts, matchOem);
        log.info("Барнаул: конкурентов {} (наших объявлений {})", b, barnaul.myCandidates().size());

        // 2. При b<10 — расширяем на всю Сибирь ПО РЕГИОНАМ ЦЕЛИКОМ (все города регионов, вкл. Алтайский край).
        //    S считаем строго по регионам (altaiskii-krai уже покрывает Барнаул), поэтому начинаем с 0.
        int siberiaTotal = 0;
        boolean searched = false;
        if (b < SIBERIA_TRIGGER) {
            searched = true;
            for (String region : SIBERIA_REGIONS) {
                DromParser.RegionScan rs = scan(p, query, region, myCompany, opts.usedOnly(), opts.maxPages(), matchOem);
                // Регион без выдачи дал бы заниженную сумму по Сибири → ложный фиолетовый/красный.
                if (rs.failed()) return incomplete(oem, price, region, b, siberiaTotal, true);
                int c = countCompetitors(rs.competitors(), oem, stopWords, matchOem);
                siberiaTotal += c;
                log.info("Сибирь [{}]: конкурентов {} (сумма {})", region, c, siberiaTotal);
            }
        }

        // 3. Цвет, переоценка и статус.
        Color color = computeColor(b, siberiaTotal, searched);
        boolean reprice = needsRepricing(b, siberiaTotal, searched);
        String status = statusText(color, b, siberiaTotal, searched);

        return AvailabilityResult.builder()
                .oemNumber(oem)
                .price(price)
                .barnaulCount(b)
                .siberiaCount(searched ? siberiaTotal : 0)
                .searchedSiberia(searched)
                .myListingUrl(myUrl)
                .color(color)
                .reprice(reprice)
                .status(status)
                .collectedAt(java.time.Instant.now())
                .build();
    }

    /**
     * Ссылка на наше объявление.
     * <p>По OEM — из той же выдачи Барнаула; если там нашего нет (часть наших не помечена «Б/у», напр.
     * диски — «Контрактная»), доп. скан без used-фильтра (present-only).
     * <p>По применимости выдача «деталь марка модель» содержит ВСЕ наши объявления на эту модель
     * (разные детали, поколения) — ближайшее по цене из них легко оказывается чужой позицией. Поэтому
     * наше объявление ищем по OEM позиции: у нас он указан, и выдача по нему однозначна.
     */
    private String findMyListing(DromParser p, String query, String oem, DromParser.RegionScan barnaul,
                                 String myCompany, BigDecimal price, ScanOptions opts, boolean matchOem) {
        if (!matchOem) {
            if (oem == null || oem.isBlank()) return null;
            return pickUrlByPrice(scan(p, oem, HOME_CITY, myCompany, false, opts.maxPages(), true).myCandidates(), price);
        }
        String myUrl = pickUrlByPrice(barnaul.myCandidates(), price);
        if (myUrl == null) {
            myUrl = pickUrlByPrice(scan(p, query, HOME_CITY, myCompany, false, opts.maxPages(), true).myCandidates(), price);
        }
        return myUrl;
    }

    /** Результат, когда drom не отдал выдачу: без цвета и БЕЗ переоценки — считать нечего. */
    private AvailabilityResult incomplete(String oem, BigDecimal price, String region, int b, int s, boolean searched) {
        log.warn("Не посчитано: нет выдачи drom [{}] (капча/сбой)", region);
        return AvailabilityResult.builder()
                .oemNumber(oem)
                .price(price)
                .barnaulCount(b)
                .siberiaCount(s)
                .searchedSiberia(searched)
                .color(Color.NONE)
                .reprice(false)
                .incomplete(true)
                .status("Не посчитано: drom не отдал выдачу [" + region + "] (капча/сбой) — повторите прогон")
                .collectedAt(java.time.Instant.now())
                .build();
    }

    /** Скан региона через кэш батча (дубли запроса не перезапрашивают drom). Кэшируем только непустой результат.
     *  used=false — выдача без фильтра «Б/у» (для поиска нашего объявления, если оно не помечено б/у).
     *  byOem — запрос это OEM (иначе — строка применимости «деталь марка модель»). */
    private DromParser.RegionScan scan(DromParser p, String query, String region, String myCompany, boolean used,
                                       int maxPages, boolean byOem) {
        // OEM нормализуем (дефисы/пробелы не важны). Строку применимости — нет: normalizeOem оставляет
        // только латиницу и цифры, и «бампер ford focus» с «капот ford focus» сошлись бы в один ключ.
        // Глубина входит в ключ: обычный и точный скан дают разные наборы, смешивать их нельзя.
        String q = byOem ? "oem:" + normalizeOem(query) : "app:" + query.trim().toLowerCase().replaceAll("\\s+", " ");
        String key = q + "|" + region + "|" + used + "|" + maxPages;
        DromParser.RegionScan cached = scanCache.get(key);
        if (cached != null) return cached;
        DromParser.RegionScan sc = p.scanRegion(query, region, myCompany, used, maxPages);
        if (!sc.failed() && (!sc.competitors().isEmpty() || !sc.myCandidates().isEmpty())) scanCache.put(key, sc);
        return sc;
    }

    // ==================== ПОДСЧЁТ / ЦВЕТ ====================

    /** Конкуренты по этому OEM: целевая деталь (без стоп-слов), тот же OEM. Б/у-фильтр — на стороне drom
     *  (condition[]=used в buildSearchUrl), поэтому здесь достаточно фильтра типа/OEM. */
    private int countCompetitors(List<PartPrice> competitors, String oem, List<String> stopWords, boolean matchOem) {
        return filterAssemblies(competitors, oem, stopWords, matchOem).size();
    }

    private Color computeColor(int barnaul, int siberia, boolean searched) {
        boolean barnaulLow = barnaul < BARNAUL_MIN;             // < 4
        boolean siberiaLow = searched && siberia < SIBERIA_MIN; // < 10 (только если искали)
        if (barnaulLow && siberiaLow) return Color.RED;
        if (siberiaLow)               return Color.PURPLE;
        if (barnaulLow)               return Color.YELLOW;
        return Color.NONE;
    }

    /** Переоценка = деталь останется НЕокрашенной (конкуренции достаточно). Вычисляется из тех же
     *  порогов, что и {@link #computeColor} (barnaulLow/siberiaLow), а НЕ из готового цвета: строка
     *  без заливки ⇔ рынок не разрежен ни в Барнауле, ни по Сибири ⇒ true; любой цвет ⇒ false. */
    private boolean needsRepricing(int barnaul, int siberia, boolean searched) {
        boolean barnaulLow = barnaul < BARNAUL_MIN;             // < 4
        boolean siberiaLow = searched && siberia < SIBERIA_MIN; // < 10 (только если искали)
        return !barnaulLow && !siberiaLow;
    }

    private String statusText(Color color, int b, int s, boolean searched) {
        String tail = searched ? ("; Сибирь: " + s + " конк.") : "; Сибирь не искали (в Барнауле ≥ 10)";
        String head = switch (color) {
            case RED    -> "Дефицит: Барнаул < 4 и Сибирь < 10";
            case PURPLE -> "Мало по Сибири (< 10)";
            case YELLOW -> "Мало в Барнауле (< 4)";
            case NONE   -> "Конкуренция достаточная";
        };
        return head + " [Барнаул: " + b + " конк." + tail + "]";
    }

    // ==================== ФИЛЬТРЫ / УТИЛИТЫ ====================

    private static String normalizeOem(String oem) {
        return oem.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
    }

    /** Выкидывает нецелевые (по стоп-словам профиля) и объявления с НЕ совпадающим OEM. */
    private List<PartPrice> filterAssemblies(List<PartPrice> parts, String targetOem, List<String> stopWords, boolean matchOem) {
        String normalizedTarget = normalizeOem(targetOem);
        List<String> stops = stopWords == null ? List.of() : stopWords;
        return parts.stream()
                .filter(p -> {
                    String title = p.getTitle() == null ? "" : p.getTitle().toLowerCase();
                    if (stops.stream().anyMatch(w -> !w.isBlank() && title.contains(w.toLowerCase()))) return false;
                    if (matchOem && p.getOem() != null && !p.getOem().isBlank()) {
                        return normalizeOem(p.getOem()).equals(normalizedTarget);
                    }
                    return true;   // OEM со страницы поиска не извлёкся — доверяем поисковому запросу
                })
                .collect(Collectors.toList());
    }

    /** Выбор нашего объявления из кандидатов по близости цены к каталожной (точное — приоритет). */
    private String pickUrlByPrice(List<DromParser.MyListingCandidate> candidates, BigDecimal targetPrice) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (targetPrice == null || targetPrice.signum() <= 0) return candidates.get(0).url();
        DromParser.MyListingCandidate best = null;
        double bestDiff = Double.MAX_VALUE;
        for (DromParser.MyListingCandidate c : candidates) {
            if (c.price() == null) continue;
            double diff = Math.abs(c.price().doubleValue() - targetPrice.doubleValue());
            if (diff < bestDiff) { bestDiff = diff; best = c; }
        }
        return best != null ? best.url() : candidates.get(0).url();
    }
}
