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
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PriceAnalyzer {

    private static final int CITY_ANALOG_THRESHOLD = 6;   // меньше этого → доищем в Новосибирске
    private static final String FALLBACK_REGION = "novosibirsk";

    private final DromParser dromParser;
    private final AIPriceAdvisor aiAdvisor;

    @Value("${drom.parallel-nsk:false}")
    private boolean parallelNsk;

    private final ExecutorService nskExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nsk-parser");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    public void shutdown() {
        nskExecutor.shutdownNow();
    }

    /** Спекулятивно запускает НСК-парсинг в отдельном потоке/браузере, если включено. */
    private Future<List<PartPrice>> startNskIfParallel(String oemNumber, String region) {
        if (parallelNsk && !region.equals(FALLBACK_REGION)) {
            return nskExecutor.submit(() -> dromParser.parsePartsBackground(oemNumber, FALLBACK_REGION));
        }
        return null;
    }

    /** Забирает результат параллельного НСК; при сбое — последовательный фолбэк. */
    private List<PartPrice> fetchSiberiaRaw(String oemNumber, Future<List<PartPrice>> nskFuture) {
        if (nskFuture != null) {
            try {
                return nskFuture.get();
            } catch (Exception e) {
                log.warn("Параллельный НСК упал ({}) — последовательный фолбэк", e.getMessage());
            }
        }
        return dromParser.parseParts(oemNumber, FALLBACK_REGION);
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
        Future<List<PartPrice>> nskFuture = startNskIfParallel(oemNumber, region);
        MyListingInfo myListing = dromParser.parseMyListing(myListingUrl);
        BigDecimal myPrice = (myPriceOverride != null && myPriceOverride.compareTo(BigDecimal.ZERO) > 0)
                ? myPriceOverride
                : myListing.getPrice();
        log.info("Моё объявление: цена={}₽, дата={}, фото={}",
                myPrice, myListing.getPublishedDate(), myListing.getPhotoUrls().size());

        // 2. Парсим конкурентов в городе (только полные узлы, без ремкомплектов/поршней)
        String normalizedMyUrl = myListingUrl.toLowerCase().replaceAll("/+$", "");
        List<PartPrice> cityRaw = dromParser.parseParts(oemNumber, region).stream()
                .filter(p -> !p.getUrl().toLowerCase().replaceAll("/+$", "").equals(normalizedMyUrl))
                .collect(java.util.stream.Collectors.toList());
        List<PartPrice> cityPrices = filterAssemblies(cityRaw, oemNumber);
        log.info("Конкуренты в городе: {} (до фильтра: {})", cityPrices.size(), cityRaw.size());

        // 3. Если конкурентов в городе меньше 10 → доищем в Новосибирске
        boolean isOldListing = isOlderThan6Months(myListing.getPublishedDate());
        boolean fewCityCompetitors = cityPrices.size() < CITY_ANALOG_THRESHOLD;
        boolean needFallback = isOldListing || fewCityCompetitors;
        log.info("Старое объявление: {} | Конкурентов в городе: {} (нужно {}+) → Поиск в {}: {}",
                isOldListing, cityPrices.size(), CITY_ANALOG_THRESHOLD, FALLBACK_REGION, needFallback);

        List<PartPrice> siberiaPrices = Collections.emptyList();
        if (needFallback && !region.equals(FALLBACK_REGION)) {
            List<PartPrice> fallbackRaw = fetchSiberiaRaw(oemNumber, nskFuture);
            siberiaPrices = filterAssemblies(fallbackRaw, oemNumber);
            log.info("{} после фильтра: {} (до фильтра: {})", FALLBACK_REGION, siberiaPrices.size(), fallbackRaw.size());
        } else if (nskFuture != null) {
            nskFuture.cancel(true);   // город достаточен → спекулятивный НСК не нужен
        }

        // 4. AI-анализ
        AIPriceAdvisor.AIRecommendation ai = aiAdvisor.analyze(
                myListing, myPrice, cityPrices, siberiaPrices, isOldListing);

        // 5. Статистика
        PriceStats stats = !cityPrices.isEmpty() ? computePriceStats(cityPrices)
                : !siberiaPrices.isEmpty() ? computePriceStats(siberiaPrices) : null;
        BigDecimal minPrice    = stats != null ? stats.min()    : BigDecimal.ZERO;
        BigDecimal maxPrice    = stats != null ? stats.max()    : BigDecimal.ZERO;
        BigDecimal avgPrice    = stats != null ? stats.avg()    : BigDecimal.ZERO;
        BigDecimal medianPrice = stats != null ? stats.median() : BigDecimal.ZERO;

        String marketNote = buildMarketNote(isOldListing, cityPrices.size(), siberiaPrices.size(), needFallback);

        return AggregationResult.builder()
                .oemNumber(oemNumber)
                .totalFound(cityPrices.size() + siberiaPrices.size())
                .cityCompetitorCount(cityPrices.size())
                .siberiaCompetitorCount(siberiaPrices.size())
                .searchedSiberia(needFallback)
                .minPrice(minPrice)
                .maxPrice(maxPrice)
                .avgPrice(avgPrice)
                .medianPrice(medianPrice)
                .recommendedPrice(ai.recommendedPrice())
                .aiConfidence(ai.confidence())
                .aiReason(ai.reason())
                .myPhotoAssessment(ai.photoNote())
                .myListingDate(myListing.getPublishedDate())
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
    public AggregationResult analyzeFromCatalog(
            String oemNumber, BigDecimal catalogPrice, String region, String myCompany) {

        log.info("=== Каталог OEM: {} | Регион: {} | Компания: {} ===", oemNumber, region, myCompany);

        // 1. Быстро ищем URL нашего объявления по имени компании прямо на странице поиска
        String myListingUrl = dromParser.findMyListingUrl(oemNumber, region, myCompany);

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

        // 2. Спекулятивно запускаем НСК параллельно (если включено), затем парсим город
        Future<List<PartPrice>> nskFuture = startNskIfParallel(oemNumber, region);
        Set<String> myUrls = Set.of(myListingUrl);
        List<PartPrice> cityRaw = dromParser.parseParts(oemNumber, region, 10, myUrls);
        List<PartPrice> cityPrices = filterAssemblies(cityRaw, oemNumber);

        // 3. Полная информация о нашем объявлении
        MyListingInfo myListingInfo = dromParser.parseMyListing(myListingUrl);
        BigDecimal myPrice = (catalogPrice != null && catalogPrice.compareTo(BigDecimal.ZERO) > 0)
                ? catalogPrice
                : myListingInfo.getPrice().compareTo(BigDecimal.ZERO) > 0
                        ? myListingInfo.getPrice()
                        : BigDecimal.ZERO;

        log.info("Цена: {}₽", myPrice);

        boolean isOldListing = isOlderThan6Months(myListingInfo.getPublishedDate());
        boolean fewCityCompetitors = cityPrices.size() < CITY_ANALOG_THRESHOLD;
        boolean needFallback = isOldListing || fewCityCompetitors;
        log.info("Старое: {} | Город: {} | Фоллбэк: {}", isOldListing, cityPrices.size(), needFallback);

        List<PartPrice> siberiaPrices = Collections.emptyList();
        if (needFallback && !region.equals(FALLBACK_REGION)) {
            List<PartPrice> fallbackRaw = fetchSiberiaRaw(oemNumber, nskFuture);
            siberiaPrices = filterAssemblies(fallbackRaw, oemNumber);
            log.info("{} после фильтра: {}", FALLBACK_REGION, siberiaPrices.size());
        } else if (nskFuture != null) {
            nskFuture.cancel(true);   // город достаточен → спекулятивный НСК не нужен
        }

        // 7. AI-анализ
        AIPriceAdvisor.AIRecommendation ai = aiAdvisor.analyze(
                myListingInfo, myPrice, cityPrices, siberiaPrices, isOldListing);

        // 8. Статистика
        List<PartPrice> statSource = !cityPrices.isEmpty() ? cityPrices : siberiaPrices;
        PriceStats catalogStats = statSource.isEmpty() ? null : computePriceStats(statSource);
        BigDecimal minPrice    = catalogStats != null ? catalogStats.min()    : BigDecimal.ZERO;
        BigDecimal maxPrice    = catalogStats != null ? catalogStats.max()    : BigDecimal.ZERO;
        BigDecimal avgPrice    = catalogStats != null ? catalogStats.avg()    : BigDecimal.ZERO;
        BigDecimal medianPrice = catalogStats != null ? catalogStats.median() : BigDecimal.ZERO;

        return AggregationResult.builder()
                .oemNumber(oemNumber)
                .totalFound(cityPrices.size() + siberiaPrices.size())
                .cityCompetitorCount(cityPrices.size())
                .siberiaCompetitorCount(siberiaPrices.size())
                .searchedSiberia(needFallback)
                .minPrice(minPrice).maxPrice(maxPrice).avgPrice(avgPrice).medianPrice(medianPrice)
                .recommendedPrice(ai.recommendedPrice())
                .aiConfidence(ai.confidence())
                .aiReason(ai.reason())
                .myPhotoAssessment(ai.photoNote())
                .myListingDate(myListingInfo.getPublishedDate())
                .marketNote(buildMarketNote(isOldListing, cityPrices.size(), siberiaPrices.size(), needFallback))
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
