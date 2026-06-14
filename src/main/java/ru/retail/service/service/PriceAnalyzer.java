package ru.retail.service.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.retail.service.dto.AggregationResult;
import ru.retail.service.dto.MyListingInfo;
import ru.retail.service.dto.PartPrice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PriceAnalyzer {

    private static final int TOP_N = 5;                    // сколько дешёвых конкурентов берём в каждом регионе
    private static final String FALLBACK_REGION = "novosibirsk";

    private final DromParser dromParser;          // одиночная дорожка (веб / одиночный анализ)
    private final DromParserPool pool;            // пул дорожек (IP) для параллельного батча
    private final AIPriceAdvisor aiAdvisor;

    // Кэш конкурентов на батч (общий на все дорожки): дубли OEM — это РАЗНЫЕ наши объявления, но рынок
    // конкурентов у них один. Ключ = OEM (как в каталоге) + регион. Кэшируем ТОЛЬКО успешные загрузки
    // (наше объявление найдено / непустой рынок) — капчевые пустышки кэшировать нельзя, иначе сломается
    // повтор-на-свежем-IP. Чистится в resetBreakers() в начале каждого батча.
    private record CityMarket(List<DromParser.MyListingCandidate> candidates, List<PartPrice> competitors) {}
    private final Map<String, CityMarket> cityCache = new ConcurrentHashMap<>();
    private final Map<String, List<PartPrice>> nskCache = new ConcurrentHashMap<>();

    /** Сколько параллельных дорожек (IP) доступно для батча. */
    public int laneCount() { return pool.size(); }

    /** Предохранитель прокси конкретной дорожки пула. */
    public boolean isProxyDown(int lane) { return pool.lane(lane).isProxyDown(); }
    /** Предохранитель капчи конкретной дорожки пула. */
    public boolean isCaptchaBlocked(int lane) { return pool.lane(lane).isCaptchaBlocked(); }

    /** Прокси недоступен (одиночная дорожка) — для веб-батча/одиночного анализа. */
    public boolean isProxyDown() { return dromParser.isProxyDown(); }
    /** drom блокирует капчей (одиночная дорожка). */
    public boolean isCaptchaBlocked() { return dromParser.isCaptchaBlocked(); }

    /** Сброс предохранителей перед новым прогоном — одиночная дорожка и все дорожки пула. */
    public void resetBreakers() {
        dromParser.resetBreakers();
        for (int i = 0; i < pool.size(); i++) pool.lane(i).resetBreakers();
        cityCache.clear();   // новый батч — старый рынок конкурентов не переиспользуем
        nskCache.clear();
    }

    /**
     * Основной метод анализа. Принимает OEM-номер, мой регион и URL моего объявления.
     *
     * @param oemNumber     OEM-номер запчасти
     * @param region        мой город (slug для baza.drom.ru, например "barnaul")
     * @param myListingUrl  URL моего объявления на Drom.ru
     * @param myPriceOverride если > 0, использует эту цену вместо цены из объявления
     */
    public AggregationResult analyze(String oemNumber, String region,
                                     String myListingUrl, BigDecimal myPriceOverride) {

        // 1. Парсим моё объявление
        log.info("=== Анализ OEM: {} | Регион: {} ===", oemNumber, region);
        MyListingInfo myListing = dromParser.parseMyListing(myListingUrl);
        BigDecimal myPrice = (myPriceOverride != null && myPriceOverride.compareTo(BigDecimal.ZERO) > 0)
                ? myPriceOverride
                : myListing.getPrice();
        log.info("Моё объявление: цена={}₽, дата={}, фото={}",
                myPrice, myListing.getPublishedDate(), myListing.getPhotoUrls().size());

        // 2. Парсим 5 дешёвых конкурентов в городе (без ремкомплектов/поршней)
        String normalizedMyUrl = myListingUrl.toLowerCase().replaceAll("/+$", "");
        List<PartPrice> cityRaw = dromParser.parseParts(oemNumber, region, TOP_N).stream()
                .filter(p -> !p.getUrl().toLowerCase().replaceAll("/+$", "").equals(normalizedMyUrl))
                .collect(java.util.stream.Collectors.toList());
        List<PartPrice> cityPrices = filterAssemblies(cityRaw, oemNumber);
        log.info("Конкуренты в городе: {} (до фильтра: {})", cityPrices.size(), cityRaw.size());

        // 3. Всегда добавляем 5 дешёвых из Новосибирска — работаем по двум регионам
        boolean isOldListing = isOlderThan6Months(myListing.getPublishedDate());
        boolean searchedNsk = !region.equals(FALLBACK_REGION);
        List<PartPrice> siberiaPrices = Collections.emptyList();
        if (searchedNsk) {
            List<PartPrice> fallbackRaw = dromParser.parseParts(oemNumber, FALLBACK_REGION, TOP_N);
            siberiaPrices = filterAssemblies(fallbackRaw, oemNumber);
            log.info("{} после фильтра: {} (до фильтра: {})", FALLBACK_REGION, siberiaPrices.size(), fallbackRaw.size());
        }

        // 4. AI-анализ
        AIPriceAdvisor.AIRecommendation ai = aiAdvisor.analyze(
                myListing, myPrice, cityPrices, siberiaPrices, isOldListing);

        // 5. Статистика по объединённому рынку (оба региона)
        List<PartPrice> combined = new java.util.ArrayList<>(cityPrices);
        combined.addAll(siberiaPrices);
        PriceStats stats = combined.isEmpty() ? null : computePriceStats(combined);
        BigDecimal minPrice    = stats != null ? stats.min()    : BigDecimal.ZERO;
        BigDecimal maxPrice    = stats != null ? stats.max()    : BigDecimal.ZERO;
        BigDecimal avgPrice    = stats != null ? stats.avg()    : BigDecimal.ZERO;
        BigDecimal medianPrice = stats != null ? stats.median() : BigDecimal.ZERO;

        String marketNote = buildMarketNote(isOldListing, cityPrices.size(), siberiaPrices.size(), searchedNsk);

        return AggregationResult.builder()
                .oemNumber(oemNumber)
                .totalFound(cityPrices.size() + siberiaPrices.size())
                .cityCompetitorCount(cityPrices.size())
                .siberiaCompetitorCount(siberiaPrices.size())
                .searchedSiberia(searchedNsk)
                .minPrice(minPrice)
                .maxPrice(maxPrice)
                .avgPrice(avgPrice)
                .medianPrice(medianPrice)
                .recommendedPrice(ai.recommendedPrice())
                .aiConfidence(ai.confidence())
                .aiReason(ai.reason())
                .myPhotoAssessment(ai.photoNote())
                .myListingDate(myListing.getPublishedDate())
                .myListingUrl(myListingUrl)
                .myListingPrice(myListing.getPrice())
                .marketNote(marketNote)
                .items(cityPrices)
                .siberiaItems(siberiaPrices)
                .collectedAt(java.time.Instant.now())
                .build();
    }

    private static final List<String> NON_ASSEMBLY_KEYWORDS = List.of(
            "ремкомплект", "ремонтный комплект", "поршень", "направляющ",
            "пыльник", "скоба", "уплотнитель", "манжет", "прокладк",
            "комплект направляющ", "болт", "пружин", "шплинт"
    );

    private static String normalizeOem(String oem) {
        return oem.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
    }

    /** Выбор URL нашего объявления из кэшированных кандидатов по близости цены к каталожной (точное — приоритет). */
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

    private List<PartPrice> filterAssemblies(List<PartPrice> parts, String targetOem) {
        String normalizedTarget = normalizeOem(targetOem);
        return parts.stream()
                .filter(p -> {
                    String title = p.getTitle() == null ? "" : p.getTitle().toLowerCase();
                    if (NON_ASSEMBLY_KEYWORDS.stream().anyMatch(title::contains)) return false;
                    if (p.getOem() != null && !p.getOem().isBlank()) {
                        String normalizedOem = normalizeOem(p.getOem());
                        boolean match = normalizedOem.equals(normalizedTarget);
                        if (!match) log.debug("Исключён по OEM: {} → {} (ожидался {})", p.getOem(), normalizedOem, normalizedTarget);
                        return match;
                    }
                    return true;
                })
                .collect(java.util.stream.Collectors.toList());
    }

    private record PriceStats(BigDecimal min, BigDecimal max, BigDecimal avg, BigDecimal median) {}

    private PriceStats computePriceStats(List<PartPrice> prices) {
        List<BigDecimal> sorted = prices.stream().map(PartPrice::getPrice).sorted().toList();
        int size = sorted.size();
        BigDecimal sum = sorted.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avg = sum.divide(BigDecimal.valueOf(size), 2, RoundingMode.HALF_UP);
        BigDecimal median = size % 2 == 0
                ? sorted.get(size / 2 - 1).add(sorted.get(size / 2)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP)
                : sorted.get(size / 2);
        return new PriceStats(sorted.get(0), sorted.get(size - 1), avg, median);
    }

    private String buildMarketNote(boolean isOld, int cityCount, int fallbackCount, boolean usedFallback) {
        StringBuilder sb = new StringBuilder();
        if (isOld) sb.append("Объявление старше 6 месяцев. ");
        sb.append("В городе: ").append(cityCount).append(" предл.");
        if (usedFallback) sb.append(" | Новосибирск: ").append(fallbackCount).append(" предл.");
        return sb.toString().trim();
    }

    /**
     * Анализирует цену по OEM из каталога.
     * Автоматически находит объявление myCompany на Drom.ru и запускает анализ конкурентов.
     */
    /** Веб/одиночный путь — одиночная дорожка. */
    public AggregationResult analyzeFromCatalog(
            String oemNumber, BigDecimal catalogPrice, String region, String myCompany) {
        return analyzeFromCatalog(oemNumber, catalogPrice, region, myCompany, dromParser);
    }

    /** Параллельный батч — конкретная дорожка пула (свой IP). */
    public AggregationResult analyzeCatalogLane(
            String oemNumber, BigDecimal catalogPrice, String region, String myCompany, int lane) {
        return analyzeFromCatalog(oemNumber, catalogPrice, region, myCompany, pool.lane(lane));
    }

    // Сколько раз гонять один OEM, если во время прогона случилась капча со сменой IP (повтор на свежем IP).
    private static final int MAX_OEM_ATTEMPTS = 3;

    /**
     * Прогон OEM с повтором при капче: если за время прогона дорожка сменила IP (была нерешаемая капча),
     * OEM прогоняется заново на свежем IP. Стоп — успех без ротации, жёсткий блок капчи или лимит попыток.
     */
    private AggregationResult analyzeFromCatalog(
            String oemNumber, BigDecimal catalogPrice, String region, String myCompany, DromParser p) {
        AggregationResult res = null;
        for (int attempt = 1; attempt <= MAX_OEM_ATTEMPTS; attempt++) {
            long rotBefore = p.rotationCount();
            res = analyzeFromCatalogOnce(oemNumber, catalogPrice, region, myCompany, p);
            boolean rotatedDuringRun = p.rotationCount() > rotBefore;
            if (!rotatedDuringRun || p.isCaptchaBlocked() || attempt >= MAX_OEM_ATTEMPTS) {
                if (rotatedDuringRun && attempt > 1)
                    log.info("OEM {}: завершён после смены IP (попыток: {})", oemNumber, attempt);
                return res;
            }
            log.warn("OEM {}: во время прогона сменился IP из-за капчи — повтор на свежем IP (попытка {}/{})",
                    oemNumber, attempt + 1, MAX_OEM_ATTEMPTS);
        }
        return res;
    }

    private AggregationResult analyzeFromCatalogOnce(
            String oemNumber, BigDecimal catalogPrice, String region, String myCompany, DromParser p) {

        log.info("=== Каталог OEM: {} | Регион: {} | Компания: {} ===", oemNumber, region, myCompany);

        // 1. Город: URL нашего объявления (по компании+цене) + конкуренты. Кэш на батч по OEM+регион —
        //    дубли OEM (наши разные объявления) НЕ грузят страницу поиска drom повторно.
        String cityKey = oemNumber.trim() + "|" + region;
        CityMarket cm = cityCache.get(cityKey);
        String myListingUrl;
        List<PartPrice> cityPrices;
        if (cm != null) {
            myListingUrl = pickUrlByPrice(cm.candidates(), catalogPrice);
            cityPrices = cm.competitors();
            log.info("Город из кэша [{}]: конкурентов {}, вариантов «{}» {}",
                    cityKey, cityPrices.size(), myCompany, cm.candidates().size());
        } else {
            DromParser.CityParseResult city = p.parseCityWithMyListing(oemNumber, region, TOP_N, myCompany, catalogPrice);
            myListingUrl = city.myListingUrl();
            cityPrices = filterAssemblies(city.competitors(), oemNumber);
            // Кэшируем только успешную загрузку (наше объявление найдено → страница отрисовалась, не капча).
            if (myListingUrl != null)
                cityCache.put(cityKey, new CityMarket(city.myCandidates(), cityPrices));
        }

        if (myListingUrl == null) {
            log.info("Объявлений «{}» для OEM {} не найдено в [{}]", myCompany, oemNumber, region);
            return AggregationResult.builder()
                    .oemNumber(oemNumber)
                    .recommendedPrice(BigDecimal.ZERO)
                    .aiReason("Объявление компании «" + myCompany + "» не найдено для OEM " + oemNumber)
                    .aiConfidence("нет данных")
                    .totalFound(0).cityCompetitorCount(0).siberiaCompetitorCount(0)
                    .searchedSiberia(false)
                    .items(Collections.emptyList()).siberiaItems(Collections.emptyList())
                    .collectedAt(java.time.Instant.now())
                    .build();
        }

        log.info("Найдено объявление {}: {}", myCompany, myListingUrl);

        // 3. Полная информация о нашем объявлении
        MyListingInfo myListingInfo = p.parseMyListing(myListingUrl);
        BigDecimal myPrice = (catalogPrice != null && catalogPrice.compareTo(BigDecimal.ZERO) > 0)
                ? catalogPrice
                : myListingInfo.getPrice().compareTo(BigDecimal.ZERO) > 0
                        ? myListingInfo.getPrice()
                        : BigDecimal.ZERO;

        // Сигнал о расхождении: цена каталога заметно отличается от живого объявления (уценка/устаревшие данные).
        BigDecimal livePrice = myListingInfo.getPrice();
        if (catalogPrice != null && catalogPrice.compareTo(BigDecimal.ZERO) > 0
                && livePrice != null && livePrice.compareTo(BigDecimal.ZERO) > 0) {
            double diff = Math.abs(catalogPrice.doubleValue() - livePrice.doubleValue()) / catalogPrice.doubleValue();
            if (diff > 0.15)
                log.warn("Цена каталога {}₽ расходится с ценой объявления {}₽ (~{}%) — используем каталог. Проверьте данные (возможна уценка).",
                        catalogPrice, livePrice, Math.round(diff * 100));
        }

        log.info("Цена: {}₽", myPrice);

        // 4. Всегда добавляем 5 дешёвых из Новосибирска — работаем по двум регионам
        boolean isOldListing = isOlderThan6Months(myListingInfo.getPublishedDate());
        boolean searchedNsk = !region.equals(FALLBACK_REGION);
        List<PartPrice> siberiaPrices = Collections.emptyList();
        if (searchedNsk) {
            String nskKey = oemNumber.trim() + "|" + FALLBACK_REGION;
            List<PartPrice> cachedNsk = nskCache.get(nskKey);
            if (cachedNsk != null) {
                siberiaPrices = cachedNsk;
                log.info("{} из кэша [{}]: {}", FALLBACK_REGION, nskKey, siberiaPrices.size());
            } else {
                List<PartPrice> fallbackRaw = p.parseParts(oemNumber, FALLBACK_REGION, TOP_N);
                siberiaPrices = filterAssemblies(fallbackRaw, oemNumber);
                log.info("{} после фильтра: {}", FALLBACK_REGION, siberiaPrices.size());
                if (!siberiaPrices.isEmpty()) nskCache.put(nskKey, siberiaPrices);   // капчевую пустышку не кэшируем
            }
        }

        // 5. AI-анализ
        AIPriceAdvisor.AIRecommendation ai = aiAdvisor.analyze(
                myListingInfo, myPrice, cityPrices, siberiaPrices, isOldListing);

        // 6. Статистика по объединённому рынку (оба региона)
        List<PartPrice> combined = new java.util.ArrayList<>(cityPrices);
        combined.addAll(siberiaPrices);
        PriceStats catalogStats = combined.isEmpty() ? null : computePriceStats(combined);
        BigDecimal minPrice    = catalogStats != null ? catalogStats.min()    : BigDecimal.ZERO;
        BigDecimal maxPrice    = catalogStats != null ? catalogStats.max()    : BigDecimal.ZERO;
        BigDecimal avgPrice    = catalogStats != null ? catalogStats.avg()    : BigDecimal.ZERO;
        BigDecimal medianPrice = catalogStats != null ? catalogStats.median() : BigDecimal.ZERO;

        return AggregationResult.builder()
                .oemNumber(oemNumber)
                .totalFound(cityPrices.size() + siberiaPrices.size())
                .cityCompetitorCount(cityPrices.size())
                .siberiaCompetitorCount(siberiaPrices.size())
                .searchedSiberia(searchedNsk)
                .minPrice(minPrice).maxPrice(maxPrice).avgPrice(avgPrice).medianPrice(medianPrice)
                .recommendedPrice(ai.recommendedPrice())
                .aiConfidence(ai.confidence())
                .aiReason(ai.reason())
                .myPhotoAssessment(ai.photoNote())
                .myListingDate(myListingInfo.getPublishedDate())
                .myListingUrl(myListingUrl)
                .myListingPrice(myListingInfo.getPrice())
                .marketNote(buildMarketNote(isOldListing, cityPrices.size(), siberiaPrices.size(), searchedNsk))
                .items(cityPrices)
                .siberiaItems(siberiaPrices)
                .collectedAt(java.time.Instant.now())
                .build();
    }

    /**
     * Парсит дату публикации из русского текста Drom.ru и сравнивает с порогом 6 месяцев.
     * Форматы: "15 ноября 2024", "вчера", "2 дня назад", "15.11.2024"
     */
    private boolean isOlderThan6Months(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return false;

        LocalDate threshold = LocalDate.now().minusMonths(6);

        // Относительные даты — точно свежие
        if (dateStr.contains("назад") || dateStr.contains("вчера") || dateStr.contains("сегодня")) {
            return false;
        }

        // Формат "dd.MM.yyyy"
        try {
            LocalDate d = LocalDate.parse(dateStr.trim(), DateTimeFormatter.ofPattern("dd.MM.yyyy"));
            return d.isBefore(threshold);
        } catch (Exception ignored) {}

        // Формат "15 ноября 2024"
        String[] months = {"января","февраля","марта","апреля","мая","июня",
                "июля","августа","сентября","октября","ноября","декабря"};
        for (int i = 0; i < months.length; i++) {
            if (dateStr.contains(months[i])) {
                try {
                    String[] parts = dateStr.trim().split("\\s+");
                    int day   = Integer.parseInt(parts[0]);
                    int month = i + 1;
                    int year  = parts.length > 2 ? Integer.parseInt(parts[2]) : LocalDate.now().getYear();
                    return LocalDate.of(year, month, day).isBefore(threshold);
                } catch (Exception ignored) {}
            }
        }

        return false;
    }
}
