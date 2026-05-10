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
    private static final String MODEL = "deepseek-chat";

    public AIPriceAdvisor(@Value("${deepseek.api.token}") String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public AIRecommendation analyze(
            String myDescription,
            String myCondition,
            String myManufacturer,
            BigDecimal myCurrentPrice,
            List<PartPrice> marketPrices) {

        if (marketPrices.isEmpty()) {
            return new AIRecommendation(myCurrentPrice, "Нет данных", "Нет рыночных данных");
        }

        String prompt = buildPrompt(myDescription, myCondition, myManufacturer, myCurrentPrice, marketPrices);

        try {
            // Формируем тело запроса в формате OpenAI-compatible API
            Map<String, Object> requestBody = Map.of(
                    "model", MODEL,
                    "messages", List.of(
                            Map.of("role", "system", "content", """
                                    Ты профессиональный оценщик автозапчастей с 10-летним опытом.
                                                            
                                    Правила оценки:
                                    1. Оригинал всегда дороже аналога на 20-40%
                                    2. Новый дороже б/у на 30-50%
                                    3. Дефект (царапина, скол) снижает цену на 10-25%
                                    4. Москва и СПб дороже регионов на 10-15%
                                    5. Много конкурентов (10+) → цена ниже медианы на 5-10%
                                    6. Мало конкурентов (1-3) → можно выше медианы на 5-15%
                                                            
                                    Отвечай СТРОГО ТОЛЬКО JSON, без пояснений:
                                    {"recommendedPrice": число, "confidence": "высокая/средняя/низкая", "reason": "краткое обоснование"}
                                    """),
                            Map.of("role", "user", "content", prompt)
                    ),
                    "temperature", 0.1,
                    "max_tokens", 1000
            );

            String json = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("DeepSeek API error: {} {}", response.statusCode(), response.body());
                return new AIRecommendation(myCurrentPrice, "Ошибка", "API вернул " + response.statusCode());
            }

            // Парсим ответ
            Map<String, Object> responseMap = objectMapper.readValue(response.body(), Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            String content = (String) message.get("content");

            log.debug("DeepSeek ответ: {}", content);

            return parseResponse(content, myCurrentPrice);

        } catch (Exception e) {
            log.error("Ошибка DeepSeek: {}", e.getMessage());
            return new AIRecommendation(myCurrentPrice, "Ошибка", e.getMessage());
        }
    }

    private String buildPrompt(String desc, String condition, String manufacturer,
                               BigDecimal myPrice, List<PartPrice> market) {
        StringBuilder sb = new StringBuilder();
        sb.append("### МОЯ ДЕТАЛЬ ###\n");
        sb.append("Описание: ").append(desc).append("\n");
        sb.append("Состояние: ").append(condition).append("\n");
        sb.append("Производитель: ").append(manufacturer).append("\n");
        sb.append("Текущая цена: ").append(myPrice).append(" руб.\n\n");

        sb.append("### РЫНОК (топ-").append(market.size()).append(" самых дешёвых) ###\n");
        for (int i = 0; i < market.size(); i++) {
            PartPrice p = market.get(i);
            sb.append(i + 1).append(". \"").append(p.getTitle()).append("\"\n");
            sb.append("   Цена: ").append(p.getPrice()).append(" руб.\n");
            if (p.getLocation() != null && !p.getLocation().isEmpty())
                sb.append("   Регион: ").append(p.getLocation()).append("\n");
            if (p.getDealer() != null && !p.getDealer().isEmpty())
                sb.append("   Продавец: ").append(p.getDealer()).append("\n");
        }

        sb.append("\n### СТАТИСТИКА ###\n");
        sb.append("Минимум: ").append(market.get(0).getPrice()).append(" руб.\n");
        sb.append("Медиана: ").append(median(market)).append(" руб.\n");
        sb.append("Максимум: ").append(market.get(market.size() - 1).getPrice()).append(" руб.\n\n");

        sb.append("Рекомендуй оптимальную цену для БЫСТРОЙ продажи. Учти ВСЕ факторы.");
        return sb.toString();
    }

    private BigDecimal median(List<PartPrice> prices) {
        List<BigDecimal> sorted = prices.stream()
                .map(PartPrice::getPrice).sorted().collect(Collectors.toList());
        int size = sorted.size();
        return size % 2 == 0
                ? sorted.get(size / 2 - 1).add(sorted.get(size / 2)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP)
                : sorted.get(size / 2);
    }

    private AIRecommendation parseResponse(String content, BigDecimal defaultPrice) {
        try {
            Map<String, Object> map = objectMapper.readValue(content, Map.class);

            Object priceObj = map.get("recommendedPrice");
            BigDecimal price = priceObj instanceof Number
                    ? BigDecimal.valueOf(((Number) priceObj).doubleValue())
                    : new BigDecimal(priceObj.toString());

            String confidence = (String) map.getOrDefault("confidence", "средняя");
            String reason = (String) map.getOrDefault("reason", "");

            return new AIRecommendation(price, confidence, reason);

        } catch (Exception e) {
            log.warn("Парсинг ответа DeepSeek не удался: {}", content);
            return new AIRecommendation(defaultPrice, "средняя", "Не удалось разобрать ответ AI");
        }
    }

    public record AIRecommendation(BigDecimal recommendedPrice, String confidence, String reason) {
    }
}

