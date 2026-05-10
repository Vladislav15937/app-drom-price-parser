package ru.retail.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Data
@Builder
@Schema(description = "Результат анализа цен")
public class AggregationResult {

    @Schema(description = "OEM-номер", example = "8113006730")
    private String oemNumber;

    @Schema(description = "Всего найдено", example = "10")
    private int totalFound;

    @Schema(description = "Минимальная цена")
    private BigDecimal minPrice;

    @Schema(description = "Максимальная цена")
    private BigDecimal maxPrice;

    @Schema(description = "Средняя цена")
    private BigDecimal avgPrice;

    @Schema(description = "Медианная цена")
    private BigDecimal medianPrice;

    @Schema(description = "Рекомендованная цена (AI)")
    private BigDecimal recommendedPrice;

    @Schema(description = "Уверенность AI", example = "высокая")
    private String aiConfidence;

    @Schema(description = "Обоснование AI")
    private String aiReason;

    @Schema(description = "Найденные предложения")
    @Builder.Default
    private List<PartPrice> items = Collections.emptyList();

    @Schema(description = "Время сбора")
    private Instant collectedAt;

    public static AggregationResult empty(String oem) {
        return AggregationResult.builder()
                .oemNumber(oem)
                .collectedAt(Instant.now())
                .build();
    }
}
