package ru.retail.service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Профили анализа (тип запчасти) из application.yml. Каждый профиль задаёт:
 *   • keyword    — ключевое слово каталога/выдачи (берём только строки, где название содержит его);
 *   • stopWords  — стоп-слова: объявления/строки, содержащие любое из них, НЕ считаются целевой деталью
 *                  (компоненты, ремкомплекты, смежные детали с тем же OEM).
 * Так один и тот же движок работает для суппортов, фар, стартеров и т.д. — правкой конфига, без кода.
 */
@Component
@ConfigurationProperties(prefix = "analysis")
@Data
public class AnalysisProfiles {

    private List<Profile> profiles = new ArrayList<>();

    @Data
    public static class Profile {
        private String name;
        private String keyword;                 // фильтр каталога из xlsx (колонка «Запчасть») и drom (query — не исп.)
        private List<String> stopWords = new ArrayList<>();
        private List<Integer> bazonPartnameIds = new ArrayList<>();  // фильтр каталога из Bazon (partname_id)
    }

    /**
     * Имена профилей для выпадающего списка (UI/веб) — по алфавиту: типов деталей больше сотни,
     * и порядок из конфига искать в них не помогает. На выбор профиля сортировка не влияет:
     * {@link #byName(String)} ищет по имени, а дефолтом остаётся ПЕРВЫЙ профиль конфига.
     */
    public List<String> names() {
        return profiles.stream()
                .map(Profile::getName)
                .sorted(java.text.Collator.getInstance(new java.util.Locale("ru", "RU")))
                .toList();
    }

    /** Профиль по имени; если не найден/имя пустое — первый (дефолтный). Пустой список → null. */
    public Profile byName(String name) {
        if (profiles.isEmpty()) return null;
        if (name != null && !name.isBlank())
            for (Profile p : profiles)
                if (name.equalsIgnoreCase(p.getName())) return p;
        return profiles.get(0);
    }
}
