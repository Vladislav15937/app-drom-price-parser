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
import java.util.concurrent.Semaphore;
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

    // Один шлюз open.blackroute.space обслуживает И текст (deepseek), И vision (gemini), и режет именно
    // КОНКУРЕНТНЫЕ запросы ("too many concurrent requests" / Vertex 429). 4 дорожки × (vision-пул из 3 +
    // классификация) дают всплеск до ~7 одновременных вызовов → массовый троттл. Общий семафор держит
    // число одновременных обращений к шлюзу по ОБОИМ маршрутам в узде. Бин один на все дорожки → лимит глобальный.
    // Подбирается опытно: шлюз очень чувствителен, 2 — безопасный старт (быстрее ≠ важнее корректности ИИ-анализа).
    private static final int GATEWAY_MAX_CONCURRENT = 2;
    private final Semaphore gatewayLimiter = new Semaphore(GATEWAY_MAX_CONCURRENT, true);

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
                    "Конкуренты в Барнауле не найдены. Цена оставлена без изменений.", "");
        }

        try {
            // Контракт-сегментация рынка. Правило: если НАША деталь контрактная и есть ДРУГИЕ
            // контрактные конкуренты — сравниваем ТОЛЬКО с контрактными (не-контрактные выкидываем
            // из набора). Если контрактных конкурентов нет — сравниваем по всему рынку. Наценочного
            // пола ×1.10 над не-контрактным рынком БОЛЬШЕ НЕТ: на drom «контракт» — слабый признак
            // (почти весь б/у-импорт), пол лишь задирал цену выше реального рынка, а медиана пола ещё
            // и засорялась аналогами/колодками из выдачи. Цена — строго по сопоставимому рынку.
            boolean mineContract = isContract(myListing.getCondition() + " " + myListing.getTitle()
                    + " " + (myListing.getDescription() == null ? "" : myListing.getDescription()));
            List<PartPrice> contractCity = filterContract(cityPrices);
            List<PartPrice> contractSiberia = filterContract(siberiaPrices);
            boolean compareContractOnly = mineContract
                    && (!contractCity.isEmpty() || !contractSiberia.isEmpty());
            List<PartPrice> cityCmp    = compareContractOnly ? contractCity    : cityPrices;
            List<PartPrice> siberiaCmp = compareContractOnly ? contractSiberia : siberiaPrices;
            if (compareContractOnly)
                log.info("Контракт: наша деталь контрактная — сравниваем только с контрактными (город {}, НСК {})",
                        cityCmp.size(), siberiaCmp.size());

            // ШАГ 1: Оценка моих фото (один раз)
            log.info("Шаг 1/3: Оценка моих фотографий...");
            PhotoAssessment myPhoto = evaluatePhotosAsync(myListing.getPhotoUrls(), myListing.getTitle()).join();
            log.info("Мои фото: {} (коэф. {})", myPhoto.condition(), myPhoto.coefficient());

            // ШАГ 2: классификации города и НСК идут ПАРАЛЛЕЛЬНО (две независимые LLM-задачи).
            // НСК (без vision) запускаем сразу — пусть его текстовый запрос перекрывает vision города.
            CompletableFuture<CompetitorClassification> siberiaFut;
            if (!siberiaCmp.isEmpty()) {
                log.info("Шаг 2b/3: Попарное сравнение {} новосибирских конкурентов (без vision, параллельно)...", siberiaCmp.size());
                List<PhotoAssessment> neutralPhotos = Collections.nCopies(
                        siberiaCmp.size(), new PhotoAssessment("неизвестно", "", myPhoto.coefficient()));
                siberiaFut = CompletableFuture.supplyAsync(
                        () -> classifyCompetitors(siberiaCmp, neutralPhotos, myListing, myPhoto, false), photoExecutor);
            } else {
                siberiaFut = CompletableFuture.completedFuture(CompetitorClassification.empty());
            }

            // Город: vision только для конкурентов ≤ моей цены × 1.10; необоценённым — коэф. = мой (без ложного "better")
            log.info("Шаг 2/3: Попарное сравнение {} городских конкурентов...", cityCmp.size());
            List<PhotoAssessment> cityPhotos = evaluatePhotosSelective(cityCmp, myCurrentPrice, myPhoto.coefficient());
            CompetitorClassification cityClassification = classifyCompetitors(
                    cityCmp, cityPhotos, myListing, myPhoto, isVisionActive());

            CompetitorClassification siberiaClassification = siberiaFut.join();

            // ШАГ 3: Стратегия ценообразования
            log.info("Шаг 3/3: Расчёт конкурентной цены...");
            AIRecommendation result = computeCompetitivePrice(
                    cityClassification, cityCmp, siberiaClassification, siberiaCmp,
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

        // Троттл шлюза (429 / "concurrent requests") НЕ сдаём — крутим до вменяемого ответа (как и текст).
        // Прочие сбои (пустой content при finish_reason=length, битый JSON, не-200) — ограниченно, затем нейтрально.
        int contentErrors = 0;
        int throttleWait = 0;
        while (true) {
            boolean throttled = false;
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

                String responseBody;
                int status;
                gatewayLimiter.acquire();   // общий лимит на шлюз (текст+vision)
                try {
                    HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                    responseBody = resp.body();
                    status = resp.statusCode();
                } finally {
                    gatewayLimiter.release();
                }
                log.debug("Gemini [{}] HTTP {}: {}", context, status,
                        responseBody.substring(0, Math.min(500, responseBody.length())));

                throttled = status == 429 || isThrottled(responseBody);
                if (throttled) throw new IllegalStateException("троттл Gemini: " + preview(responseBody));

                if (status != 200) {
                    if (++contentErrors >= VISION_MAX_CONTENT_ERRORS)
                        return new PhotoAssessment("неизвестно", "ошибка Gemini", 0.80);
                    log.warn("Gemini vision HTTP {} для '{}' (ошибка {}/{}): {}", status, context,
                            contentErrors, VISION_MAX_CONTENT_ERRORS, preview(responseBody));
                    if (!sleepMs(600)) return new PhotoAssessment("неизвестно", "прервано", 0.80);
                    continue;
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
                    if (++contentErrors >= VISION_MAX_CONTENT_ERRORS)
                        return new PhotoAssessment("неизвестно", "пустой ответ Gemini", 0.80);
                    log.warn("Gemini vision: пустой content для '{}' (finish_reason=length, ошибка {}/{})",
                            context, contentErrors, VISION_MAX_CONTENT_ERRORS);
                    if (!sleepMs(600)) return new PhotoAssessment("неизвестно", "прервано", 0.80);
                    continue;
                }
                PhotoAssessment result = parsePhotoAssessment(raw);
                log.info("Vision [{}]: {} (коэф. {})", context, result.condition(), result.coefficient());
                return result;

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return new PhotoAssessment("неизвестно", "прервано", 0.80);
            } catch (Exception e) {
                if (throttled) {
                    // Троттл лечится ожиданием — ждём, сколько нужно, не теряя оценку фото.
                    log.warn("Gemini vision троттл для '{}' (ожидание {}): {}", context, ++throttleWait, e.getMessage());
                    if (!sleepMs(throttleBackoffMs(throttleWait)))
                        return new PhotoAssessment("неизвестно", "прервано", 0.80);
                } else {
                    if (++contentErrors >= VISION_MAX_CONTENT_ERRORS) {
                        log.warn("Vision недоступен для '{}' ({} ошибок): {}", context, contentErrors, e.getMessage());
                        return new PhotoAssessment("неизвестно", "оценка недоступна", 0.80);
                    }
                    log.warn("Vision недоступен для '{}' (ошибка {}/{}): {}",
                            context, contentErrors, VISION_MAX_CONTENT_ERRORS, e.getMessage());
                    if (!sleepMs(600)) return new PhotoAssessment("неизвестно", "прервано", 0.80);
                }
            }
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

        // --- Заголовок «МОЁ ОБЪЯВЛЕНИЕ» (общий для всех чанков) ---
        StringBuilder hdr = new StringBuilder();
        hdr.append("МОЁ ОБЪЯВЛЕНИЕ:\n");
        if (myListing.getTitle() != null && !myListing.getTitle().isBlank())
            hdr.append("Название: ").append(myListing.getTitle()).append("\n");
        hdr.append("Состояние: ").append(myListing.getCondition()).append("\n");
        hdr.append("Производитель: ").append(myListing.getManufacturer()).append("\n");
        if (myListing.getDescription() != null && !myListing.getDescription().isBlank())
            hdr.append("Описание: ").append(myListing.getDescription(), 0,
                    Math.min(300, myListing.getDescription().length())).append("\n");
        if (visionOn) {
            hdr.append("Фото: ").append(myPhoto.condition())
               .append(String.format(" (коэф. %.2f)", myPhoto.coefficient()));
            if (myPhoto.defects() != null && !myPhoto.defects().isBlank())
                hdr.append(", дефекты: ").append(myPhoto.defects());
            hdr.append("\n");
        } else {
            hdr.append("Фото: оценка недоступна — опирайся только на текст описания\n");
        }
        String header = hdr.toString();

        // --- Детерминированные правила (forceWorse) по ВСЕМ конкурентам, считаем в коде ---
        // forceWorse[i] перебивает вердикт модели на "worse" (см. parseCompetitorClassification).
        String myManuf = myListing.getManufacturer() != null ? myListing.getManufacturer().toLowerCase() : "";
        boolean mineOriginal = !isAnalog(myTitle + " " + myManuf);
        boolean[] forceWorse = new boolean[competitors.size()];
        for (int i = 0; i < competitors.size(); i++) {
            PartPrice p = competitors.get(i);
            String compTitle = p.getTitle() != null ? p.getTitle().toLowerCase() : "";
            String compText  = compTitle + " " + (p.getDescription() != null ? p.getDescription().toLowerCase() : "");
            boolean sideOff   = isSideMismatch(mySide, compTitle) || isPositionMismatch(myPosition, compTitle);
            boolean analogOff = mineOriginal && isAnalog(compText);
            if (sideOff || analogOff) {
                forceWorse[i] = true;
                log.debug("forceWorse[{}]: {} ({})", i, p.getTitle(), sideOff ? "сторона/позиция" : "аналог");
            }
        }

        String systemPrompt = buildClassifierSystemPrompt(visionOn);

        // --- Классификация ЧАНКАМИ ≤ CLASSIFY_CHUNK ---
        // Ответ классификатора (вердикт+причина на конкурента) растёт линейно с числом конкурентов;
        // на 34–36 он переполнял max_tokens → обрезанный JSON → не парсился. Чанки держат каждый ответ
        // в пределах бюджета токенов, при этом ВСЕ конкуренты классифицируются (vision/причины сохранены).
        int n = competitors.size();
        List<Double> similar = new ArrayList<>(), better = new ArrayList<>(), worse = new ArrayList<>();
        String summary = "";
        boolean degraded = false;

        for (int start = 0; start < n; start += CLASSIFY_CHUNK) {
            int end = Math.min(start + CLASSIFY_CHUNK, n);
            StringBuilder sb = new StringBuilder(header);
            sb.append("\nКОНКУРЕНТЫ (попарно сравни каждого с МОЁ ОБЪЯВЛЕНИЕ выше):\n");
            boolean[] chunkForce = new boolean[end - start];
            for (int i = start; i < end; i++) {
                int display = i - start + 1;   // нумерация 1..k ВНУТРИ чанка (parse мапит n→forceWorse[n-1])
                PhotoAssessment pa = i < photos.size() ? photos.get(i) : new PhotoAssessment("нет фото", "", 0.80);
                appendCompetitorBlock(sb, display, competitors.get(i), pa, visionOn, myPhoto, forceWorse[i]);
                chunkForce[display - 1] = forceWorse[i];
            }
            CompetitorClassification cc = classifyChunk(systemPrompt, sb.toString(), chunkForce,
                    competitors.subList(start, end));
            similar.addAll(cc.similar());
            better.addAll(cc.better());
            worse.addAll(cc.worse());
            if (summary.isBlank() && cc.summary() != null && !cc.summary().isBlank()) summary = cc.summary();
            degraded |= cc.degraded();
        }

        CompetitorClassification result =
                new CompetitorClassification(similar, better, worse, summary, degraded);

        // Кэшируем только ПОЛНОЦЕННЫЙ непустой результат (деградированный фолбэк кэшировать нельзя —
        // на следующем прогоне того же набора можно получить настоящую классификацию).
        boolean nonEmpty = !similar.isEmpty() || !better.isEmpty() || !worse.isEmpty();
        if (nonEmpty && !degraded)
            classificationCache.put(cacheKey, new CachedClassification(result, System.currentTimeMillis()));
        return result;
    }

    /** Размер чанка конкурентов на один запрос классификатора (ответ влезает в max_tokens). */
    private static final int CLASSIFY_CHUNK = 12;
    /** Потолок попыток на чанк при обрыве/сбое (НЕ троттл): дальше — нейтральный фолбэк. */
    private static final int CLASSIFY_MAX_ATTEMPTS = 8;
    /** Потолок времени на чанк (мс): даже при затяжном троттле дорожка не висит. */
    private static final long CLASSIFY_BUDGET_MS = 180_000;

    /** Один конкурент в тело запроса классификатора (нумерация — внутри чанка). */
    private void appendCompetitorBlock(StringBuilder sb, int num, PartPrice p, PhotoAssessment pa,
                                       boolean visionOn, PhotoAssessment myPhoto, boolean force) {
        sb.append("\n--- КОНКУРЕНТ ").append(num)
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
            // Сравнение по КАТЕГОРИИ состояния, а не по сырому коэффициенту.
            int rc = conditionRank(pa.condition()), rm = conditionRank(myPhoto.condition());
            if (rc >= 0 && rm >= 0) {
                if (rc > rm)      sb.append("→ состояние по фото лучше моего (категория выше)\n");
                else if (rc < rm) sb.append("→ состояние по фото хуже моего (категория ниже)\n");
                else              sb.append("→ состояние по фото сопоставимо с моим\n");
            }
        }

        if (force) sb.append("[ДРУГАЯ СТОРОНА/ПОЗИЦИЯ или АНАЛОГ против моего ОРИГИНАЛА — вердикт строго worse]\n");
    }

    /**
     * Классификация одного чанка с разделением троттла и обрыва (п.2) и жёстким потолком (п.3):
     *  - троттл (429/«throttled») лечится ожиданием ВНУТРИ callTextAI до общего дедлайна;
     *  - обрыв/битый JSON — это НЕ троттл: повтор того же запроса при temperature=0 обычно бессмыслен,
     *    поэтому ограничен (CLASSIFY_MAX_ATTEMPTS / CLASSIFY_BUDGET_MS);
     *  - потолок исчерпан → нейтральный фолбэк (forceWorse→worse, остальные→similar, degraded=true).
     */
    private CompetitorClassification classifyChunk(String systemPrompt, String user,
                                                   boolean[] forceWorse, List<PartPrice> chunk) {
        long deadline = System.currentTimeMillis() + CLASSIFY_BUDGET_MS;
        for (int attempt = 1; attempt <= CLASSIFY_MAX_ATTEMPTS; attempt++) {
            if (System.currentTimeMillis() > deadline) {
                log.error("Классификация чанка ({}): исчерпан бюджет {}с — нейтральный фолбэк",
                        chunk.size(), CLASSIFY_BUDGET_MS / 1000);
                break;
            }
            String response = callTextAI(systemPrompt, user, deadline);
            if (response == null) {   // шлюз сдался / дедлайн / прерывание потока — повторять бессмысленно
                log.warn("Классификация чанка ({}): шлюз не ответил (попытка {}/{}) — фолбэк",
                        chunk.size(), attempt, CLASSIFY_MAX_ATTEMPTS);
                break;
            }
            CompetitorClassification cc = parseCompetitorClassification(response, forceWorse);
            if (!cc.similar().isEmpty() || !cc.better().isEmpty() || !cc.worse().isEmpty())
                return cc;   // успех
            // Распарсился пустым = обрезанный/битый ответ (НЕ троттл — troттл вернул бы исключение внутри).
            log.warn("Классификация чанка ({}): ответ обрезан/битый (попытка {}/{}) — повтор ограничен",
                    chunk.size(), attempt, CLASSIFY_MAX_ATTEMPTS);
            if (attempt < CLASSIFY_MAX_ATTEMPTS && !sleepMs(textErrorBackoffMs(attempt))) break;  // поток прерван
        }
        return neutralFallback(chunk, forceWorse);
    }

    /** Нейтральный фолбэк чанка: код-правила (forceWorse) → worse, остальные → similar, degraded=true. */
    private CompetitorClassification neutralFallback(List<PartPrice> chunk, boolean[] forceWorse) {
        List<Double> similar = new ArrayList<>(), worse = new ArrayList<>();
        for (int i = 0; i < chunk.size(); i++) {
            BigDecimal pr = chunk.get(i).getPrice();
            if (pr == null) continue;
            double price = pr.doubleValue();
            if (i < forceWorse.length && forceWorse[i]) worse.add(price); else similar.add(price);
        }
        return new CompetitorClassification(similar, List.of(), worse, "", true);
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
                ? "Главное — текст: состояние, дефекты, производитель (оригинал/аналог). Фото — ВТОРИЧНЫЙ сигнал: используй подсказку о КАТЕГОРИИ состояния («лучше/хуже/сопоставимо»), НЕ сам коэффициент. Мелкая разница коэффициента в одной категории — это similar, а не better/worse."
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
                - "better"  = конкурент явно лучше ПО ОПИСАНИЮ: лучше состояние/меньше дефектов, новый vs б/у, оригинал vs аналог. Фото — только подтверждающий сигнал (категория состояния выше), НЕ единственное основание.
                - "similar" = сопоставимое качество: схожее состояние и набор дефектов; разница фото в пределах одной категории — это similar
                - "worse"   = явно хуже: больше дефектов, ниже класс детали, другая сторона/позиция, аналог против оригинала, категория состояния по фото ниже

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

    /** Меньше этого числа сопоставимых конкурентов → данным нельзя доверять (низкая уверенность). */
    private static final int MIN_RELIABLE_COMPETITORS = 3;

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
        String scope = String.format("По Барнаулу (%d позиций)", cityPrices.size());
        return strategyForMarket(merged, market, myPhoto, myCurrentPrice, scope, photoNote);
    }

    /** Сливает классификации двух регионов в одну (similar/better/worse). */
    private CompetitorClassification mergeClassifications(CompetitorClassification a, CompetitorClassification b) {
        List<Double> similar = new ArrayList<>(a.similar()); similar.addAll(b.similar());
        List<Double> better  = new ArrayList<>(a.better());  better.addAll(b.better());
        List<Double> worse   = new ArrayList<>(a.worse());   worse.addAll(b.worse());
        String summary = (a.summary() != null && !a.summary().isBlank()) ? a.summary() : b.summary();
        return new CompetitorClassification(similar, better, worse, summary, a.degraded() || b.degraded());
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
        // ВАЖНО: не подгоняем рекомендацию под ТЕКУЩУЮ цену (её и проверяем — иначе аудит порочно
        // круговой). Оставляем оценку по рынку, но честно помечаем низкой уверенностью.
        // applyBounds уже держит цену в коридоре рынка [0.8·min … max], так что «улёта» не будет.
        int reliableCount = Math.min(stats.count(), priceBasisCount);
        if (reliableCount > 0 && reliableCount < MIN_RELIABLE_COMPETITORS) {
            confidence = "низкая";
            reason += String.format(
                    " [мало сопоставимых данных (%d): оценка по рынку без подгонки к текущей цене, проверить вручную]",
                    reliableCount);
        }

        // Классификация (или её часть) получена нейтральным фолбэком, а не от LLM (исчерпан потолок
        // на обрыве/сбое шлюза) → честно понижаем уверенность и помечаем на ручную проверку.
        if (cls.degraded()) {
            confidence = "низкая";
            reason += " [классификация ИИ получена не для всех конкурентов (сбой шлюза) — нейтральная оценка по рынку, проверить вручную]";
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

    /** Реальные (НЕ троттл) ошибки текста — битый/пустой ответ, сетевой сбой. После стольки подряд сдаёмся
     *  (защита от вечного зависания на настоящем сбое/исчерпании бюджета). Троттл этот счётчик НЕ трогает. */
    private static final int TEXT_MAX_HARD_ERRORS = 6;
    /** Реальные (НЕ троттл) ошибки vision до перехода на нейтральный коэф. Троттл этот счётчик НЕ трогает. */
    private static final int VISION_MAX_CONTENT_ERRORS = 3;

    private String callTextAI(String system, String user, long deadlineMs) {
        // Троттл шлюза («too many concurrent requests», 200 с {"error":...}) НЕ сдаём — это «нас слишком много
        // одновременно», лечится ожиданием. Крутим до вменяемого ответа ЛИБО до общего дедлайна (чтобы дорожка
        // не висела часами при затяжном троттле). Конкурентность придушена общим семафором gatewayLimiter.
        int throttleWait = 0;
        int hardErrors = 0;
        while (true) {
            boolean throttled = false;
            try {
                Map<String, Object> body = Map.of(
                        "model", textModel,
                        "messages", List.of(
                                Map.of("role", "system", "content", system),
                                Map.of("role", "user", "content", user)
                        ),
                        "temperature", 0,   // детерминированная классификация → воспроизводимая цена
                        "max_tokens", 4000  // запас на вердикт+причину по чанку; обрезка ответа исключена
                );
                String json = objectMapper.writeValueAsString(body);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .timeout(Duration.ofSeconds(90))
                        .build();

                String bodyStr;
                int status;
                gatewayLimiter.acquire();   // общий лимит на шлюз (текст+vision)
                try {
                    HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                    bodyStr = resp.body();
                    status = resp.statusCode();
                } finally {
                    gatewayLimiter.release();
                }
                throttled = status == 429 || isThrottled(bodyStr);
                if (throttled) throw new IllegalStateException("троттл шлюза: " + preview(bodyStr));

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

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                if (System.currentTimeMillis() > deadlineMs) {   // общий потолок времени на классификацию (п.3)
                    log.warn("Text AI: дедлайн классификации исчерпан — сдаёмся (фолбэк выше)");
                    return null;
                }
                if (throttled) {
                    log.warn("Text AI троттл (ожидание {}): {}", ++throttleWait, e.getMessage());
                    if (!sleepMs(throttleBackoffMs(throttleWait))) return null;
                } else {
                    if (++hardErrors >= TEXT_MAX_HARD_ERRORS) {
                        log.error("Text AI: {} реальных ошибок подряд — классификация пропущена", hardErrors);
                        return null;
                    }
                    log.warn("Text AI ошибка ({}/{}): {}", hardErrors, TEXT_MAX_HARD_ERRORS, e.getMessage());
                    if (!sleepMs(textErrorBackoffMs(hardErrors))) return null;
                }
            }
        }
    }

    /** Ответ-троттл: шлюз отдаёт его с HTTP 200, поэтому ловим по тексту тела. */
    private static boolean isThrottled(String body) {
        if (body == null) return false;
        String b = body.toLowerCase();
        return b.contains("throttl") || b.contains("too many") || b.contains("rate limit")
                || b.contains("concurrent request") || b.contains("error-code-429");
    }

    private boolean sleepMs(long ms) {
        try { Thread.sleep(ms); return true; }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
    }

    /** Троттл: эскалация ожидания 1.2→2.4→4.8→9.6→15с (далее держим 15с). Ретраи не ограничены — ждём шлюз. */
    private static long throttleBackoffMs(int waitNo) {
        long exp = Math.min(15000L, 1200L * (1L << Math.min(waitNo - 1, 4)));
        return exp + ThreadLocalRandom.current().nextLong(0, 500);   // джиттер — рассинхрон параллельных дорожек
    }

    /** Реальная (не троттл) ошибка: короткий экспоненциальный backoff с потолком 4с. */
    private static long textErrorBackoffMs(int errNo) {
        long exp = Math.min(4000L, 600L * (1L << (errNo - 1)));
        return exp + ThreadLocalRandom.current().nextLong(0, 500);
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

    private CompetitorClassification parseCompetitorClassification(String content, boolean[] forceWorse) {
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
                int idx = 0;
                for (Map<String, Object> comp : comps) {
                    idx++;
                    Object priceObj = comp.get("price");
                    if (priceObj == null) continue;
                    double price   = ((Number) priceObj).doubleValue();
                    String verdict = String.valueOf(comp.getOrDefault("verdict", "similar"));
                    String reason  = String.valueOf(comp.getOrDefault("reason", ""));
                    // Номер конкурента из ответа (иначе порядковый), для override по forceWorse[]
                    int n = (comp.get("n") instanceof Number num) ? num.intValue() : idx;
                    if (forceWorse != null && n >= 1 && n <= forceWorse.length && forceWorse[n - 1]
                            && !"worse".equals(verdict)) {
                        log.debug("Конкурент {}₽: вердикт {} → worse (детерминированное правило)", (long) price, verdict);
                        verdict = "worse";
                    }
                    log.debug("Конкурент {}₽ → {} | {}", (long) price, verdict, reason);
                    switch (verdict) {
                        case "better"  -> better.add(price);
                        case "worse"   -> worse.add(price);
                        default        -> similar.add(price);
                    }
                }
                String summary = (String) m.getOrDefault("summary", "");
                return new CompetitorClassification(similar, better, worse, summary, false);
            }

            // Обратная совместимость со старым форматом {similar:[], better:[], worse:[]}
            return new CompetitorClassification(
                    toDoubleList(m.get("similar")),
                    toDoubleList(m.get("better")),
                    toDoubleList(m.get("worse")),
                    (String) m.getOrDefault("comment", ""),
                    false
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

    // ==================== ОПРЕДЕЛЕНИЕ АНАЛОГА / СТОРОНЫ / ПОЗИЦИИ ====================

    /** Бренды/артикулы неоригинальных аналогов (lowercase-токены). */
    private static final String[] ANALOG_TOKENS = {
            "febest", "masterkit", "master kit", "master-kit", "lynx", "nagamochi",
            "masuma", "narichin", "trust auto", "trustauto", "sat-st", " sat ",
            "tabc", "tabp", "ta-tab"
    };

    /** Признак неоригинала по тексту названия/описания. */
    private boolean isAnalog(String text) {
        if (text == null) return false;
        String t = text.toLowerCase();
        for (String tok : ANALOG_TOKENS) if (t.contains(tok)) return true;
        return false;
    }

    /** Признак контрактной детали (снята с контрактного авто) по тексту состояния/названия/описания. */
    private boolean isContract(String text) {
        return text != null && text.toLowerCase().contains("контракт");
    }

    /** Конкурент контрактный, если в названии/описании есть «контракт» (название есть всегда — со страницы поиска). */
    private boolean compContract(PartPrice p) {
        return isContract((p.getTitle() == null ? "" : p.getTitle()) + " "
                + (p.getDescription() == null ? "" : p.getDescription()));
    }

    /** Только контрактные конкуренты из списка. */
    private List<PartPrice> filterContract(List<PartPrice> competitors) {
        List<PartPrice> out = new ArrayList<>();
        for (PartPrice p : competitors) if (compContract(p)) out.add(p);
        return out;
    }

    /** Категория состояния по фото: отличное/хорошее=2, удовлетворительное=1, плохое=0, неизвестно=-1. */
    private int conditionRank(String condition) {
        if (condition == null) return -1;
        String c = condition.toLowerCase();
        if (c.contains("отличн") || c.contains("хорош")) return 2;
        if (c.contains("удовлетвор")) return 1;
        if (c.contains("плох")) return 0;
        return -1;
    }

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

    // degraded=true — классификация (или её часть) получена нейтральным фолбэком, а не от LLM
    // (исчерпан потолок попыток на обрыве/сбое шлюза). Понижает уверенность и ставит флаг ручной проверки.
    private record CompetitorClassification(List<Double> similar, List<Double> better, List<Double> worse,
                                            String summary, boolean degraded) {
        static CompetitorClassification empty() {
            return new CompetitorClassification(List.of(), List.of(), List.of(), "", false);
        }
    }

    private record MarketStats(BigDecimal min, BigDecimal median, BigDecimal max, BigDecimal avg, int count) {}
}
