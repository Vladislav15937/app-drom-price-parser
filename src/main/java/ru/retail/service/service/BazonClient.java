package ru.retail.service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.retail.service.service.CatalogLoader.CatalogItem;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Клиент учётной системы Bazon (external-api v1.0) — источник каталога наших запчастей.
 *
 * Авторизация: логин/пароль external-app → пара токенов (AT/RT) через {@code /login/user};
 * access-токен живёт ~4 дня, поэтому реализован авто-рефреш ({@code /refresh/user} по RT, при
 * истечении/401 — перелогин). Пара токенов персистится в token-file (по умолчанию {@code bazon-token.json}):
 * при старте читается с диска и обновляется по RT, поэтому перелогин (и риск капчи) на каждом рестарте не нужен.
 *
 * Каталог тянется методом {@code getPartsWithChars} с постраничной выборкой и фильтрами:
 * состояние «contract», нужный тип детали (partname_id из профиля), свободный остаток на складе
 * «Ткацкая» (amount − reserved > 0) и дата создания старше порога.
 */
@Slf4j
@Service
public class BazonClient {

    private final String authUrl;
    private final String apiUrl;
    private final String login;
    private final String password;
    private final int storageId;
    private final String tokenFile;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private static final int PAGE = 500;                 // limit getPartsWithChars (макс. 500)
    private static final long RATE_LIMIT_MS = 1100;      // ~1 rps/метод (просьба Bazon)

    // ── токены (в памяти) ──
    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile long expiresAtMs;   // когда access-токен истекает (с запасом)

    // ── кэш каталога (в памяти) ──
    // Серверной фильтрации по типу/состоянию в API нет → полный скан «старой» части каталога дорогой (~мин).
    // Кэшируем ВСЕ контрактные старше порога (любой тип) за один скан; фильтр по типу профиля потом мгновенный.
    private record CachedPart(int partnameId, CatalogItem item) {}
    private volatile List<CachedPart> cache;
    private volatile long cacheDay = -1;   // epochDay порога, на котором собран кэш

    public BazonClient(
            @Value("${bazon.auth-url}") String authUrl,
            @Value("${bazon.api-url}") String apiUrl,
            @Value("${bazon.login}") String login,
            @Value("${bazon.password}") String password,
            @Value("${bazon.storage-id:1}") int storageId,
            @Value("${bazon.token-file:bazon-token.json}") String tokenFile) {
        this.authUrl = trimSlash(authUrl);
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl : apiUrl + "/";
        this.login = login;
        this.password = password;
        this.storageId = storageId;
        this.tokenFile = tokenFile;
    }

    /** Логин API-пользователя: им подписаны все цены, изменённые парсером («Кто изменил цену» в экспорте). */
    public String login() { return login; }

    // ==================== ПУБЛИЧНЫЙ МЕТОД ====================

    /**
     * Наши контрактные запчасти нужного типа старше {@code olderThan}, свободные на складе «Ткацкая».
     * @param partnameIds типы детали (partname_id) из профиля; пусто → берём все типы
     * @param olderThan   порог даты создания (берём созданные ДО него, т.е. «старше 6 мес»)
     */
    public synchronized List<CatalogItem> fetchContractParts(List<Integer> partnameIds, LocalDateTime olderThan) {
        long day = olderThan.toLocalDate().toEpochDay();
        if (cache == null || cacheDay != day) {
            cache = loadAllContract(olderThan);
            cacheDay = day;
        }
        List<CatalogItem> out = new ArrayList<>();
        for (CachedPart c : cache)
            if (partnameIds.isEmpty() || partnameIds.contains(c.partnameId())) out.add(c.item());
        log.info("Bazon: типы {} → {} из кэша ({} контрактных всего)", partnameIds, out.size(), cache.size());
        return out;
    }

