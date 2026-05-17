package ru.retail.service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class PriceAnalyzer {

    private static final int CITY_ANALOG_THRESHOLD = 10; // меньше этого → ищем в Новосибирске
    private static final String FALLBACK_REGION = "novosibirsk";

    private final DromParser dromParser;
    private final AIPriceAdvisor aiAdvisor;

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

        // 2. Парсим конкурентов в городе (только полные узлы, без ремкомплектов/поршней)
        List<PartPrice> cityRaw = dromParser.parseParts(oemNumber, region);
        List<PartPrice> cityPrices = filterAssemblies(cityRaw);
        log.info("Конкуренты в городе: {} (до фильтра: {})", cityPrices.size(), cityRaw.size());

        // 3. Если конкурентов в городе меньше 10 → доищем в Новосибирске
        boolean isOldListing = isOlderThan6Months(myListing.getPublishedDate());
        boolean fewCityCompetitors = cityPrices.size() < CITY_ANALOG_THRESHOLD;
        boolean needFallback = isOldListing || fewCityCompetitors;
        log.info("Старое объявление: {} | Конкурентов в городе: {} (нужно {}+) → Поиск в {}: {}",
                isOldListing, cityPrices.size(), CITY_ANALOG_THRESHOLD, FALLBACK_REGION, needFallback);

        List<PartPrice> siberiaPrices = Collections.emptyList();
        if (needFallback && !region.equals(FALLBACK_REGION)) {
            List<PartPrice> fallbackRaw = dromParser.parseParts(oemNumber, FALLBACK_REGION);
            siberiaPrices = filterAssemblies(fallbackRaw);
            log.info("{} после фильтра: {} (до фильтра: {})", FALLBACK_REGION, siberiaPrices.size(), fallbackRaw.size());
        }

        // 4. AI-анализ
        AIPriceAdvisor.AIRecommendation ai = aiAdvisor.analyze(
                myListing, myPrice, cityPrices, siberiaPrices, isOldListing);

        // 5. Статистика по городу
        BigDecimal minPrice    = BigDecimal.ZERO;
        BigDecimal maxPrice    = BigDecimal.ZERO;
        BigDecimal avgPrice    = BigDecimal.ZERO;
        BigDecimal medianPrice = BigDecimal.ZERO;

        if (!cityPrices.isEmpty()) {
            List<BigDecimal> sorted = cityPrices.stream().map(PartPrice::getPrice).sorted().toList();
            minPrice    = sorted.get(0);
            maxPrice    = sorted.get(sorted.size() - 1);
            avgPrice    = sorted.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(sorted.size()), 2, RoundingMode.HALF_UP);
            medianPrice = sorted.size() % 2 == 0
                    ? sorted.get(sorted.size() / 2 - 1).add(sorted.get(sorted.size() / 2))
                          .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP)
                    : sorted.get(sorted.size() / 2);
        } else if (!siberiaPrices.isEmpty()) {
            List<BigDecimal> sorted = siberiaPrices.stream().map(PartPrice::getPrice).sorted().toList();
            minPrice    = sorted.get(0);
            maxPrice    = sorted.get(sorted.size() - 1);
            avgPrice    = sorted.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(sorted.size()), 2, RoundingMode.HALF_UP);
            medianPrice = sorted.size() % 2 == 0
                    ? sorted.get(sorted.size() / 2 - 1).add(sorted.get(sorted.size() / 2))
                          .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP)
                    : sorted.get(sorted.size() / 2);
        }

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

    private List<PartPrice> filterAssemblies(List<PartPrice> parts) {
        return parts.stream()
                .filter(p -> {
                    String title = p.getTitle() == null ? "" : p.getTitle().toLowerCase();
                    return NON_ASSEMBLY_KEYWORDS.stream().noneMatch(title::contains);
                })
                .collect(java.util.stream.Collectors.toList());
    }

    private String buildMarketNote(boolean isOld, int cityCount, int fallbackCount, boolean usedFallback) {
        StringBuilder sb = new StringBuilder();
        if (isOld) sb.append("Объявление старше 6 месяцев. ");
        sb.append("В городе: ").append(cityCount).append(" предл.");
        if (usedFallback) sb.append(" | Новосибирск: ").append(fallbackCount).append(" предл.");
        return sb.toString().trim();
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
