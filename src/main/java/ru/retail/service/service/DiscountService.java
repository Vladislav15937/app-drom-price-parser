package ru.retail.service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Автодисконт (предложение заказчика №11): позициям, которые долго не продаются и при этом
 * дефицитны, скидка в пики спроса — чт 5 %, пт 10 %, сб 10 %, вс 5 % — с откатом к базовой цене
 * в остальные дни.
 *
 * <p>Ключевое здесь — <b>журнал базовых цен</b> ({@code autodiscount-state.json}). Скидка всегда
 * считается от базовой цены, а не от текущей: иначе повторные запуски множили бы её саму на себя
 * (0.9 × 0.9 × …) и цена уползала бы вниз без возврата. Журнал же позволяет и откатиться —
 * в день без скидки позиции возвращается ровно её базовая цена.
 *
 * <p>Расписание и порог возраста настраиваются в application.yml; критерии дефицита задаёт
 * клиент при расчёте. Записи в Bazon этот класс не делает — только считает план.
 */
@Slf4j
@Service
public class DiscountService {

    /** Что известно о позиции: с какой цены начинали и какая ступень скидки стоит сейчас. */
    public record Entry(long basePrice, int percent, String appliedAt) {}

    /** Строка плана: сколько стоит сейчас, сколько будет и почему. */
    public record DiscountPlan(int id, String oem, long basePrice, long oldPrice, long newPrice,
                               int percent, String note) {
        public boolean applicable() { return newPrice > 0 && newPrice != oldPrice; }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path stateFile;
    private final Map<DayOfWeek, Integer> schedule;

    public DiscountService(
            @Value("${discount.state-file:autodiscount-state.json}") String stateFile,
            @Value("${discount.thursday:5}") int thu,
            @Value("${discount.friday:10}") int fri,
            @Value("${discount.saturday:10}") int sat,
            @Value("${discount.sunday:5}") int sun) {
        this.stateFile = Path.of(stateFile);
        this.schedule = Map.of(DayOfWeek.THURSDAY, thu, DayOfWeek.FRIDAY, fri,
                DayOfWeek.SATURDAY, sat, DayOfWeek.SUNDAY, sun);
    }

    /** Скидка на сегодня по расписанию; вне пиков — 0 %, то есть возврат к базовой цене. */
    public int percentToday() {
        return schedule.getOrDefault(LocalDate.now().getDayOfWeek(), 0);
    }

    /**
     * План по одной позиции. {@code currentPrice} — цена из каталога (что сейчас в Bazon).
     * Базой считается запомненная цена, а если позиции в журнале ещё нет — текущая.
     */
    public DiscountPlan plan(int id, String oem, long currentPrice, int percent) {
        Map<Integer, Entry> state = load();
        Entry known = state.get(id);
        long base = known != null ? known.basePrice() : currentPrice;
        if (base <= 0) return new DiscountPlan(id, oem, base, currentPrice, 0, percent, "нет цены");
        long target = percent == 0 ? base : roundTo10(Math.round(base * (100 - percent) / 100.0));
        String note = percent == 0
                ? (target == currentPrice ? "уже базовая" : "откат к базовой")
                : (known != null && known.percent() == percent && target == currentPrice
                    ? "скидка уже стоит" : "скидка " + percent + "%");
        return new DiscountPlan(id, oem, base, currentPrice, target, percent, note);
    }

    /** Фиксирует применённые цены: база не меняется, ступень — текущая. */
    public synchronized void remember(java.util.List<DiscountPlan> applied) {
        Map<Integer, Entry> state = load();
        String today = LocalDate.now().toString();
        for (DiscountPlan p : applied) state.put(p.id(), new Entry(p.basePrice(), p.percent(), today));
        save(state);
    }

    public static long roundTo10(long v) { return Math.round(v / 10.0) * 10; }

    // ── журнал ──

    public synchronized Map<Integer, Entry> load() {
        Map<Integer, Entry> out = new LinkedHashMap<>();
        try {
            if (!Files.exists(stateFile)) return out;
            JsonNode root = mapper.readTree(Files.readString(stateFile));
            root.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                out.put(Integer.parseInt(e.getKey()), new Entry(
                        v.path("basePrice").asLong(0), v.path("percent").asInt(0), v.path("appliedAt").asText("")));
            });
        } catch (Exception e) {
            log.warn("Автодисконт: не прочитать журнал {}: {}", stateFile, e.getMessage());
        }
        return out;
    }

    private void save(Map<Integer, Entry> state) {
        try {
            ObjectNode root = mapper.createObjectNode();
            state.forEach((id, e) -> {
                ObjectNode n = root.putObject(String.valueOf(id));
                n.put("basePrice", e.basePrice());
                n.put("percent", e.percent());
                n.put("appliedAt", e.appliedAt());
            });
            Files.writeString(stateFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (Exception e) {
            log.warn("Автодисконт: не сохранить журнал {}: {}", stateFile, e.getMessage());
        }
    }
}
