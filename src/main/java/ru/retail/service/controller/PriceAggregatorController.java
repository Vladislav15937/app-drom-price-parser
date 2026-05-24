package ru.retail.service.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.retail.service.dto.AggregationResult;
import ru.retail.service.service.PriceAnalyzer;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/prices")
@RequiredArgsConstructor
@Tag(name = "Агрегатор цен на запчасти")
public class PriceAggregatorController {

    private final PriceAnalyzer priceAnalyzer;

    @GetMapping("/health")
    @Operation(summary = "Проверка работоспособности")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Парсер работает");
    }

    @PostMapping("/oem/{oemNumber}/ai-price")
    @Operation(summary = "AI-рекомендация конкурентной цены",
               description = """
                       Парсит конкурентов на Drom.ru по OEM-номеру и региону.
                       Оценивает фотографии (мои и конкурентов) через vision-агент.
                       Если объявление старше 6 месяцев или конкурентов в городе мало — расширяет поиск на Сибирь.
                       Возвращает рекомендованную цену с обоснованием.
                       """)
    public ResponseEntity<AggregationResult> aiPrice(

            @Parameter(description = "OEM-номер запчасти", example = "26692AE020")
            @PathVariable String oemNumber,

            @Parameter(description = "Регион (slug Drom.ru)", example = "barnaul")
            @RequestParam(defaultValue = "barnaul") String region,

            @Parameter(description = "URL моего объявления на baza.drom.ru",
                       example = "https://baza.drom.ru/barnaul/sell_spare_parts/support-zadnij-pravyj-subaru-legacy-26692ae020-g13066380597.html")
            @RequestParam String myListingUrl,

            @Parameter(description = "Переопределить цену (если 0 — берётся из объявления)", example = "0")
            @RequestParam(required = false) BigDecimal myPrice) {

        AggregationResult result = priceAnalyzer.analyze(oemNumber, region, myListingUrl, myPrice);
        return ResponseEntity.ok(result);
    }
}
