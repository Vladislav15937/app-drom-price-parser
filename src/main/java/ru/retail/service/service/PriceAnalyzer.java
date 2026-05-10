package ru.retail.service.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.retail.service.dto.AggregationResult;
import ru.retail.service.dto.PartPrice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PriceAnalyzer {

    private final AIPriceAdvisor aiAdvisor;

    public AggregationResult analyze(String oemNumber, List<PartPrice> prices,
                                     String myDescription, String myCondition,
                                     String myManufacturer, BigDecimal myCurrentPrice) {
        if (prices.isEmpty()) {
            return AggregationResult.empty(oemNumber);
        }

        List<BigDecimal> sorted = prices.stream().map(PartPrice::getPrice).sorted().toList();

        // AI-рекомендация
        AIPriceAdvisor.AIRecommendation ai = aiAdvisor.analyze(
                myDescription, myCondition, myManufacturer, myCurrentPrice, prices);

        return AggregationResult.builder()
                .oemNumber(oemNumber)
                .totalFound(prices.size())
                .minPrice(sorted.get(0))
                .maxPrice(sorted.get(sorted.size() - 1))
                .avgPrice(sorted.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(sorted.size()), 2, RoundingMode.HALF_UP))
                .medianPrice(median(sorted))
                .recommendedPrice(ai.recommendedPrice())
                .aiConfidence(ai.confidence())
                .aiReason(ai.reason())
                .items(prices)
                .collectedAt(Instant.now())
                .build();
    }

    private BigDecimal median(List<BigDecimal> sorted) {
        int size = sorted.size();
        return size % 2 == 0
                ? sorted.get(size/2-1).add(sorted.get(size/2)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP)
                : sorted.get(size/2);
    }
}