package ru.retail.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Результат анализа наличия/плотности рынка по одной запчасти (без ИИ).
 * Считает, сколько конкурентов (без объявлений нашей компании) торгуют тем же OEM
 * б/у в Барнауле и — при разреженном рынке — по всей Сибири, и определяет цвет строки.
 */
@Data
@Builder
@Schema(description = "Результат анализа наличия по OEM")
public class AvailabilityResult {

    /** Цвет итоговой строки. Приоритет при определении: RED > PURPLE > YELLOW > NONE. */
    public enum Color { NONE, YELLOW, PURPLE, RED }

    @Schema(description = "OEM-номер", example = "4775068010")
    private String oemNumber;

    @Schema(description = "Название запчасти из каталога")
    private String partName;

    @Schema(description = "Марка авто")
    private String brand;

    @Schema(description = "Модель авто")
    private String model;

    @Schema(description = "Цена из каталога, ₽")
    private BigDecimal price;

    @Schema(description = "Дата создания позиции в каталоге (колонка «Создан»)")
    private String createdDate;

    @Schema(description = "Число конкурентов в Барнауле (тот же OEM, б/у, без нашей компании)")
    private int barnaulCount;

    @Schema(description = "Число конкурентов по всей Сибири включая Барнаул (0 если Сибирь не искали)")
    private int siberiaCount;

    @Schema(description = "Искали ли Сибирь (только если в Барнауле < 10 конкурентов)")
    private boolean searchedSiberia;

    @Schema(description = "URL нашего объявления на drom (null, если не найдено)")
    private String myListingUrl;

    @Schema(description = "Цвет строки: NONE/YELLOW/PURPLE/RED")
    @Builder.Default
    private Color color = Color.NONE;

    @Schema(description = "Текстовая расшифровка статуса рынка")
    private String status;

    @Schema(description = "Время сбора")
    private Instant collectedAt;
}
