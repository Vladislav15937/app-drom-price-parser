package ru.retail.service.service;

import lombok.Builder;
import lombok.Data;
import ru.retail.service.dto.DromListingItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Data
@Builder
public class PriceAggregationResult {
    private String oemNumber;
    private int totalListings;
    private int validPrices;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;
    private BigDecimal averagePrice;
    private BigDecimal medianPrice;
    private BigDecimal recommendedPrice;
    private Instant collectedAt;

    @Builder.Default
    private List<DromListingItem> listings = Collections.emptyList();

    public static PriceAggregationResult empty(String oemNumber) {
        return PriceAggregationResult.builder()
                .oemNumber(oemNumber)
                .collectedAt(Instant.now())
                .build();
    }
}

