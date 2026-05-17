package ru.retail.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

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

    @Schema(description = "Дата публикации объявления", example = "15 ноября 2024")
    private String publishedDate;

    @Schema(description = "URL фотографий детали (до 3 штук)")
    private List<String> photoUrls;

    @Schema(description = "Полное описание с детальной страницы")
    private String description;
}

