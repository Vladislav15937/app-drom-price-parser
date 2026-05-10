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
import ru.retail.service.dto.PartPrice;
import ru.retail.service.service.DromParser;
import ru.retail.service.service.PriceAnalyzer;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/prices")
@RequiredArgsConstructor
@Tag(name = "Агрегатор цен на запчасти")
public class PriceAggregatorController {

    private final DromParser dromParser;
    private final PriceAnalyzer priceAnalyzer;

//    @GetMapping("/oem/{oemNumber}")
//    @Operation(summary = "Получить цены по OEM-номеру")
//    public ResponseEntity<AggregationResult> getPrices(
//            @Parameter(description = "OEM-номер", example = "8113006730")
//            @PathVariable String oemNumber,
//
//            @Parameter(description = "Регион", example = "krasnodar")
//            @RequestParam(defaultValue = "krasnodar") String region) {
//
//        List<PartPrice> prices = dromParser.parseParts(oemNumber, region);
//        AggregationResult result = priceAnalyzer.analyze(oemNumber, prices);
//        return ResponseEntity.ok(result);
//    }

    @GetMapping("/health")
    @Operation(summary = "Проверка работоспособности")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Парсер работает");
    }

    @PostMapping("/oem/{oemNumber}/ai-price")
    @Operation(summary = "AI-рекомендация цены")
    public ResponseEntity<AggregationResult> aiPrice(
            @PathVariable String oemNumber,
            @RequestParam(defaultValue = "krasnodar") String region,
            @RequestParam String description,
            @RequestParam String condition,
            @RequestParam String manufacturer,
            @RequestParam(defaultValue = "5000") BigDecimal myPrice) {

        List<PartPrice> prices = dromParser.parseParts(oemNumber, region);
        AggregationResult result = priceAnalyzer.analyze(
                oemNumber, prices, description, condition, manufacturer, myPrice);
        return ResponseEntity.ok(result);
    }
}