    // ==================== ИЗМЕНЕНИЕ ЦЕНЫ (external-api v1.0 `setProducts`) ====================
    // v1.0 умеет запись: POST setProducts {"products":[{"id":..,"fields":[{"field_name":"price","action":"set","value":".."}]}]}.
    // Массив products за один запрос (батч). Результаты ПОЗИЦИОННЫЕ: {action:"updated"|"error", error, product{price,...}}.
    // Поле розничной цены — "price" (проверено вживую на товаре 63131). НЕОБРАТИМО — только после подтверждения.

    public record PriceUpdate(int id, long newPrice) {}
    public record PriceResult(int id, boolean ok, long appliedPrice, String error) {}

    private static final int SET_BATCH = 100;   // товаров на один setProducts-запрос

    /** Батч-установка розничной цены. Отправляет по {@link #SET_BATCH} товаров за запрос; результат — по каждому id. */
    public synchronized List<PriceResult> setPrices(List<PriceUpdate> updates) {
        List<PriceResult> out = new ArrayList<>();
        for (int start = 0; start < updates.size(); start += SET_BATCH) {
            List<PriceUpdate> chunk = updates.subList(start, Math.min(updates.size(), start + SET_BATCH));
            out.addAll(setPricesChunk(chunk));
            if (start + SET_BATCH < updates.size()) sleep(RATE_LIMIT_MS);
        }
        return out;
    }

    private List<PriceResult> setPricesChunk(List<PriceUpdate> chunk) {
        StringBuilder prods = new StringBuilder();
        for (PriceUpdate u : chunk) {
            if (prods.length() > 0) prods.append(',');
            prods.append("{\"id\":").append(u.id())
                 .append(",\"fields\":[{\"field_name\":\"price\",\"action\":\"set\",\"value\":\"").append(u.newPrice()).append("\"}]}");
        }
        String body = "{\"request\":[{\"method\":\"setProducts\",\"params\":{\"products\":[" + prods + "]}}]}";
        JsonNode results = call(body).path("response").path(0).path("result").path("results");
        List<PriceResult> out = new ArrayList<>();
        for (int i = 0; i < chunk.size(); i++) {
            PriceUpdate u = chunk.get(i);
            JsonNode r = results.path(i);   // порядок результатов = порядку запроса
            boolean ok = "updated".equals(r.path("action").asText());
            long applied = r.path("product").path("price").asLong(-1);
            if (!ok) log.warn("Bazon setProducts id={} → {}", u.id(), preview(r.toString()));
            out.add(new PriceResult(u.id(), ok, applied, ok ? "" : r.path("error").asText("нет результата")));
        }
        return out;
    }

    /** Полный скан «старой» части каталога: ВСЕ контрактные старше порога, свободные на «Ткацкой» (любой тип). */
    private List<CachedPart> loadAllContract(LocalDateTime olderThan) {
        List<CachedPart> out = new ArrayList<>();
        int offset = 0, found = Integer.MAX_VALUE, scanned = 0;
        boolean reachedBoundary = false;

        // ВАЖНО: серверный фильтр даты (min/max_created_at) в alpha-версии ломает API (HTTP 500), а фильтров
        // по типу/состоянию нет вовсе. Обход: order=asc (старые первыми) + стоп, дойдя до свежих (< порога).
        while (offset < found && !reachedBoundary) {
            JsonNode result = callGetParts(offset);
            found = result.path("found").asInt(found);
            JsonNode parts = result.path("products_with_chars");
            if (!parts.isArray() || parts.isEmpty()) break;

            for (JsonNode p : parts) {
                scanned++;
                LocalDateTime created = parseCreated(p);
                if (created != null && !created.isBefore(olderThan)) { reachedBoundary = true; break; }  // дошли до свежих — стоп
                if (!"contract".equalsIgnoreCase(p.path("used").asText(""))) continue;
                if (freeStock(p) <= 0) continue;   // нет свободного остатка на «Ткацкой»
                out.add(new CachedPart(p.path("partname_id").asInt(-1), toItem(p, created)));
            }
            offset += PAGE;
            if (offset % (PAGE * 10) == 0)
                log.info("Bazon: просмотрено {} из ~{}, контрактных {}…", scanned, found, out.size());
            if (!reachedBoundary) sleep(RATE_LIMIT_MS);
        }
        log.info("Bazon: полный скан старше {} → контрактных {} (просмотрено {})",
                olderThan.toLocalDate(), out.size(), scanned);
        return out;
    }

