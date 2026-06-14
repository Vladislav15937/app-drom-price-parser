package ru.retail.service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
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

    // Кэш классификации по набору конкурентов: один и тот же набор (+ моё объявление + режим vision)
    // → один и тот же вердикт → одна и та же цена. Убирает остаточный недетерминизм LLM при temperature=0.
    private static final long CLASSIFICATION_CACHE_TTL_MS = 30 * 60 * 1000L;
    private record CachedClassification(CompetitorClassification cls, long ts) {}
    private final ConcurrentHashMap<String, CachedClassification> classificationCache = new ConcurrentHashMap<>();

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

    @PreDestroy
    public void shutdown() {
        photoExecutor.shutdownNow();
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

            // ШАГ 2: классификации города и НСК идут ПАРАЛЛЕЛЬНО (две независимые LLM-задачи).
            // НСК (без vision) запускаем сразу — пусть его текстовый запрос перекрывает vision города.
            CompletableFuture<CompetitorClassification> siberiaFut;
            if (!siberiaPrices.isEmpty()) {
                log.info("Шаг 2b/3: Попарное сравнение {} новосибирских конкурентов (без vision, параллельно)...", siberiaPrices.size());
                List<PhotoAssessment> neutralPhotos = Collections.nCopies(
                        siberiaPrices.size(), new PhotoAssessment("неизвестно", "", myPhoto.coefficient()));
                siberiaFut = CompletableFuture.supplyAsync(
                        () -> classifyCompetitors(siberiaPrices, neutralPhotos, myListing, myPhoto, false), photoExecutor);
            } else {
                siberiaFut = CompletableFuture.completedFuture(CompetitorClassification.empty());
            }

            // Город: vision только для конкурентов ≤ моей цены × 1.10; необоценённым — коэф. = мой (без ложного "better")
            log.info("Шаг 2/3: Попарное сравнение {} городских конкурентов...", cityPrices.size());
            List<PhotoAssessment> cityPhotos = evaluatePhotosSelective(cityPrices, myCurrentPrice, myPhoto.coefficient());
            CompetitorClassification cityClassification = classifyCompetitors(
                    cityPrices, cityPhotos, myListing, myPhoto, isVisionActive());

            CompetitorClassification siberiaClassification = siberiaFut.join();

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

    /**
     * Vision только для конкурентов с ценой ≤ моей × 1.10. Необоценённым ставим
     * нейтральный коэффициент = коэф. моих фото, чтобы разница 0.0 не давала ложный "better".
     */
    private List<PhotoAssessment> evaluatePhotosSelective(List<PartPrice> competitors, BigDecimal myPrice, double neutralCoef) {
        double threshold = myPrice.doubleValue() * 1.10;
        long visionCount = competitors.stream()
                .filter(p -> p.getPrice().doubleValue() <= threshold)
                .filter(p -> p.getPhotoUrls() != null && !p.getPhotoUrls().isEmpty())
                .count();
        log.info("Vision: {} из {} городских конкурентов (цена ≤ {}₽, с фото)",
                visionCount, competitors.size(), String.format("%.0f", threshold));
        List<CompletableFuture<PhotoAssessment>> futures = competitors.stream()
                .map(p -> {
                    boolean hasPhotos = p.getPhotoUrls() != null && !p.getPhotoUrls().isEmpty();
                    // Пустые фото = деталь не загрузилась (тайм-аут / лёгкая запись со страницы поиска),
                    // а НЕ «продавец без фото». Не штрафуем как 0.65 → нейтральный коэф. = мой.
                    if (!hasPhotos || p.getPrice().doubleValue() > threshold) {
                        return CompletableFuture.completedFuture(new PhotoAssessment("неизвестно", "", neutralCoef));
                    }
                    return evaluatePhotosAsync(p.getPhotoUrls(), p.getTitle());
                })
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

        // 2 попытки: Gemini изредка «уходит в размышления» и возвращает null content (finish_reason=length).
        for (int attempt = 1; attempt <= 2; attempt++) {
            String responseBody = null;
            try {
                Map<String, Object> body = new HashMap<>();
                body.put("model", geminiVisionModel);
                body.put("messages", List.of(Map.of("role", "user", "content", content)));
                body.put("temperature", 0);   // воспроизводимость оценки
                body.put("max_tokens", 2500);  // запас, чтобы reasoning не съел весь лимит до JSON

                String json = objectMapper.writeValueAsString(body);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(geminiBaseUrl))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + geminiApiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .timeout(Duration.ofSeconds(25))
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
                    log.warn("Gemini vision HTTP {} для '{}' (попытка {}/2): {}", resp.statusCode(), context, attempt,
                            responseBody.substring(0, Math.min(300, responseBody.length())));
                    if (attempt < 2) continue;
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
                if (raw == null || raw.isBlank()) {
                    log.warn("Gemini vision: пустой content для '{}' (finish_reason=length, попытка {}/2)", context, attempt);
                    if (attempt < 2) continue;
                    return new PhotoAssessment("неизвестно", "пустой ответ Gemini", 0.80);
                }
                PhotoAssessment result = parsePhotoAssessment(raw);
                log.info("Vision [{}]: {} (коэф. {})", context, result.condition(), result.coefficient());
                return result;

            } catch (Exception e) {
                log.warn("Vision недоступен для '{}' (попытка {}/2): {}", context, attempt, e.getMessage());
                if (attempt < 2) {
                    try { Thread.sleep(600); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        return new PhotoAssessment("неизвестно", "оценка недоступна", 0.80);
    }

    // ==================== АГЕНТ 2: Попарный классификатор конкурентов ====================

    /**
     * Для каждого конкурента строит попарное сравнение с моим объявлением:
     * учитывает описание, производителя, состояние и оценку фото (если vision активен).
     * Возвращает список вердиктов better/similar/worse с обоснованием по каждому.
     */
    private CompetitorClassification classifyCompetitors(
            List<PartPrice> competitors, List<PhotoAssessment> photos,
            MyListingInfo myListing, PhotoAssessment myPhoto, boolean useVision) {

        if (competitors.isEmpty()) return CompetitorClassification.empty();

        String myTitle = myListing.getTitle() != null ? myListing.getTitle().toLowerCase() : "";
        String mySide     = extractSide(myTitle);
        String myPosition = extractPosition(myTitle);
        // Если МОЁ фото не оценилось (тайм-аут vision → "неизвестно"), нельзя судить конкурентов
        // по разнице коэффициентов: база ненадёжна. Переходим на текстовое сравнение.
        boolean myPhotoReliable = !"неизвестно".equals(myPhoto.condition());
        boolean visionOn  = useVision && myPhotoReliable;
        if (useVision && !myPhotoReliable)
            log.info("Моё фото не оценено — сравнение конкурентов по тексту (без коэф. фото)");

        // Кэш по набору конкурентов: тот же набор → тот же вердикт (детерминизм цены)
        String cacheKey = classificationCacheKey(competitors, myListing, visionOn);
        CachedClassification cached = classificationCache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.ts() < CLASSIFICATION_CACHE_TTL_MS) {
            log.info("Классификация из кэша: {} конкурентов (vision={})", competitors.size(), visionOn);
            return cached.cls();
        }

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
        CompetitorClassification result = parseCompetitorClassification(response);

        // Кэшируем только непустой результат (пустой = сбой LLM, его кэшировать нельзя)
        boolean nonEmpty = !result.similar().isEmpty() || !result.better().isEmpty() || !result.worse().isEmpty();
        if (nonEmpty) classificationCache.put(cacheKey, new CachedClassification(result, System.currentTimeMillis()));
        return result;
    }

    /**
     * Ключ кэша классификации: моё объявление (OEM/название/состояние) + отсортированный набор
     * идентификаторов конкурентов (URL, иначе цена+название) + режим vision. Сортировка делает ключ
     * независимым от порядка конкурентов.
     */
    private String classificationCacheKey(List<PartPrice> competitors, MyListingInfo my, boolean visionOn) {
        String mine = norm(my.getOem()) + "|" + norm(my.getTitle()) + "|" + norm(my.getCondition());
        String comps = competitors.stream()
                .map(p -> (p.getUrl() != null && !p.getUrl().isBlank())
                        ? p.getUrl().toLowerCase().replaceAll("/+$", "")
                        : p.getPrice() + "#" + norm(p.getTitle()))
                .sorted()
                .collect(Collectors.joining(","));
        return (visionOn ? "V|" : "T|") + mine + "||" + comps;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase().replaceAll("\\s+", " ");
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

    /** Уклон в быструю продажу: −5% от справедливой медианы (но не ниже минимума рынка). */
    private static final double FAST_SALE_FACTOR = 0.95;

    /** Меньше этого числа конкурентов на рынке → данным нельзя доверять, цену резко не двигаем. */
    private static final int MIN_RELIABLE_COMPETITORS = 3;
    /** Максимальное движение цены от текущей при тонких данных. */
    private static final double THIN_DATA_MAX_MOVE = 0.10;

    /**
     * Единый рынок из двух регионов (Барнаул + Новосибирск). Считаем справедливую цену:
     * не самую низкую, но с лёгким уклоном в быструю продажу. Фото-дисконт при коэф. < 0.70.
     */
    private AIRecommendation computeCompetitivePrice(
            CompetitorClassification cityClass, List<PartPrice> cityPrices,
            CompetitorClassification siberiaClass, List<PartPrice> siberiaPrices,
            PhotoAssessment myPhoto, BigDecimal myCurrentPrice, boolean isOldListing) {

        String photoNote = myPhoto.coefficient() < 1.0
                ? String.format(" Мои фото: %s (коэф. %.2f).", myPhoto.condition(), myPhoto.coefficient())
                : "";

        // Объединяем оба региона в единый рынок
        List<PartPrice> market = new ArrayList<>(cityPrices);
        market.addAll(siberiaPrices);
        if (market.isEmpty()) {
            return new AIRecommendation(myCurrentPrice, "низкая",
                    "Недостаточно данных для анализа. Цена оставлена без изменений.", photoNote);
        }

        CompetitorClassification merged = mergeClassifications(cityClass, siberiaClass);
        String scope = String.format("По 2 регионам (Барнаул %d + НСК %d)", cityPrices.size(), siberiaPrices.size());
        return strategyForMarket(merged, market, myPhoto, myCurrentPrice, scope, photoNote);
    }

    /** Сливает классификации двух регионов в одну (similar/better/worse). */
    private CompetitorClassification mergeClassifications(CompetitorClassification a, CompetitorClassification b) {
        List<Double> similar = new ArrayList<>(a.similar()); similar.addAll(b.similar());
        List<Double> better  = new ArrayList<>(a.better());  better.addAll(b.better());
        List<Double> worse   = new ArrayList<>(a.worse());   worse.addAll(b.worse());
        String summary = (a.summary() != null && !a.summary().isBlank()) ? a.summary() : b.summary();
        return new CompetitorClassification(similar, better, worse, summary);
    }

    private AIRecommendation strategyForMarket(
            CompetitorClassification cls, List<PartPrice> marketPrices,
            PhotoAssessment myPhoto, BigDecimal myCurrentPrice, String scope, String photoNote) {

        MarketStats stats = computeStats(marketPrices);
        List<Double> similar = cls.similar();
        List<Double> worse   = cls.worse();
        List<Double> better  = cls.better();

        BigDecimal target;
        String confidence;
        String reason;
        int priceBasisCount;   // сколько объявлений реально определили цену — база доверия

        if (!similar.isEmpty()) {
            // Анкер — медиана аналогов (конкурентный уровень, а не демпинг под минимум).
            // Двигаем цену вверх/вниз в зависимости от того, скольких конкурентов мы лучше/хуже.
            double minSimilar = similar.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
            double medSimilar = median(similar);
            int betterCnt = better.size();   // конкуренты ЛУЧШЕ нас
            int worseCnt  = worse.size();    // конкуренты ХУЖЕ нас
            int total     = betterCnt + worseCnt + similar.size();
            priceBasisCount = similar.size();   // цену анкерим на медиану аналогов — она и есть база

            if (worseCnt > betterCnt) {
                // Мы лучше большинства → стоим НА медиане рынка, БЕЗ надбавки.
                // Цель — попасть в рынок и продать, а не максимизировать маржу; качество = продаём быстрее при той же цене.
                target     = BigDecimal.valueOf(medSimilar).setScale(0, RoundingMode.HALF_UP);
                confidence = total >= 4 ? "высокая" : "средняя";
                reason     = String.format("%s наш товар лучше %d из %d конкурентов → цена по медиане рынка (без надбавки): %.0f₽.",
                        scope, worseCnt, total, target.doubleValue());

            } else {
                // Справедливая цена с уклоном в быструю продажу: медиана аналогов −5%,
                // но НЕ ниже самого дешёвого аналога — мы не самые дешёвые, но привлекательны.
                double fair = Math.max(minSimilar, medSimilar * FAST_SALE_FACTOR);
                target     = BigDecimal.valueOf(fair).setScale(0, RoundingMode.DOWN);
                confidence = total >= 4 ? "высокая" : "средняя";
                reason     = String.format("%s справедливая цена: медиана аналогов %.0f₽ −5%% (уклон в быструю продажу), не ниже минимума %.0f₽ → %.0f₽.",
                        scope, medSimilar, minSimilar, target.doubleValue());
            }

        } else if (!worse.isEmpty() && better.isEmpty()) {
            double minWorse = worse.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
            target     = BigDecimal.valueOf(minWorse * 1.20).setScale(0, RoundingMode.HALF_UP);
            confidence = "средняя";
            priceBasisCount = worse.size();
            reason     = String.format("%s наш товар лучше всех конкурентов. Премия +20%% к минимуму (%.0f₽).",
                    scope, minWorse);

        } else if (!better.isEmpty() && worse.isEmpty()) {
            double minBetter = better.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
            target     = BigDecimal.valueOf(minBetter * 0.80).setScale(0, RoundingMode.DOWN);
            confidence = "средняя";
            priceBasisCount = better.size();
            reason     = String.format("%s наш товар хуже конкурентов. Скидка −20%% от минимума (%.0f₽).",
                    scope, minBetter);

        } else if (similar.isEmpty() && worse.isEmpty() && better.isEmpty()) {
            target     = myCurrentPrice;
            confidence = "высокая";
            reason     = scope + " аналогов нет. Цена не меняется.";
            priceBasisCount = stats.count();

        } else {
            // Смешанный рынок (есть и better, и worse, нет similar) — позиционируем по балансу
            int betterCnt = better.size();
            int worseCnt  = worse.size();
            priceBasisCount = betterCnt + worseCnt;
            double net    = (worseCnt - betterCnt) / (double) (betterCnt + worseCnt); // −1..+1
            double factor = 1.0 + 0.15 * net;                                          // ±15% от медианы
            target     = stats.median().multiply(BigDecimal.valueOf(factor)).setScale(0, RoundingMode.HALF_UP);
            confidence = "низкая";
            reason     = String.format("Неоднородный рынок (%s): лучше нас %d, хуже %d. Медиана %.0f₽ ×%.2f → %.0f₽.",
                    scope.toLowerCase(), betterCnt, worseCnt, stats.median().doubleValue(), factor, target.doubleValue());
        }

        target = applyPhotoDiscount(target, myPhoto);
        BigDecimal bounded = applyBounds(target, stats, myCurrentPrice);
        if (bounded.compareTo(target) != 0) {
            reason += String.format(" [в границах рынка: %.0f₽]", bounded.doubleValue());
            target = bounded;
        }

        // Тонкие данные → ненадёжно. Считаем по числу СОПОСТАВИМЫХ объявлений, определивших цену,
        // а не по всему рынку: 6 конкурентов, из которых 2 аналога, — это всё ещё тонкие данные.
        // Не двигаем цену резко: не более ±10% от текущей.
        int reliableCount = Math.min(stats.count(), priceBasisCount);
        if (reliableCount < MIN_RELIABLE_COMPETITORS) {
            confidence = "низкая";
            if (myCurrentPrice.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal lo = myCurrentPrice.multiply(BigDecimal.valueOf(1 - THIN_DATA_MAX_MOVE)).setScale(0, RoundingMode.DOWN);
                BigDecimal hi = myCurrentPrice.multiply(BigDecimal.valueOf(1 + THIN_DATA_MAX_MOVE)).setScale(0, RoundingMode.HALF_UP);
                BigDecimal clamped = target.compareTo(hi) > 0 ? hi : (target.compareTo(lo) < 0 ? lo : target);
                if (clamped.compareTo(target) != 0) {
                    reason += String.format(" [мало сопоставимых данных (%d): движение ограничено ±10%% → %.0f₽]",
                            reliableCount, clamped.doubleValue());
                    target = clamped;
                } else {
                    reason += String.format(" [мало сопоставимых данных (%d): низкая уверенность]", reliableCount);
                }
            }
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

    /**
     * Границы рекомендации:
     * - Верхний потолок: не выше максимума рынка.
     * - Если мы уже на минимуме рынка (или ниже) — не опускаемся ещё глубже, остаёмся на своей цене.
     * - Нижний порог: не ниже 80% от минимума рынка.
     */
    private BigDecimal applyBounds(BigDecimal price, MarketStats stats, BigDecimal myCurrentPrice) {
        // Верхний потолок — не выше максимума рынка
        if (price.compareTo(stats.max()) > 0) price = stats.max();

        // Уже самые дешёвые → не подрезаем себя ещё ниже, остаёмся на текущем уровне
        if (myCurrentPrice.compareTo(stats.min()) <= 0 && price.compareTo(myCurrentPrice) < 0) {
            price = myCurrentPrice;
        }

        // Нижний порог — не ниже 80% от минимума рынка
        BigDecimal floor = stats.min().multiply(BigDecimal.valueOf(0.80)).setScale(0, RoundingMode.DOWN);
        if (price.compareTo(floor) < 0) price = floor;

        return price;
    }

    // ==================== МАТЕМАТИКА ====================

    /** Медиана списка цен (для позиционирования внутри группы аналогов). */
    private double median(List<Double> values) {
        List<Double> s = values.stream().sorted().toList();
        int n = s.size();
        return n % 2 == 0 ? (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0 : s.get(n / 2);
    }

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

    /** Сколько раз пробуем текстовый вызов. Троттл шлюза («too many concurrent requests») лечится только ожиданием. */
    private static final int TEXT_MAX_ATTEMPTS = 5;

    private String callTextAI(String system, String user) {
        // Ретрай с экспоненциальным backoff: шлюз при параллельном батче отдаёт 200 с {"error":"...throttled..."}
        // (нет choices) — без долгого ожидания обе попытки падали и классификация терялась → ложное «аналогов нет».
        for (int attempt = 1; attempt <= TEXT_MAX_ATTEMPTS; attempt++) {
            boolean throttled = false;
            try {
                Map<String, Object> body = Map.of(
                        "model", textModel,
                        "messages", List.of(
                                Map.of("role", "system", "content", system),
                                Map.of("role", "user", "content", user)
                        ),
                        "temperature", 0,   // детерминированная классификация → воспроизводимая цена
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
                String bodyStr = resp.body();
                throttled = resp.statusCode() == 429 || isThrottled(bodyStr);
                if (throttled) {
                    throw new IllegalStateException("троттл шлюза: " + preview(bodyStr));
                }
                Object parsed = objectMapper.readValue(bodyStr, Object.class);
                Map<String, Object> map;
                if (parsed instanceof List<?> list && !list.isEmpty()) {
                    map = (Map<String, Object>) list.get(0);
                } else {
                    map = (Map<String, Object>) parsed;
                }
                List<Map<String, Object>> choices = (List<Map<String, Object>>) map.get("choices");
                if (choices == null || choices.isEmpty()) {
                    throw new IllegalStateException("choices отсутствует в ответе: " + preview(bodyStr));
                }
                String content = (String) ((Map<String, Object>) choices.get(0).get("message")).get("content");
                if (content == null || content.isBlank()) throw new IllegalStateException("пустой content в ответе модели");
                return content;
            } catch (Exception e) {
                log.warn("Text AI error (попытка {}/{}{}): {}", attempt, TEXT_MAX_ATTEMPTS,
                        throttled ? ", троттл" : "", e.getMessage());
                if (attempt < TEXT_MAX_ATTEMPTS) {
                    try { Thread.sleep(textBackoffMs(attempt, throttled)); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
                }
            }
        }
        log.error("Text AI: исчерпаны {} попыток — классификация пропущена", TEXT_MAX_ATTEMPTS);
        return null;
    }

    /** Ответ-троттл: шлюз отдаёт его с HTTP 200, поэтому ловим по тексту тела. */
    private static boolean isThrottled(String body) {
        if (body == null) return false;
        String b = body.toLowerCase();
        return b.contains("throttl") || b.contains("too many") || b.contains("rate limit")
                || b.contains("concurrent request");
    }

    /** Экспоненциальный backoff + джиттер. Для троттла ждём дольше (виноват параллелизм, а не битый ответ). */
    private static long textBackoffMs(int attempt, boolean throttled) {
        long base = throttled ? 1200L : 600L;
        long cap = throttled ? 15000L : 4000L;
        long exp = Math.min(cap, base * (1L << (attempt - 1)));   // 1200,2400,4800,9600,15000 (троттл)
        return exp + ThreadLocalRandom.current().nextLong(0, 500);   // джиттер — рассинхрон параллельных дорожек
    }

    private static String preview(String body) {
        return body == null ? "" : body.substring(0, Math.min(300, body.length()));
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
