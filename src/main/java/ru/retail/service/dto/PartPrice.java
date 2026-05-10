package ru.retail.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Цена на запчасть")
public class PartPrice {

    @Schema(description = "Название", example = "Фара Toyota Aurion, Camry Powertec 81130-06730")
    private String title;

    @Schema(description = "Цена в рублях", example = "4180")
    private BigDecimal price;

    @Schema(description = "Ссылка на объявление")
    private String url;

    @Schema(description = "Город продавца", example = "в Краснодаре")
    private String location;

    @Schema(description = "Название магазина")
    private String dealer;

    @Schema(description = "Дата публикации")
    private String date;

    @Schema(description = "Полное описание с детальной страницы")
    private String description;
}

