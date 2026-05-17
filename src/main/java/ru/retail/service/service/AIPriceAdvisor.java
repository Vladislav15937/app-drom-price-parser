package ru.retail.service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.retail.service.dto.MyListingInfo;
import ru.retail.service.dto.PartPrice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AIPriceAdvisor {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final String textModel;
    private final ExecutorService photoExecutor = Executors.newFixedThreadPool(3);

    public AIPriceAdvisor(
            @Value("${deepseek.api.token}") String apiKey,
            @Value("${deepseek.api.base-url:https://api.deepseek.com/v1/chat/completions}") String baseUrl,
            @Value("${deepseek.text.model:deepseek-v4-flash}") String textModel) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.textModel = textModel;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(60)).build();
        this.objectMapper = new ObjectMapper();
    }

    // ==================== ПУБЛИЧНЫЙ МЕТОД ====================

    /**
     * Анализирует рынок и рекомендует конкурентную цену.
     *
     * @param myListing      данные моего объявления (описание, фото, дата)
     * @param myCurrentPrice текущая цена (из объявления или переданная вручную)
     * @param cityPrices     конкуренты в городе (с фото)
     * @param siberiaPrices  конкуренты по Сибири (пусто если не нужны)
     * @param isOldListing   объявление старше 6 месяцев
     */
    public AIRecommendation analyze(
            MyListingInfo myListing, BigDecimal myCurrentPrice,
            List<PartPrice> cityPrices, List<PartPrice> siberiaPrices,
            boolean isOldListing) {

        if (cityPrices.isEmpty() && siberiaPrices.isEmpty()) {
            return new AIRecommendation(myCurrentPrice, "нет данных",
                    "Конкуренты не найдены ни в городе, ни по Сибири. Цена оставлена без изменений.", "");
        }

        try {
            // ШАГ 1: Параллельная оценка фотографий (мои + конкурентов)
            log.info("Шаг 1/3: Оценка фотографий...");
            PhotoAssessment myPhoto = evaluatePhotosAsync(myListing.getPhotoUrls(), "моя деталь").join();
            log.info("Мои фото: {} (коэф. {})", myPhoto.condition(), myPhoto.coefficient());

            List<CompletableFuture<PhotoAssessment>> competitorPhotoFutures = cityPrices.stream()
                    .map(p -> evaluatePhotosAsync(p.getPhotoUrls(), p.getTitle()))
                    .collect(Collectors.toList());
            List<PhotoAssessment> competitorPhotos = competitorPhotoFutures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());

            // ШАГ 2: Классификация конкурентов в городе
            log.info("Шаг 2/3: Классификация {} городских конкурентов...", cityPrices.size());
            CompetitorClassification cityClassification = classifyCompetitors(
                    cityPrices, competitorPhotos, myListing, myPhoto);

            // ШАГ 3: Стратегия ценообразования
            log.info("Шаг 3/3: Расчёт конкурентной цены...");
            AIRecommendation result = computeCompetitivePrice(
                    cityClassification, cityPrices, siberiaPrices,
                    myPhoto, myCurrentPrice, isOldListing);

            log.info("Рекомендация: {} руб. | уверенность: {} | причина: {}",
                    result.recommendedPrice(), result.confidence(), result.reason());
            return result;

        } catch (Exception e) {
            log.error("Ошибка анализа: {}", e.getMessage());
            return fallback(cityPrices.isEmpty() ? siberiaPrices : cityPrices, myCurrentPrice);
        }
    }

    // ==================== АГЕНТ 1: VISION — оценка фотографий ====================

    private CompletableFuture<PhotoAssessment> evaluatePhotosAsync(List<String> photoUrls, String context) {
        return CompletableFuture.supplyAsync(() -> evaluatePhotos(photoUrls, context), photoExecutor);
    }

    private PhotoAssessment evaluatePhotos(List<String> photoUrls, String context) {
        // Vision-модель недоступна в текущем API — возвращаем нейтральный коэффициент
        log.debug("Vision пропущен для '{}': нет vision-модели в API", context);
        return new PhotoAssessment("неизвестно", "оценка недоступна", 0.80);
    }

    @SuppressWarnings("unused")
    private PhotoAssessment evaluatePhotosViaApi(List<String> photoUrls, String context) {
        if (photoUrls == null || photoUrls.isEmpty()) {
            return new PhotoAssessment("нет фото", "нет фото", 0.80);
        }

        List<Map<String, Object>> content = new ArrayList<>();
        int added = 0;
        for (String url : photoUrls) {
            if (added >= 2) break;
            Map<String, Object> imageItem = new HashMap<>();
            imageItem.put("type", "image_url");
            imageItem.put("image_url", Map.of("url", url));
            content.add(imageItem);
            added++;
        }
        content.add(Map.of("type", "text", "text", """
                Оцени автозапчасть на фото по внешнему виду и состоянию.
                Учитывай: видимые повреждения, износ, царапины, сколы, общий вид.
                Игнорируй бренд и марку — только визуальное состояние.
                Верни СТРОГО ТОЛЬКО JSON без пояснений:
                {"condition":"отличное/хорошее/удовлетворительное/плохое","defects":"список дефектов или нет","coefficient":число от 0.5 до 1.0}
                Пример: {"condition":"хорошее","defects":"лёгкие царапины","coefficient":0.85}
                """));

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", "deepseek-vl2"); // заменить на vision-модель при появлении в API
            body.put("messages", List.of(Map.of("role", "user", "content", content)));
            body.put("temperature", 0.1);
            body.put("max_tokens", 200);

            String json = objectMapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> map = objectMapper.readValue(resp.body(), Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) map.get("choices");
            if (choices == null || choices.isEmpty()) {
                String preview = resp.body().substring(0, Math.min(300, resp.body().length()));
                throw new IllegalStateException("choices отсутствует в ответе API: " + preview);
            }
            String raw = (String) ((Map<String, Object>) choices.get(0).get("message")).get("content");
            return parsePhotoAssessment(raw);

        } catch (Exception e) {
            log.warn("Vision AI недоступен для '{}': {}. Используем нейтральный коэф.", context, e.getMessage());
            return new PhotoAssessment("неизвестно", "оценка недоступна", 0.80);
        }
    }

    // ==================== АГЕНТ 2: Классификатор конкурентов ====================

    private CompetitorClassification classifyCompetitors(
            List<PartPrice> competitors, List<PhotoAssessment> photos,
            MyListingInfo myListing, PhotoAssessment myPhoto) {

        if (competitors.isEmpty()) return CompetitorClassification.empty();

        StringBuilder sb = new StringBuilder();
        sb.append("МОЯ ДЕТАЛЬ:\n");
        sb.append("Состояние: ").append(myListing.getCondition()).append("\n");
        sb.append("Производитель: ").append(myListing.getManufacturer()).append("\n");
        sb.append("Описание: ").append(myListing.getDescription()).append("\n");
        sb.append("Оценка по фото: ").append(myPhoto.condition())
          .append(" (коэф. ").append(myPhoto.coefficient()).append(")\n\n");
        sb.append("КОНКУРЕНТЫ:\n");

        for (int i = 0; i < competitors.size(); i++) {
            PartPrice p = competitors.get(i);
            PhotoAssessment pa = i < photos.size() ? photos.get(i) : new PhotoAssessment("нет фото", "", 0.80);

            sb.append(i + 1).append(". Цена: ").append(p.getPrice()).append("₽");
            sb.append(" | Фото: ").append(pa.condition())
              .append(" (коэф. ").append(pa.coefficient()).append(")");

            if (p.getDescription() != null && !p.getDescription().isBlank()) {
                String desc = p.getDescription()
                        .replaceAll("(?m)Продавец:.*$", "")
                        .replaceAll("(?m)Рейтинг:.*$", "")
                        .replaceAll("(?m)Город:.*$", "")
                        .trim();
                if (desc.length() > 10) sb.append(" | ").append(desc, 0, Math.min(150, desc.length()));
            }
            sb.append("\n");
        }

        String response = callTextAI("""
                Ты эксперт по автозапчастям. Сравни качество каждого конкурента с моей деталью.
                Учитывай: оценку по фото (коэффициент и состояние), производителя, состояние из описания, дефекты.
                Игнорируй: продавца, рейтинг, доставку, город.

                Классификация относительно МОЕЙ детали:
                - "similar" = аналогичное качество (коэф. конкурента в пределах ±0.10 от моего, схожее состояние)
                - "better"  = явно лучше (новый vs б/у, оригинал vs аналог, или коэф. выше на 0.15+)
                - "worse"   = явно хуже (дефекты, плохое фото, дешёвый аналог при моём оригинале)

                Верни СТРОГО ТОЛЬКО JSON (цены — числа из списка конкурентов):
                {"similar":[цены],"better":[цены],"worse":[цены],"comment":"краткий вывод о рынке"}
                """, sb.toString());

        return parseCompetitorClassification(response);
    }

    // ==================== СТРАТЕГИЯ КОНКУРЕНТНОГО ЦЕНООБРАЗОВАНИЯ ====================

    /**
     * Правила:
     * 1. Недавнее объявление + в городе есть аналоги дешевле → ПОНИЗИТЬ до min(аналоги)×0.97
     * 2. Недавнее объявление + в городе нет аналогов дешевле → НЕ МЕНЯТЬ (нет смысла снижать)
     * 3. Старое объявление → сравниваем с Сибирью (те же правила)
     * 4. Корректировка по фото: если моё фото плохое → доп. дисконт
     * 5. Коридор [60% медианы, 130% медианы]
     */
    private AIRecommendation computeCompetitivePrice(
            CompetitorClassification cityClass, List<PartPrice> cityPrices,
            List<PartPrice> siberiaPrices, PhotoAssessment myPhoto,
            BigDecimal myCurrentPrice, boolean isOldListing) {

        String photoNote = myPhoto.coefficient() < 1.0
                ? String.format(" Мои фото: %s (коэф. %.2f).", myPhoto.condition(), myPhoto.coefficient())
                : "";

        // === Для свежих объявлений работаем с городом ===
        if (!isOldListing && !cityPrices.isEmpty()) {
            return strategyForMarket(cityClass, cityPrices, myPhoto, myCurrentPrice,
                    "В городе", photoNote);
        }

        // === Старое объявление или мало конкурентов в городе → работаем с Новосибирском ===
        if (!siberiaPrices.isEmpty()) {
            MarketStats siberiaStats = computeStats(siberiaPrices);
            String scope = isOldListing ? "Объявление старше 6 мес., сравниваем с Новосибирском" : "Мало предложений в городе, смотрим Новосибирск";

            List<Double> similar = cityClass.similar();
            double myPrice = myCurrentPrice.doubleValue();
            double siberiaMin = siberiaStats.min().doubleValue();
            double siberiaMedian = siberiaStats.median().doubleValue();

            // Цена объявления не определена (капча/ошибка парсинга) — рекомендуем по рынку
            if (myPrice == 0) {
                BigDecimal raw = BigDecimal.valueOf(siberiaMin * 0.97).setScale(0, RoundingMode.DOWN);
                raw = applyPhotoDiscount(raw, myPhoto);
                BigDecimal target = applyBounds(raw, siberiaStats);
                String boundsNote = target.compareTo(raw) != 0
                        ? String.format(" [нижний порог: %.0f₽]", target.doubleValue()) : "";
                return new AIRecommendation(target, "средняя",
                        scope + String.format(". Цена объявления не определена. Рекомендуем −3%% от мин. Новосибирска = %.0f₽.%s",
                                target.doubleValue(), boundsNote),
                        photoNote);
            }

            // Есть ли аналоги в Сибири дешевле моей цены?
            if (myPrice > siberiaMin * 1.05) { // больше чем на 5% дороже минимума Сибири
                BigDecimal raw = BigDecimal.valueOf(siberiaMin * 0.97).setScale(0, RoundingMode.DOWN);
                raw = applyPhotoDiscount(raw, myPhoto);
                BigDecimal target = applyBounds(raw, siberiaStats);
                String boundsNote = target.compareTo(raw) != 0
                        ? String.format(" [нижний порог: %.0f₽]", target.doubleValue()) : "";
                return new AIRecommendation(target, "средняя",
                        scope + String.format(". Новосибирск мин=%.0f₽, мед=%.0f₽. −3%% от нск минимума = %.0f₽.%s",
                                siberiaMin, siberiaMedian, target.doubleValue(), boundsNote),
                        photoNote);
            } else {
                BigDecimal raw = applyPhotoDiscount(myCurrentPrice, myPhoto);
                if (raw.compareTo(myCurrentPrice) == 0) {
                    return new AIRecommendation(myCurrentPrice, "высокая",
                            scope + String.format(". Наша цена конкурентна (нск мин=%.0f₽).", siberiaMin), photoNote);
                }
                BigDecimal target = applyBounds(raw, siberiaStats);
                return new AIRecommendation(target, "средняя",
                        scope + String.format(". Цена в норме, корректируем по фото (коэф. %.2f) → %.0f₽.",
                                myPhoto.coefficient(), target.doubleValue()), photoNote);
            }
        }

        // Фолбэк: нет ни города ни Сибири
        return new AIRecommendation(myCurrentPrice, "низкая",
                "Недостаточно данных для анализа. Цена оставлена без изменений.", photoNote);
    }

    private AIRecommendation strategyForMarket(
            CompetitorClassification cls, List<PartPrice> marketPrices,
            PhotoAssessment myPhoto, BigDecimal myCurrentPrice, String scope, String photoNote) {

        MarketStats stats = computeStats(marketPrices);
        List<Double> similar = cls.similar();
        List<Double> worse   = cls.worse();
        List<Double> better  = cls.better();
        double myPrice = myCurrentPrice.doubleValue();

        BigDecimal target;
        String confidence;
        String reason;

        if (!similar.isEmpty()) {
            double minSimilar = similar.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
            if (myPrice > minSimilar) {
                // Есть аналоги дешевле → ОБЯЗАТЕЛЬНО снижаем
                target     = BigDecimal.valueOf(minSimilar * 0.97).setScale(0, RoundingMode.DOWN);
                confidence = similar.size() >= 3 ? "высокая" : "средняя";
                reason     = String.format("%s есть аналоги дешевле (от %.0f₽). Снижаем на 3%% — до %.0f₽.",
                        scope, minSimilar, target.doubleValue());
            } else {
                // Мы уже дешевле или наравне — не трогаем
                target     = myCurrentPrice;
                confidence = "высокая";
                reason     = String.format("%s наша цена уже ниже или равна аналогам (мин. аналог %.0f₽). Цена оптимальна.",
                        scope, minSimilar);
            }

        } else if (!worse.isEmpty() && better.isEmpty()) {
            // Мы лучшие на рынке → небольшая премия
            double minWorse = worse.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
            target     = BigDecimal.valueOf(minWorse * 1.20).setScale(0, RoundingMode.HALF_UP);
            confidence = "средняя";
            reason     = String.format("%s наш товар лучше всех конкурентов. +20%% к дешёвому (%.0f₽).", scope, minWorse);

        } else if (!better.isEmpty() && worse.isEmpty()) {
            // Мы хуже всех → нужна существенная скидка
            double minBetter = better.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
            target     = BigDecimal.valueOf(minBetter * 0.80).setScale(0, RoundingMode.DOWN);
            confidence = "средняя";
            reason     = String.format("%s наш товар хуже конкурентов. −20%% от минимума (%.0f₽).", scope, minBetter);

        } else if (similar.isEmpty() && worse.isEmpty() && better.isEmpty()) {
            // Нет конкурентов вообще
            target     = myCurrentPrice;
            confidence = "высокая";
            reason     = scope + " аналогов нет. Цена не меняется.";

        } else {
            // Смешанный рынок без чётких аналогов
            target     = stats.median().multiply(BigDecimal.valueOf(0.95)).setScale(0, RoundingMode.HALF_UP);
            confidence = "низкая";
            reason     = String.format("Нет чётких аналогов (%s). Медиана (%.0f₽) × 0.95.",
                    scope.toLowerCase(), stats.median().doubleValue());
        }

        target = applyPhotoDiscount(target, myPhoto);
        BigDecimal bounded = applyBounds(target, stats);
        if (bounded.compareTo(target) != 0) {
            reason += String.format(" [нижний порог: %.0f₽]", bounded.doubleValue());
            target = bounded;
        }

        if (cls.comment() != null && !cls.comment().isBlank()) reason += " " + cls.comment();
        return new AIRecommendation(target, confidence, reason, photoNote);
    }

    /** Дополнительный дисконт если фото плохое (коэф. ниже 0.70) */
    private BigDecimal applyPhotoDiscount(BigDecimal price, PhotoAssessment myPhoto) {
        if (myPhoto.coefficient() < 0.70) {
            double discount = myPhoto.coefficient() / 0.70; // до −30%
            log.info("Фото-дисконт: коэф={} → ×{}", myPhoto.coefficient(), String.format("%.2f", discount));
            return price.multiply(BigDecimal.valueOf(discount)).setScale(0, RoundingMode.DOWN);
        }
        return price;
    }

    /**
     * Нижний порог: не ниже 80% от минимальной цены на рынке (защита от аномально низких значений).
     * Верхний потолок не применяется — если аналоги дорогие, цена должна это отражать.
     */
    private BigDecimal applyBounds(BigDecimal price, MarketStats stats) {
        BigDecimal floor = stats.min().multiply(BigDecimal.valueOf(0.80)).setScale(0, RoundingMode.DOWN);
        if (price.compareTo(floor) < 0) return floor;
        return price;
    }

    // ==================== МАТЕМАТИКА ====================

    private MarketStats computeStats(List<PartPrice> market) {
        List<BigDecimal> sorted = market.stream().map(PartPrice::getPrice).sorted().collect(Collectors.toList());
        int size = sorted.size();
        BigDecimal sum = sorted.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avg = sum.divide(BigDecimal.valueOf(size), 2, RoundingMode.HALF_UP);
        BigDecimal median = size % 2 == 0
                ? sorted.get(size / 2 - 1).add(sorted.get(size / 2)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP)
                : sorted.get(size / 2);
        return new MarketStats(sorted.get(0), median, sorted.get(size - 1), avg, size);
    }

    private AIRecommendation fallback(List<PartPrice> prices, BigDecimal myCurrentPrice) {
        if (prices.isEmpty()) return new AIRecommendation(myCurrentPrice, "низкая", "Нет данных", "");
        BigDecimal median = computeStats(prices).median();
        return new AIRecommendation(
                median.multiply(BigDecimal.valueOf(0.95)).setScale(0, RoundingMode.DOWN),
                "низкая", "Фолбэк: медиана −5%", "");
    }

    // ==================== HTTP ====================

    private String callTextAI(String system, String user) {
        try {
            Map<String, Object> body = Map.of(
                    "model", textModel,
                    "messages", List.of(
                            Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", user)
                    ),
                    "temperature", 0.1,
                    "max_tokens", 500
            );
            String json = objectMapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(90))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> map = objectMapper.readValue(resp.body(), Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) map.get("choices");
            if (choices == null || choices.isEmpty()) {
                String preview = resp.body().substring(0, Math.min(300, resp.body().length()));
                throw new IllegalStateException("choices отсутствует в ответе API: " + preview);
            }
            return (String) ((Map<String, Object>) choices.get(0).get("message")).get("content");
        } catch (Exception e) {
            log.error("Text AI error: {}", e.getMessage());
            return null;
        }
    }

    // ==================== ПАРСЕРЫ ОТВЕТОВ ====================

    private PhotoAssessment parsePhotoAssessment(String content) {
        try {
            if (content == null) return new PhotoAssessment("неизвестно", "", 0.80);
            String c = content.replaceAll("```json|```", "").trim();
            if (!c.startsWith("{")) c = c.substring(c.indexOf('{'));
            Map<String, Object> m = objectMapper.readValue(c, Map.class);
            return new PhotoAssessment(
                    (String) m.getOrDefault("condition", "неизвестно"),
                    (String) m.getOrDefault("defects", ""),
                    ((Number) m.getOrDefault("coefficient", 0.80)).doubleValue()
            );
        } catch (Exception e) {
            return new PhotoAssessment("неизвестно", "", 0.80);
        }
    }

    private CompetitorClassification parseCompetitorClassification(String content) {
        try {
            if (content == null) return CompetitorClassification.empty();
            String c = content.replaceAll("```json|```", "").trim();
            if (!c.startsWith("{")) c = c.substring(c.indexOf('{'));
            Map<String, Object> m = objectMapper.readValue(c, Map.class);
            return new CompetitorClassification(
                    toDoubleList(m.get("similar")),
                    toDoubleList(m.get("better")),
                    toDoubleList(m.get("worse")),
                    (String) m.getOrDefault("comment", "")
            );
        } catch (Exception e) {
            log.warn("Не удалось распарсить классификацию: {}", e.getMessage());
            return CompetitorClassification.empty();
        }
    }

    private List<Double> toDoubleList(Object obj) {
        if (!(obj instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(o -> o instanceof Number)
                .map(o -> ((Number) o).doubleValue())
                .collect(Collectors.toList());
    }

    // ==================== ЗАПИСИ ====================

    public record AIRecommendation(
            BigDecimal recommendedPrice,
            String confidence,
            String reason,
            String photoNote) {}

    public record PhotoAssessment(String condition, String defects, double coefficient) {}

    private record CompetitorClassification(List<Double> similar, List<Double> better, List<Double> worse, String comment) {
        static CompetitorClassification empty() {
            return new CompetitorClassification(List.of(), List.of(), List.of(), "");
        }
    }

    private record MarketStats(BigDecimal min, BigDecimal median, BigDecimal max, BigDecimal avg, int count) {}
}