    private JsonNode callGetParts(int offset) {
        // offset=0 API считает невалидным (баг: 0 = «пусто») → на первой странице параметр опускаем.
        String off = offset > 0 ? ",\"offset\":" + offset : "";
        String body = "{\"request\":[{\"method\":\"getPartsWithChars\",\"params\":" +
                "{\"order\":\"asc\",\"limit\":" + PAGE + off + "}}]}";
        JsonNode resp = call(body);
        JsonNode r0 = resp.path("response").path(0).path("result");
        if (r0.has("error"))
            throw new IllegalStateException("Bazon getPartsWithChars error: " + r0.path("error").asText());
        return r0;
    }

    private LocalDateTime parseCreated(JsonNode p) {
        try { return OffsetDateTime.parse(p.path("created_at").asText()).toLocalDateTime(); }
        catch (Exception e) { return null; }
    }

    /** Свободный остаток на складе «Ткацкая» = amount − reserved по storage_id. */
    private int freeStock(JsonNode part) {
        for (JsonNode s : part.path("by_storages")) {
            if (s.path("storage_id").asInt(-1) == storageId)
                return s.path("amount").asInt(0) - s.path("reserved").asInt(0);
        }
        return 0;
    }

    private CatalogItem toItem(JsonNode p, LocalDateTime created) {
        BigDecimal price;
        try { price = new BigDecimal(p.path("price").asText("0")); } catch (Exception e) { price = BigDecimal.ZERO; }
        if (created == null) created = LocalDateTime.now();
        return new CatalogItem(
                p.path("mfrnumber").asText(""),
                p.path("name").asText(""),
                p.path("carsrc_mark").asText(""),
                p.path("carsrc_model").asText(""),
                price,
                created,
                p.path("id").asText(""),    // «Номер товара» (внутренний id Bazon)
                null);                      // «Цена изменена в» — в external-api Bazon поля нет (только файл-экспорт)
    }

    // ==================== HTTP + ТОКЕНЫ ====================

    /** POST batch-запроса к api-url с Bearer; при 401 — один рефреш/перелогин и повтор. */
    private synchronized JsonNode call(String jsonBody) {
        ensureToken();
        HttpResponse<String> resp = post(apiUrl, jsonBody, accessToken);
        if (resp.statusCode() == 401) {
            log.info("Bazon: 401 — обновляю токен и повторяю");
            forceRefresh();
            resp = post(apiUrl, jsonBody, accessToken);
        }
        if (resp.statusCode() != 200)
            throw new IllegalStateException("Bazon API HTTP " + resp.statusCode() + ": " + preview(resp.body()));
        try { return mapper.readTree(resp.body()); }
        catch (Exception e) { throw new IllegalStateException("Bazon API: битый JSON: " + preview(resp.body())); }
    }

    private void ensureToken() {
        if (accessToken == null) loadTokenFromFile();                       // после рестарта — берём с диска
        if (accessToken != null && System.currentTimeMillis() < expiresAtMs) return;
        if (refreshToken != null) {                                          // рефреш вместо логина (не триггерит капчу)
            try { refresh(); return; }
            catch (Exception e) { log.warn("Bazon: рефреш не удался ({}), логинюсь заново", e.getMessage()); }
        }
        loginUser();
    }

    private void forceRefresh() {
        try { if (refreshToken != null) { refresh(); return; } } catch (Exception ignored) {}
        loginUser();
    }

    private void loginUser() {
        String body = String.format("{\"login\":%s,\"password\":%s}", jsonStr(login), jsonStr(password));
        applyTokens(postJson(authUrl + "/login/user", body), "login");
    }

