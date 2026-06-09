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
import java.util.Base64;
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
    private final String geminiApiKey;
    private final String geminiBaseUrl;
    private final String geminiVisionModel;
    private final ExecutorService photoExecutor = Executors.newFixedThreadPool(3);

    public AIPriceAdvisor(
            @Value("${deepseek.api.token}") String apiKey,
            @Value("${deepseek.api.base-url:https://api.deepseek.com/v1/chat/completions}") String baseUrl,
            @Value("${deepseek.text.model:deepseek-v4-flash}") String textModel,
            @Value("${gemini.api.key:}") String geminiApiKey,
            @Value("${gemini.api.base-url:https://generativelanguage.googleapis.com/v1beta/openai/chat/completions}") String geminiBaseUrl,
            @Value("${gemini.vision.model:gemini-2.0-flash}") String geminiVisionModel) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.textModel = textModel;
        this.geminiApiKey = geminiApiKey;
        this.geminiBaseUrl = geminiBaseUrl;
        this.geminiVisionModel = geminiVisionModel;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(60)).build();
        this.objectMapper = new ObjectMapper();
    }

    // ==================== ПУБЛИЧНЫЙ МЕТОД ====================

    public AIRecommendation analyze(
            MyListingInfo myListing, BigDecimal myCurrentPrice,
            List<PartPrice> cityPrices, List<PartPrice> siberiaPrices,
            boolean isOldListing) {

        if (cityPrices.isEmpty() && siberiaPrices.isEmpty()) {
            return new AIRecommendation(myCurrentPrice, "нет данных",
                    "Конкуренты не найдены ни в городе, ни по Сибири. Цена оставлена без изменений.", "");
        }

        try {
            // ШАГ 1: Оценка моих фото (один раз)
            log.info("Шаг 1/3: Оценка моих фотографий...");
            PhotoAssessment myPhoto = evaluatePhotosAsync(myListing.getPhotoUrls(), myListing.getTitle()).join();
            log.info("Мои фото: {} (коэф. {})", myPhoto.condition(), myPhoto.coefficient());

            // ШАГ 2: Попарное сравнение конкурентов (параллельная оценка фото + текстовая классификация)
            log.info("Шаг 2/3: Попарное сравнение {} городских конкурентов...", cityPrices.size());
            List<PhotoAssessment> cityPhotos = evaluateAllPhotosParallel(cityPrices);
            CompetitorClassification cityClassification = classifyCompetitors(
                    cityPrices, cityPhotos, myListing, myPhoto);

            CompetitorClassification siberiaClassification = CompetitorClassification.empty();
            if (!siberiaPrices.isEmpty()) {
                log.info("Шаг 2b/3: Попарное сравнение {} новосибирских конкурентов...", siberiaPrices.size());
                List<PhotoAssessment> siberiaPhotos = evaluateAllPhotosParallel(siberiaPrices);
                siberiaClassification = classifyCompetitors(siberiaPrices, siberiaPhotos, myListing, myPhoto);
            }

            // ШАГ 3: Стратегия ценообразования
            log.info("Шаг 3/3: Расчёт конкурентной цены...");
            AIRecommendation result = computeCompetitivePrice(
                    cityClassification, cityPrices, siberiaClassification, siberiaPrices,
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

    private boolean isVisionActive() {
        return geminiApiKey != null && !geminiApiKey.isBlank();
    }

    /** Параллельно оценивает фото всех конкурентов в списке. */
    private List<PhotoAssessment> evaluateAllPhotosParallel(List<PartPrice> competitors) {
        List<CompletableFuture<PhotoAssessment>> futures = competitors.stream()
                .map(p -> evaluatePhotosAsync(p.getPhotoUrls(), p.getTitle()))
                .collect(Collectors.toList());
        return futures.stream().map(CompletableFuture::join).collect(Collectors.toList());
    }

    private CompletableFuture<PhotoAssessment> evaluatePhotosAsync(List<String> photoUrls, String context) {
        return CompletableFuture.supplyAsync(() -> evaluatePhotos(photoUrls, context), photoExecutor);
    }

    private PhotoAssessment evaluatePhotos(List<String> photoUrls, String context) {
        if (!isVisionActive()) {
            log.debug("Vision отключён (ключ не задан), пропускаем для '{}'", context);
            return new PhotoAssessment("неизвестно", "оценка недоступна", 0.80);
        }
        return evaluatePhotosViaApi(photoUrls, context);
    }

    private PhotoAssessment evaluatePhotosViaApi(List<String> photoUrls, String context) {
        if (photoUrls == null || photoUrls.isEmpty()) {
            return new PhotoAssessment("нет фото", "нет фото", 0.65);
        }

        List<Map<String, Object>> content = new ArrayList<>();
        int added = 0;
        for (String url : photoUrls) {
            if (added >= 2) break;
            String dataUrl = downloadImageAsBase64(url);
            if (dataUrl == null) continue;
            Map<String, Object> imageItem = new HashMap<>();
            imageItem.put("type", "image_url");
            imageItem.put("image_url", Map.of("url", dataUrl));
            content.add(imageItem);
            added++;
        }

        String partHint = (context != null && !context.isBlank())
                ? "Деталь: " + context + ".\n" : "";
        content.add(Map.of("type", "text", "text", partHint + """
                Оцени автозапчасть на фото по внешнему виду и состоянию.
                Учитывай: видимые повреждения, износ, царапины, сколы, ржавчину, общий вид и качество самого фото.
                Игнорируй бренд и марку — только визуальное состояние детали.
                Верни СТРОГО ТОЛЬКО JSON без пояснений:
                {"condition":"отличное/хорошее/удовлетворительное/плохое","defects":"список дефектов или нет","coefficient":число от 0.5 до 1.0}
                Пример: {"condition":"хорошее","defects":"лёгкие царапины","coefficient":0.85}
                coefficient: 0.95-1.0=отличное, 0.80-0.94=хорошее, 0.65-0.79=удовлетворительное, ниже 0.65=плохое
                """));

        String responseBody = null;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", geminiVisionModel);
            body.put("messages", List.of(Map.of("role", "user", "content", content)));
            body.put("temperature", 0.1);
            body.put("max_tokens", 1500);

            String json = objectMapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(geminiBaseUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + geminiApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(90))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            responseBody = resp.body();
            log.debug("Gemini [{}] HTTP {}: {}", context, resp.statusCode(),
                    responseBody.substring(0, Math.min(500, responseBody.length())));

            if (resp.statusCode() == 429) {
                log.info("Gemini vision: квота исчерпана (429) для '{}'", context);
                return new PhotoAssessment("неизвестно", "квота Gemini исчерпана", 0.80);
            }
            if (resp.statusCode() != 200) {
                log.warn("Gemini vision HTTP {} для '{}': {}", resp.statusCode(), context,
                        responseBody.substring(0, Math.min(300, responseBody.length())));
                return new PhotoAssessment("неизвестно", "ошибка Gemini", 0.80);
            }

            String normalized = responseBody.trim();
            if (normalized.startsWith("[")) {
                normalized = normalized.substring(1, normalized.lastIndexOf(']')).trim();
            }

            Map<String, Object> map = objectMapper.readValue(normalized, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) map.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new IllegalStateException("choices отсутствует: "
                        + normalized.substring(0, Math.min(300, normalized.length())));
            }
            String raw = (String) ((Map<String, Object>) choices.get(0).get("message")).get("content");
            PhotoAssessment result = parsePhotoAssessment(raw);
            log.info("Vision [{}]: {} (коэф. {})", context, result.condition(), result.coefficient());
            return result;

        } catch (Exception e) {
            log.warn("Vision недоступен для '{}': {}. Нейтральный коэф.", context, e.getMessage());
            return new PhotoAssessment("неизвестно", "оценка недоступна", 0.80);
        }
    }

    // ==================== АГЕНТ 2: Попарный классификатор конкурентов ====================

    /**
     * Для каждого конкурента строит попарное сравнение с моим объявлением:
     * учитывает описание, производителя, состояние и оценку фото (если vision активен).
     * Возвращает список вердиктов better/similar/worse с обоснованием по каждому.
     */
    private CompetitorClassification classifyCompetitors(
            List<PartPrice> competitors, List<PhotoAssessment> photos,
            MyListingInfo myListing, PhotoAssessment myPhoto) {

        if (competitors.isEmpty()) return CompetitorClassification.empty();

        String myTitle = myListing.getTitle() != null ? myListing.getTitle().toLowerCase() : "";
        String mySide     = extractSide(myTitle);
        String myPosition = extractPosition(myTitle);
        boolean visionOn  = isVisionActive();

        // --- Моё объявление ---
        StringBuilder sb = new StringBuilder();
        sb.append("МОЁ ОБЪЯВЛЕНИЕ:\n");
        if (myListing.getTitle() != null && !myListing.getTitle().isBlank())
            sb.append("Название: ").append(myListing.getTitle()).append("\n");
        sb.append("Состояние: ").append(myListing.getCondition()).append("\n");
        sb.append("Производитель: ").append(myListing.getManufacturer()).append("\n");
        if (myListing.getDescription() != null && !myListing.getDescription().isBlank())
            sb.append("Описание: ").append(myListing.getDescription(), 0,
                    Math.min(300, myListing.getDescription().length())).append("\n");
        if (visionOn) {
            sb.append("Фото: ").append(myPhoto.condition())
              .append(String.format(" (коэф. %.2f)", myPhoto.coefficient()));
            if (myPhoto.defects() != null && !myPhoto.defects().isBlank())
                sb.append(", дефекты: ").append(myPhoto.defects());
            sb.append("\n");
        } else {
            sb.append("Фото: оценка недоступна — опирайся только на текст описания\n");
        }

        // --- Список конкурентов ---
        sb.append("\nКОНКУРЕНТЫ (попарно сравни каждого с МОЁ ОБЪЯВЛЕНИЕ выше):\n");

        for (int i = 0; i < competitors.size(); i++) {
            PartPrice p = competitors.get(i);
            PhotoAssessment pa = i < photos.size() ? photos.get(i) : new PhotoAssessment("нет фото", "", 0.80);

            sb.append("\n--- КОНКУРЕНТ ").append(i + 1)
              .append(" (цена: ").append(p.getPrice()).append("₽) ---\n");

            if (p.getTitle() != null && !p.getTitle().isBlank())
                sb.append("Название: ")
                  .append(p.getTitle(), 0, Math.min(120, p.getTitle().length())).append("\n");

            if (p.getDescription() != null && !p.getDescription().isBlank()) {
                String desc = p.getDescription()
                        .replaceAll("(?m)Продавец:.*$", "")
                        .replaceAll("(?m)Рейтинг:.*$", "")
                        .replaceAll("(?m)Город:.*$", "")
                        .trim();
                if (desc.length() > 10)
                    sb.append("Описание: ")
                      .append(desc, 0, Math.min(200, desc.length())).append("\n");
            }

            if (visionOn) {
                sb.append("Фото: ").append(pa.condition())
                  .append(String.format(" (коэф. %.2f)", pa.coefficient()));
                if (pa.defects() != null && !pa.defects().isBlank())
                    sb.append(", дефекты: ").append(pa.defects());
                sb.append("\n");
                // Явная подсказка по фото для сравнения
                double diff = pa.coefficient() - myPhoto.coefficient();
                if (diff > 0.10)
                    sb.append("→ фото этого конкурента ЗАМЕТНО ЛУЧШЕ моего\n");
                else if (diff < -0.10)
                    sb.append("→ фото этого конкурента ЗАМЕТНО ХУЖЕ моего\n");
                else
                    sb.append("→ фото сопоставимо с моим\n");
            }

            String compTitle = p.getTitle() != null ? p.getTitle().toLowerCase() : "";
            if (isSideMismatch(mySide, compTitle) || isPositionMismatch(myPosition, compTitle)) {
                sb.append("[ДРУГАЯ СТОРОНА/ПОЗИЦИЯ — вердикт строго worse]\n");
                log.debug("Другая сторона: {}", p.getTitle());
            }
        }

        String systemPrompt = buildClassifierSystemPrompt(visionOn);
        String response = callTextAI(systemPrompt, sb.toString());
        return parseCompetitorClassification(response);
    }

    private String buildClassifierSystemPrompt(boolean visionOn) {
        String photoInstruction = visionOn
                ? "Учитывай совокупность: описание (состояние, дефекты, производитель) и оценку фото (коэффициент, подсказка «лучше/хуже/сопоставимо»)."
                : "Оценка фото недоступна — учитывай только текст: состояние из описания, производитель (оригинал/аналог), упомянутые дефекты.";

        return """
                Ты эксперт по автозапчастям. Для каждого пронумерованного конкурента выполни ПОПАРНОЕ сравнение с МОЁ ОБЪЯВЛЕНИЕ.
                """ + photoInstruction + """

                Игнорируй: продавца, рейтинг, доставку, город.

                ОБЯЗАТЕЛЬНЫЕ ПРАВИЛА (применяй первыми):
                - Разные стороны (левый/правый) → "worse" без исключений.
                - Разные позиции (передний/задний) → "worse" без исключений.
                - Если сторона конкурента явно не указана → считай совместимым.
                - Конкурент "новый" а моё б/у → "better".
                - Конкурент аналог а моё оригинал → "worse".
                - Конкурент оригинал а моё аналог → "better".

                Вердикт (относительно МОЕГО объявления):
                - "better"  = конкурент явно лучше: лучшее состояние, коэф. фото выше на 0.10+, новый vs б/у, оригинал vs аналог
                - "similar" = сопоставимое качество: схожее состояние, коэф. фото в пределах ±0.10
                - "worse"   = явно хуже: дефекты, ниже класс детали, другая сторона, коэф. фото ниже на 0.10+

                Верни СТРОГО ТОЛЬКО JSON без пояснений вне него:
                {
                  "comparisons": [
                    {"n":1,"price":цена_числом,"verdict":"better/similar/worse","reason":"1-2 предложения с конкретным обоснованием"},
                    {"n":2,"price":цена_числом,"verdict":"...","reason":"..."}
                  ],
                  "summary": "1-2 предложения: общий вывод о рынке и позиции моего товара"
                }
                """;
    }

    // ==================== СТРАТЕГИЯ КОНКУРЕНТНОГО ЦЕНООБРАЗОВАНИЯ ====================

    private static final int MIN_CITY_COMPETITORS = 3;

    /**
     * Правила:
     * 1. Свежее объявление + в городе ≥3 конкурентов → используем только городские данные
     * 2. Свежее объявление + в городе <3 конкурентов → Новосибирск как ориентир
     * 3. Старое объявление → Новосибирск как ориентир
     * 4. Фото-дисконт при коэф. < 0.70
     */
    private AIRecommendation computeCompetitivePrice(
            CompetitorClassification cityClass, List<PartPrice> cityPrices,
            CompetitorClassification siberiaClass, List<PartPrice> siberiaPrices,
            PhotoAssessment myPhoto, BigDecimal myCurrentPrice, boolean isOldListing) {

        String photoNote = myPhoto.coefficient() < 1.0
                ? String.format(" Мои фото: %s (коэф. %.2f).", myPhoto.condition(), myPhoto.coefficient())
                : "";

        boolean cityClassValid = !cityClass.similar().isEmpty() || !cityClass.better().isEmpty() || !cityClass.worse().isEmpty();
        if (!isOldListing && cityPrices.size() >= MIN_CITY_COMPETITORS && cityClassValid) {
            return strategyForMarket(cityClass, cityPrices, myPhoto, myCurrentPrice, "В городе", photoNote);
        }

        if (!siberiaPrices.isEmpty()) {
            String scope = isOldListing
                    ? "Объявление старше 6 мес. Ориентир — Новосибирск"
                    : "Мало предложений в городе. Ориентир — Новосибирск";
            return strategyForMarket(siberiaClass, siberiaPrices, myPhoto, myCurrentPrice, scope, photoNote);
        }

        if (!cityPrices.isEmpty()) {
            return strategyForMarket(cityClass, cityPrices, myPhoto, myCurrentPrice,
                    "В городе (мало данных)", photoNote);
        }

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
            double target97   = minSimilar * 0.97;

            if (myPrice > target97) {
                // Мы дороже минимального аналога → снижаем на 3%
                target     = BigDecimal.valueOf(target97).setScale(0, RoundingMode.DOWN);
                confidence = similar.size() >= 3 ? "высокая" : "средняя";
                reason     = String.format("%s есть аналоги по цене от %.0f₽. Снижаем до %.0f₽ (−3%%).",
                        scope, minSimilar, target.doubleValue());

            } else if (myPrice < minSimilar * 0.75) {
                // Мы на 25%+ ниже рынка → поднимаем до конкурентного уровня
                target     = BigDecimal.valueOf(target97).setScale(0, RoundingMode.DOWN);
                confidence = similar.size() >= 3 ? "высокая" : "средняя";
                reason     = String.format(
                        "%s наша цена значительно ниже рынка (%.0f₽ vs мин. аналог %.0f₽). Поднимаем до %.0f₽ (−3%% от минимума).",
                        scope, myPrice, minSimilar, target.doubleValue());

            } else {
                // Мы уже немного ниже рынка — оптимально
                target     = myCurrentPrice;
                confidence = "высокая";
                reason     = String.format("%s наша цена ниже всех аналогов (мин. аналог %.0f₽). Цена оптимальна.",
                        scope, minSimilar);
            }

        } else if (!worse.isEmpty() && better.isEmpty()) {
            double minWorse = worse.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
            target     = BigDecimal.valueOf(minWorse * 1.20).setScale(0, RoundingMode.HALF_UP);
            confidence = "средняя";
            reason     = String.format("%s наш товар лучше всех конкурентов. Премия +20%% к минимуму (%.0f₽).",
                    scope, minWorse);

        } else if (!better.isEmpty() && worse.isEmpty()) {
            double minBetter = better.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
            target     = BigDecimal.valueOf(minBetter * 0.80).setScale(0, RoundingMode.DOWN);
            confidence = "средняя";
            reason     = String.format("%s наш товар хуже конкурентов. Скидка −20%% от минимума (%.0f₽).",
                    scope, minBetter);

        } else if (similar.isEmpty() && worse.isEmpty() && better.isEmpty()) {
            target     = myCurrentPrice;
            confidence = "высокая";
            reason     = scope + " аналогов нет. Цена не меняется.";

        } else {
            // Смешанный рынок (есть и better, и worse, нет similar)
            target     = stats.median().multiply(BigDecimal.valueOf(0.95)).setScale(0, RoundingMode.HALF_UP);
            confidence = "низкая";
            reason     = String.format("Неоднородный рынок (%s). Медиана (%.0f₽) × 0.95.",
                    scope.toLowerCase(), stats.median().doubleValue());
        }

        target = applyPhotoDiscount(target, myPhoto);
        BigDecimal bounded = applyBounds(target, stats);
        if (bounded.compareTo(target) != 0) {
            reason += String.format(" [нижний порог: %.0f₽]", bounded.doubleValue());
            target = bounded;
        }

        if (cls.summary() != null && !cls.summary().isBlank()) reason += " " + cls.summary();
        return new AIRecommendation(target, confidence, reason, photoNote);
    }

    /** Дисконт если фото плохое (коэф. < 0.70) — пропорционально до −30%. */
    private BigDecimal applyPhotoDiscount(BigDecimal price, PhotoAssessment myPhoto) {
        if (myPhoto.coefficient() < 0.70) {
            double discount = myPhoto.coefficient() / 0.70;
            log.info("Фото-дисконт: коэф={} → ×{}", myPhoto.coefficient(), String.format("%.2f", discount));
            return price.multiply(BigDecimal.valueOf(discount)).setScale(0, RoundingMode.DOWN);
        }
        return price;
    }

    /** Нижний порог: не ниже 80% от минимума рынка. Верхний потолок не применяется. */
    private BigDecimal applyBounds(BigDecimal price, MarketStats stats) {
        BigDecimal floor = stats.min().multiply(BigDecimal.valueOf(0.80)).setScale(0, RoundingMode.DOWN);
        return price.compareTo(floor) < 0 ? floor : price;
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

    // ==================== ЗАГРУЗКА ИЗОБРАЖЕНИЙ ====================

    private String downloadImageAsBase64(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                log.debug("Не удалось загрузить фото (HTTP {}): {}", resp.statusCode(), url);
                return null;
            }
            String mimeType = resp.headers().firstValue("content-type")
                    .map(ct -> ct.split(";")[0].trim())
                    .orElse("image/jpeg");
            return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(resp.body());
        } catch (Exception e) {
            log.debug("Ошибка загрузки фото {}: {}", url, e.getMessage());
            return null;
        }
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
                    "max_tokens", 2000
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
            Object parsed = objectMapper.readValue(resp.body(), Object.class);
            Map<String, Object> map;
            if (parsed instanceof List<?> list && !list.isEmpty()) {
                map = (Map<String, Object>) list.get(0);
            } else {
                map = (Map<String, Object>) parsed;
            }
            List<Map<String, Object>> choices = (List<Map<String, Object>>) map.get("choices");
            if (choices == null || choices.isEmpty()) {
                String preview = resp.body().substring(0, Math.min(300, resp.body().length()));
                throw new IllegalStateException("choices отсутствует в ответе: " + preview);
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

            // Новый формат: {comparisons:[{n, price, verdict, reason}], summary}
            if (m.containsKey("comparisons")) {
                List<Map<String, Object>> comps = (List<Map<String, Object>>) m.get("comparisons");
                List<Double> similar = new ArrayList<>();
                List<Double> better  = new ArrayList<>();
                List<Double> worse   = new ArrayList<>();
                for (Map<String, Object> comp : comps) {
                    Object priceObj = comp.get("price");
                    if (priceObj == null) continue;
                    double price   = ((Number) priceObj).doubleValue();
                    String verdict = String.valueOf(comp.getOrDefault("verdict", "similar"));
                    String reason  = String.valueOf(comp.getOrDefault("reason", ""));
                    log.debug("Конкурент {}₽ → {} | {}", (long) price, verdict, reason);
                    switch (verdict) {
                        case "better"  -> better.add(price);
                        case "worse"   -> worse.add(price);
                        default        -> similar.add(price);
                    }
                }
                String summary = (String) m.getOrDefault("summary", "");
                return new CompetitorClassification(similar, better, worse, summary);
            }

            // Обратная совместимость со старым форматом {similar:[], better:[], worse:[]}
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

    // ==================== ОПРЕДЕЛЕНИЕ СТОРОНЫ/ПОЗИЦИИ ====================

    private String extractSide(String title) {
        if (title.contains("левый") || title.contains("левой") || title.contains("левая")) return "левый";
        if (title.contains("правый") || title.contains("правой") || title.contains("правая")) return "правый";
        return "";
    }

    private String extractPosition(String title) {
        if (title.contains("передний") || title.contains("передней") || title.contains("передняя")) return "передний";
        if (title.contains("задний") || title.contains("задней") || title.contains("задняя")) return "задний";
        return "";
    }

    private boolean isSideMismatch(String mySide, String competitorTitle) {
        if (mySide.isEmpty()) return false;
        String opposite = mySide.equals("левый") ? "прав" : "лев";
        return competitorTitle.contains(opposite);
    }

    private boolean isPositionMismatch(String myPosition, String competitorTitle) {
        if (myPosition.isEmpty()) return false;
        String opposite = myPosition.equals("передний") ? "задн" : "передн";
        return competitorTitle.contains(opposite);
    }

    // ==================== ЗАПИСИ ====================

    public record AIRecommendation(
            BigDecimal recommendedPrice,
            String confidence,
            String reason,
            String photoNote) {}

    public record PhotoAssessment(String condition, String defects, double coefficient) {}

    private record CompetitorClassification(List<Double> similar, List<Double> better, List<Double> worse, String summary) {
        static CompetitorClassification empty() {
            return new CompetitorClassification(List.of(), List.of(), List.of(), "");
        }
    }

    private record MarketStats(BigDecimal min, BigDecimal median, BigDecimal max, BigDecimal avg, int count) {}
}
