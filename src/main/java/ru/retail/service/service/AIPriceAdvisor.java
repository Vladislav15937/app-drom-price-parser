package ru.retail.service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.retail.service.dto.PartPrice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AIPriceAdvisor {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    private static final String BASE_URL = "https://open.blackroute.space/v1/chat/completions";
    // Используем НЕ-R1 модель — она не делает {think}, сразу JSON
    private static final String MODEL = "deepseek-chat";

    public AIPriceAdvisor(@Value("${deepseek.api.token}") String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(60)).build();
        this.objectMapper = new ObjectMapper();
    }

    // ==================== ТРИ АГЕНТА ====================

    private String analyzeMarketQualitative(List<PartPrice> marketPrices) {
        String prompt = buildMarketPrompt(marketPrices);
        return callAI("""
            Ты Аналитик рынка. Проанализируй ТОЛЬКО конкурентов.
            Верни СТРОГО ТОЛЬКО JSON, без пояснений:
            {"outliers":"есть/нет","marketDensity":"высокая/средняя/низкая","comment":"1-2 предложения"}
            """, prompt);
    }

    private ConditionReport assessCondition(String description, String condition, String manufacturer) {
        String prompt = String.format("""
        Деталь: %s | Состояние: %s | Производитель: %s
        """, description, condition, manufacturer);

        String response = callAI("""
        Ты Оценщик качества автозапчастей. Оцени ТОЛЬКО качество детали.
        Игнорируй рейтинг продавца, магазин, доставку — это неважно.
        
        Коэффициенты КАЧЕСТВА (не продавца!):
        
        ПРОИЗВОДИТЕЛЬ:
        - Оригинал (Toyota, Honda, BMW и т.д.): 1.00
        - Качественный аналог (CASP, Depo, TYC, Hella): 0.90
        - Средний аналог (Sailing, SAT): 0.80
        - Бюджетный аналог (Powertec): 0.70
        
        СОСТОЯНИЕ:
        - Новый: 1.00
        - Контрактный (без пробега по РФ): 0.90
        - Контрактный (с пробегом по РФ): 0.80
        - Б/у (пробег до 50 000 км): 0.85
        - Б/у (пробег 50 000-100 000 км): 0.75
        - Б/у (пробег 100 000+ км): 0.65
        - Б/у (пробег неизвестен): 0.70
        
        ДЕФЕКТЫ (вычитаются дополнительно):
        - Царапина: -0.05
        - Скол: -0.10
        - Без скобы/направляющих: -0.15
        - Уценка: -0.10
        - Отсутствие упаковки: -0.03
        
        ВАЖНО: контрактный = снят с японского/европейского авто, без пробега по РФ = ПЛЮС.
        Пробег по РФ = МИНУС (износ по нашим дорогам).
        
        Верни СТРОГО ТОЛЬКО JSON:
        {"coefficient":число от 0 до 1, "breakdown":"краткое обоснование"}
        """, prompt);

        return parseConditionReport(response);
    }

    private AIRecommendation makeFinalDecision(
            MarketStats stats, ConditionReport cond, BigDecimal myPrice, String myDesc, String qualitative) {

        String prompt = String.format("""
        РЫНОК: мин=%s, мед=%s, макс=%s, ср=%s, предл=%d, разброс=%s%%
        КАЧЕСТВЕННЫЙ АНАЛИЗ: %s
        КОЭФФИЦИЕНТ КАЧЕСТВА ДЕТАЛИ: %s (%s)
        БАЗОВАЯ ЦЕНА (медиана×коэф): %s руб.
        МОЯ ТЕКУЩАЯ: %s руб.
        ДЕТАЛЬ: %s
        
        Скорректируй базовую цену, учитывая:
        - Реальное качество детали (коэффициент уже учёл производителя, состояние, дефекты)
        - Плотность рынка (много конкурентов → небольшой минус)
        - Выбросы (аномально дешёвые/дорогие — игнорировать)
        
        НЕ учитывай рейтинг продавца, название магазина, доставку — это не влияет на цену детали.
        
        Верни СТРОГО ТОЛЬКО JSON:
        {"recommendedPrice":число,"confidence":"высокая/средняя/низкая","reason":"обоснование"}
        """,
                stats.min, stats.median, stats.max, stats.avg, stats.count, stats.spread,
                qualitative, cond.coefficient(), cond.breakdown(),
                stats.median.multiply(BigDecimal.valueOf(cond.coefficient())).setScale(0, RoundingMode.HALF_UP),
                myPrice, myDesc);

        String response = callAI("""
        Ты Стратег по ценообразованию автозапчастей.
        Выдай финальную цену на основе качества детали и рыночной ситуации.
        НЕ учитывай рейтинг продавца, доставку, магазин — только деталь и рынок.
        """, prompt);
        return parseResponse(response, myPrice);
    }

    // ==================== ПУБЛИЧНЫЙ МЕТОД ====================

    public AIRecommendation analyze(
            String myDescription, String myCondition, String myManufacturer,
            BigDecimal myCurrentPrice, List<PartPrice> marketPrices) {

        if (marketPrices.isEmpty()) {
            return new AIRecommendation(myCurrentPrice, "Нет данных", "Нет рыночных данных");
        }

        try {
            // МАТЕМАТИКА — считаем сами (AI не умеет)
            MarketStats stats = computeStats(marketPrices);

            // Шаг 1: Качественный анализ рынка
            log.info("Агент 1/3: Аналитик рынка...");
            String qualitative = analyzeMarketQualitative(marketPrices);

            // Шаг 2: Коэффициент состояния
            log.info("Агент 2/3: Оценщик состояния...");
            ConditionReport condition = assessCondition(myDescription, myCondition, myManufacturer);

            // Шаг 3: Стратег
            log.info("Агент 3/3: Стратег...");
            AIRecommendation result = makeFinalDecision(stats, condition, myCurrentPrice, myDescription, qualitative);

            log.info("Рекомендация: {} руб. (уверенность: {})", result.recommendedPrice(), result.confidence());
            return result;

        } catch (Exception e) {
            log.error("Ошибка: {}", e.getMessage());
            return fallback(marketPrices);
        }
    }

    // ==================== МАТЕМАТИКА (без AI) ====================

    private MarketStats computeStats(List<PartPrice> market) {
        List<BigDecimal> sorted = market.stream().map(PartPrice::getPrice).sorted().collect(Collectors.toList());
        int size = sorted.size();

        BigDecimal min = sorted.get(0);
        BigDecimal max = sorted.get(size - 1);
        BigDecimal sum = sorted.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avg = sum.divide(BigDecimal.valueOf(size), 2, RoundingMode.HALF_UP);

        BigDecimal median;
        if (size % 2 == 0) {
            median = sorted.get(size/2 - 1).add(sorted.get(size/2)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        } else {
            median = sorted.get(size / 2);
        }

        BigDecimal spread = max.subtract(min).divide(avg, 2, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));

        return new MarketStats(min, median, max, avg, size, spread.setScale(0, RoundingMode.HALF_UP).toString());
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ ====================

    private String callAI(String systemPrompt, String userPrompt) {
        try {
            Map<String, Object> body = Map.of(
                    "model", MODEL,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)
                    ),
                    "temperature", 0.1,
                    "max_tokens", 300
            );

            String json = objectMapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> map = objectMapper.readValue(resp.body(), Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) map.get("choices");
            return (String) ((Map<String, Object>) choices.get(0).get("message")).get("content");

        } catch (Exception e) {
            log.error("AI error: {}", e.getMessage());
            return null;
        }
    }

    private String buildMarketPrompt(List<PartPrice> market) {
        StringBuilder sb = new StringBuilder("Рынок (" + market.size() + "):\n");
        for (int i = 0; i < market.size(); i++) {
            PartPrice p = market.get(i);
            sb.append(i+1).append(". ").append(p.getPrice()).append("₽");
            if (p.getDescription() != null) {
                String desc = p.getDescription();
                // Оставляем только качество, убираем продавца
                desc = desc.replaceAll("Продавец:.*", "").replaceAll("Рейтинг:.*", "").replaceAll("Город:.*", "");
                if (desc.length() > 10) sb.append(" | ").append(desc.substring(0, Math.min(120, desc.length())));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private ConditionReport parseConditionReport(String content) {
        try {
            String cleaned = content.replaceAll("```json|```", "").trim();
            if (!cleaned.startsWith("{")) cleaned = cleaned.substring(cleaned.indexOf('{'));
            Map<String, Object> m = objectMapper.readValue(cleaned, Map.class);
            return new ConditionReport(((Number) m.get("coefficient")).doubleValue(), (String) m.getOrDefault("breakdown", ""));
        } catch (Exception e) {
            return new ConditionReport(0.8, "не удалось оценить");
        }
    }

    private AIRecommendation parseResponse(String content, BigDecimal def) {
        try {
            String cleaned = content.replaceAll("```json|```", "").trim();
            if (!cleaned.startsWith("{")) cleaned = cleaned.substring(cleaned.indexOf('{'));
            Map<String, Object> m = objectMapper.readValue(cleaned, Map.class);
            return new AIRecommendation(toBD(m.get("recommendedPrice"), def), (String) m.getOrDefault("confidence", "средняя"), (String) m.getOrDefault("reason", ""));
        } catch (Exception e) {
            return new AIRecommendation(def, "средняя", "ошибка парсинга");
        }
    }

    private AIRecommendation fallback(List<PartPrice> p) {
        List<BigDecimal> s = p.stream().map(PartPrice::getPrice).sorted().collect(Collectors.toList());
        return new AIRecommendation(s.get(s.size()/2).multiply(BigDecimal.valueOf(0.95)).setScale(0, RoundingMode.DOWN), "Без AI", "медиана - 5%");
    }

    private BigDecimal toBD(Object o, BigDecimal def) {
        if (o == null) return def;
        if (o instanceof Number) return BigDecimal.valueOf(((Number) o).doubleValue());
        try { return new BigDecimal(o.toString()); } catch (Exception e) { return def; }
    }

    // ==================== RECORDS ====================

    public record AIRecommendation(BigDecimal recommendedPrice, String confidence, String reason) {}
    private record ConditionReport(double coefficient, String breakdown) {}
    private record MarketStats(BigDecimal min, BigDecimal median, BigDecimal max, BigDecimal avg, int count, String spread) {}
}

