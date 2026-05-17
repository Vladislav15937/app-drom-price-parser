package ru.retail.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@Schema(description = "Данные моего объявления, извлечённые с Drom.ru")
public class MyListingInfo {

    @Schema(description = "Название / заголовок объявления")
    private String title;

    @Schema(description = "Описание")
    private String description;

    @Schema(description = "Состояние (новый, б/у, контрактный и т.д.)")
    private String condition;

    @Schema(description = "Производитель (если указан)")
    private String manufacturer;

    @Schema(description = "OEM-номер из объявления")
    private String oem;

    @Schema(description = "Город продавца")
    private String city;

    @Schema(description = "Дата публикации в виде строки (как на сайте)")
    private String publishedDate;

    @Schema(description = "URL фотографий (до 5 штук)")
    private List<String> photoUrls;

    @Schema(description = "Цена из объявления")
    private BigDecimal price;
}
