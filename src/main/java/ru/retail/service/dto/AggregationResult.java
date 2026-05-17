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

    @Schema(description = "Количество конкурентов найдено в городе")
    private int cityCompetitorCount;

    @Schema(description = "Количество конкурентов найдено по Сибири (0 если не искали)")
    private int siberiaCompetitorCount;

    @Schema(description = "Искали ли рынок Сибири (актуально для старых объявлений)")
    private boolean searchedSiberia;

    @Schema(description = "Оценка моей детали по фото")
    private String myPhotoAssessment;

    @Schema(description = "Дата публикации моего объявления")
    private String myListingDate;

    @Schema(description = "Краткий вывод по рыночной ситуации")
    private String marketNote;

    @Schema(description = "Найденные предложения в городе")
    @Builder.Default
    private List<PartPrice> items = Collections.emptyList();

    @Schema(description = "Найденные предложения по Сибири")
    @Builder.Default
    private List<PartPrice> siberiaItems = Collections.emptyList();

    @Schema(description = "Время сбора")
    private Instant collectedAt;

    public static AggregationResult empty(String oem) {
        return AggregationResult.builder()
                .oemNumber(oem)
                .collectedAt(Instant.now())
                .build();
    }
}