    private void refresh() {
        String body = String.format("{\"RT\":%s}", jsonStr(refreshToken));
        applyTokens(postJson(authUrl + "/refresh/user", body), "refresh");
    }

    private void applyTokens(JsonNode j, String what) {
        if ("need_captcha".equals(j.path("error").asText()))
            throw new IllegalStateException("Bazon " + what + ": требуется капча (частые логины). Войдите в браузере " +
                    "и подложите свежий токен в " + tokenFile + " {\"AT\":\"…\",\"RT\":\"…\"} — приложение будет его рефрешить, не перелогиниваясь.");
        if (!j.path("success").asBoolean(false) || j.path("AT").asText("").isEmpty())
            throw new IllegalStateException("Bazon " + what + " failed: " + preview(j.toString()));
        accessToken = j.path("AT").asText();
        refreshToken = j.path("RT").asText(refreshToken);
        long ttl = j.path("access_token_ttl").asLong(3600);
        expiresAtMs = System.currentTimeMillis() + Math.max(0, (ttl - 120)) * 1000L;  // запас 2 мин
        saveTokenToFile();
        log.info("Bazon: токен обновлён ({}), ttl={}с", what, ttl);
    }

    // ── персистентность токена (чтобы не логиниться на каждом старте → не триггерить капчу) ──

    private boolean loadTokenFromFile() {
        try {
            Path f = Path.of(tokenFile);
            if (!Files.exists(f)) return false;
            JsonNode j = mapper.readTree(Files.readString(f));
            accessToken = emptyToNull(j.path("AT").asText(""));
            refreshToken = emptyToNull(j.path("RT").asText(""));
            expiresAtMs = j.path("exp").asLong(0);
            if (expiresAtMs <= 0 && accessToken != null) expiresAtMs = jwtExpMs(accessToken);  // из самого JWT
            if (accessToken != null) log.info("Bazon: токен загружен из {}", tokenFile);
            return accessToken != null;
        } catch (Exception e) { log.warn("Bazon: не прочитать токен-файл {}: {}", tokenFile, e.getMessage()); return false; }
    }

    private void saveTokenToFile() {
        try {
            Files.writeString(Path.of(tokenFile), mapper.writeValueAsString(Map.of(
                    "AT", accessToken == null ? "" : accessToken,
                    "RT", refreshToken == null ? "" : refreshToken,
                    "exp", expiresAtMs)));
        } catch (Exception e) { log.warn("Bazon: не сохранить токен-файл {}: {}", tokenFile, e.getMessage()); }
    }

    /** Срок жизни JWT из его payload (claim exp), с запасом 2 мин. 0 — если не распарсить. */
    private long jwtExpMs(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            JsonNode p = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            long exp = p.path("exp").asLong(0);
            return exp > 0 ? exp * 1000L - 120_000 : 0;
        } catch (Exception e) { return 0; }
    }

    private static String emptyToNull(String s) { return s == null || s.isEmpty() ? null : s; }

    private JsonNode postJson(String url, String body) {
        HttpResponse<String> r = post(url, body, null);
        if (r.statusCode() != 200)
            throw new IllegalStateException("Bazon auth HTTP " + r.statusCode() + ": " + preview(r.body()));
        try { return mapper.readTree(r.body()); }
        catch (Exception e) { throw new IllegalStateException("Bazon auth: битый JSON: " + preview(r.body())); }
    }

    private HttpResponse<String> post(String url, String body, String bearer) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8));
            if (bearer != null) b.header("Authorization", "Bearer " + bearer);
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Bazon: сеть недоступна (" + url + "): " + e.getMessage(), e);
        }
    }

    // ==================== УТИЛИТЫ ====================

    private static String trimSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }
    private String jsonStr(String s) { try { return mapper.writeValueAsString(s); } catch (Exception e) { return "\"\""; } }
    private static String preview(String s) { return s == null ? "" : s.substring(0, Math.min(300, s.length())); }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
