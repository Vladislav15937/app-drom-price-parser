package ru.retail.service.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Загрузка каталога запчастей (экспорт из учётной системы). Поддерживаются два формата:
 *   • CSV — Windows-1251, разделитель «;», значения в кавычках;
 *   • XLSX — книга Excel (первый лист), читается через Apache POI.
 * В обоих случаях ПЕРВАЯ строка — именованная шапка; колонки ищутся по ИМЕНИ (устойчиво к смене
 * порядка), а не по фиксированному индексу. Формат определяется по сигнатуре файла (ZIP «PK» → xlsx).
 *
 * Основной вход — {@link #loadOlderThan6Months}: возвращает только позиции, которые по колонке
 * «Создан» старше 6 месяцев от текущей даты (то, что «залежалось» и требует проверки рынка).
 */
@Slf4j
public final class CatalogLoader {

    private CatalogLoader() {}

    /** Одна позиция каталога после разбора шапки. */
    public record CatalogItem(String oem, String name, String brand, String model,
                              BigDecimal price, LocalDateTime created, String itemNumber,
                              LocalDateTime priceChanged) {}

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter D  = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Сколько ждать после НАШЕЙ автопереоценки, прежде чем позиция снова попадёт в отчёт. */
    private static final int OWN_REPRICE_COOLDOWN_MONTHS = 1;

    public static List<CatalogItem> loadOlderThan6Months(File file, String keyword, List<String> stopWords) throws Exception {
        return loadOlderThan6Months(Files.readAllBytes(file.toPath()), keyword, stopWords, null);
    }

    public static List<CatalogItem> loadOlderThan6Months(File file, String keyword, List<String> stopWords,
                                                         String apiUser) throws Exception {
        return loadOlderThan6Months(Files.readAllBytes(file.toPath()), keyword, stopWords, apiUser);
    }

    public static List<CatalogItem> loadOlderThan6Months(byte[] bytes, String keyword, List<String> stopWords) {
        return loadOlderThan6Months(bytes, keyword, stopWords, null);
    }

    /**
     * Разбирает CSV/XLSX и оставляет только позиции целевого типа (название содержит {@code keyword}
     * и НЕ содержит ни одного из {@code stopWords}), у которых «Создан» старше 6 месяцев.
     * Нераспарсенные даты и нецелевые строки пропускаются (с логом статистики).
     *
     * @param apiUser логин API-пользователя Bazon ({@code bazon.login}), которым парсер пишет цены.
     *                Позиция, чью цену последним менял ОН сам и уже больше
     *                {@link #OWN_REPRICE_COOLDOWN_MONTHS} мес назад, снова идёт в переоценку: ждать
     *                полные 6 месяцев после нашего же −10% незачем. Чужие (людские) правки цены
     *                по-прежнему держат позицию вне отчёта 6 месяцев. {@code null} → правило выключено.
     */
    public static List<CatalogItem> loadOlderThan6Months(byte[] bytes, String keyword, List<String> stopWords,
                                                         String apiUser) {
        String kw = keyword == null ? "" : keyword.toLowerCase();
        List<String> stops = stopWords == null ? List.of() : stopWords;
        List<String[]> rows = looksLikeXlsx(bytes) ? parseXlsx(bytes) : parseCsv(bytes);
        if (rows.isEmpty()) return List.of();

        Map<String, Integer> h = headerIndex(rows.get(0));
        int iCreated = col(h, "Создан");
        int iOem     = col(h, "Номер производителя");
        int iName    = col(h, "Наименование");
        int iPartType = col(h, "Запчасть");
        int iBrand   = col(h, "Марка");
        int iModel   = col(h, "Модель");
        int iPrice   = col(h, "Цена");
        // Необязательные колонки — при их наличии повторяем фильтр пути Bazon:
        // только контрактные детали со свободным остатком на «Ткацкой» > 0 (зарезервированные продать нельзя).
        int iState   = col(h, "Состояние");
        int iFree    = col(h, "Ткацкая (свободно)");
        int iItem    = col(h, "Номер товара");        // внутренний id Bazon
        int iPriceCh = col(h, "Цена изменена в");     // дата последней смены цены — берём только «застоявшиеся» (>6 мес)
        int iPriceBy = col(h, "Кто изменил цену");    // автор последней смены цены: наш api-пользователь или человек

        if (iCreated < 0 || iOem < 0) {
            log.error("Каталог: не найдены обязательные колонки «Создан»/«Номер производителя». Шапка: {}", h.keySet());
            return List.of();
        }

        LocalDateTime threshold = LocalDateTime.now().minusMonths(6);
        // Порог для позиций, чью цену последним менял САМ парсер: после нашего −10% ждём не 6 мес, а месяц.
        LocalDateTime ownThreshold = LocalDateTime.now().minusMonths(OWN_REPRICE_COOLDOWN_MONTHS);
        String api = apiUser == null ? "" : apiUser.trim().toLowerCase();
        List<CatalogItem> out = new ArrayList<>();
        int skippedFresh = 0, skippedPart = 0, skippedDate = 0, skippedOem = 0, skippedState = 0, skippedStock = 0, skippedPriceFresh = 0;
        int takenOwnReprice = 0;   // взято по «нашей» ветке: цену менял парсер больше месяца назад

        for (int r = 1; r < rows.size(); r++) {
            String[] row = rows.get(r);
            String oem = get(row, iOem);
            if (oem.isBlank()) { skippedOem++; continue; }

            String name = get(row, iName);
            String partType = get(row, iPartType);
            if (!matchesType(name, partType, kw, stops)) { skippedPart++; continue; }

            LocalDateTime created = parseDate(get(row, iCreated));
            if (created == null) { skippedDate++; continue; }
            if (!created.isBefore(threshold)) { skippedFresh++; continue; }   // свежее 6 мес — пропуск

            // Контрактные + свободный остаток на «Ткацкой» > 0 (как в пути Bazon), если колонки есть в файле.
            if (iState >= 0 && !"контракт".equalsIgnoreCase(get(row, iState).trim())) { skippedState++; continue; }
            if (iFree >= 0 && parseIntSafe(get(row, iFree)) <= 0) { skippedStock++; continue; }

            // Цену не меняли > 6 мес (застоялась). Если колонка есть и дата свежее порога — пропуск.
            // Пустая/непарсимая «Цена изменена в» = цену не трогали → берём (это и есть застой).
            // Исключение: последним цену менял САМ парсер (api-пользователь Bazon) — тогда порог месяц,
            // чтобы наши же позиции возвращались в автопереоценку, не дожидаясь полугода.
            LocalDateTime priceChanged = iPriceCh >= 0 ? parseDate(get(row, iPriceCh)) : null;
            boolean ownPriceEdit = !api.isEmpty() && iPriceBy >= 0
                    && get(row, iPriceBy).toLowerCase().contains(api);
            LocalDateTime priceLimit = ownPriceEdit ? ownThreshold : threshold;
            if (iPriceCh >= 0 && priceChanged != null && !priceChanged.isBefore(priceLimit)) { skippedPriceFresh++; continue; }
            if (ownPriceEdit && priceChanged != null && priceChanged.isBefore(ownThreshold)
                    && !priceChanged.isBefore(threshold)) takenOwnReprice++;   // взято именно по «нашей» ветке

            out.add(new CatalogItem(
                    oem,
                    name.isBlank() ? partType : name,
                    get(row, iBrand),
                    get(row, iModel),
                    parsePrice(get(row, iPrice)),
                    created,
                    get(row, iItem),
                    priceChanged));
        }

        log.info("Каталог [{}]: отобрано {} (старше 6 мес; из них по нашей автопереоценке >{} мес назад: {}). "
                        + "Пропущено: свежих {}, нецелевых {}, без даты {}, без OEM {}, не контракт {}, без свободного остатка {}, цена менялась <6мес {}.",
                keyword, out.size(), OWN_REPRICE_COOLDOWN_MONTHS, takenOwnReprice,
                skippedFresh, skippedPart, skippedDate, skippedOem, skippedState, skippedStock, skippedPriceFresh);
        return out;
    }

    /** Целое из строки («0», «1», пусто) — 0 при неразборе. */
    private static int parseIntSafe(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s.trim().replaceAll("[^\\d-]", "")); } catch (Exception e) { return 0; }
    }

    /** Целевая деталь: название/тип содержит keyword и не содержит ни одного стоп-слова. */
    private static boolean matchesType(String name, String partType, String keyword, List<String> stopWords) {
        String s = (name + " " + partType).toLowerCase();
        if (keyword.isBlank() || !s.contains(keyword)) return false;
        for (String w : stopWords) if (!w.isBlank() && s.contains(w.toLowerCase())) return false;
        return true;
    }

    private static LocalDateTime parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        try { return LocalDateTime.parse(t, DT); } catch (Exception ignored) {}
        try { return LocalDate.parse(t, D).atStartOfDay(); } catch (Exception ignored) {}
        return null;
    }

    private static BigDecimal parsePrice(String s) {
        try { return new BigDecimal(s.replaceAll("[^\\d.]", "")); }
        catch (Exception e) { return BigDecimal.ZERO; }
    }

    /** header → индекс колонки (по имени, без учёта регистра/пробелов по краям). */
    private static Map<String, Integer> headerIndex(String[] header) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            String key = header[i] == null ? "" : header[i].trim().toLowerCase();
            map.putIfAbsent(key, i);
        }
        return map;
    }

    private static int col(Map<String, Integer> h, String name) {
        return h.getOrDefault(name.toLowerCase(), -1);
    }

    private static String get(String[] row, int i) {
        if (row == null || i < 0 || i >= row.length || row[i] == null) return "";
        return row[i].trim();
    }

    /** ZIP-сигнатура «PK» → это .xlsx (Office Open XML), иначе считаем CSV. */
    private static boolean looksLikeXlsx(byte[] b) {
        return b != null && b.length >= 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4;
    }

    /**
     * XLSX-парсер через POI: первый лист, значения ячеек как текст (даты/числа — в исходном отображении).
     * Возвращает ВСЕ строки, включая шапку (row 0), выровненные по числу колонок шапки.
     */
    public static List<String[]> parseXlsx(byte[] bytes) {
        List<String[]> result = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            int width = 0;
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow != null) width = headerRow.getLastCellNum();
            for (Row row : sheet) {
                int cols = Math.max(width, row.getLastCellNum());
                if (cols <= 0) continue;
                String[] arr = new String[cols];
                for (int c = 0; c < cols; c++) {
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    arr[c] = cell == null ? "" : fmt.formatCellValue(cell).trim();
                }
                boolean allEmpty = true;
                for (String s : arr) if (!s.isEmpty()) { allEmpty = false; break; }
                if (!allEmpty) result.add(arr);
            }
        } catch (Exception e) {
            log.error("Каталог: не удалось прочитать XLSX: {}", e.getMessage());
        }
        return result;
    }

    /**
     * CSV-парсер: Windows-1251, кавычки, «;». Возвращает ВСЕ строки, включая шапку (row 0).
     * Переводы строк внутри кавычек поддерживаются.
     */
    public static List<String[]> parseCsv(byte[] bytes) {
        List<String[]> result = new ArrayList<>();
        String content = new String(bytes, Charset.forName("Windows-1251"));
        List<String> row = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ';' && !inQuotes) {
                row.add(sb.toString().trim()); sb.setLength(0);
            } else if (c == '\n' && !inQuotes) {
                row.add(sb.toString().trim()); sb.setLength(0);
                if (!row.stream().allMatch(String::isEmpty)) result.add(row.toArray(new String[0]));
                row = new ArrayList<>();
            } else if (c != '\r') {
                sb.append(c);
            }
        }
        if (sb.length() > 0) row.add(sb.toString().trim());
        if (!row.isEmpty() && !row.stream().allMatch(String::isEmpty)) result.add(row.toArray(new String[0]));
        return result;
    }
}
