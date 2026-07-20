package ru.retail.service.ui;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import ru.retail.service.dto.AvailabilityResult;

import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.nio.file.Path;

/**
 * Пишет отчёт анализа наличия/плотности рынка в .xlsx, строка за строкой, с пересохранением файла
 * после КАЖДОЙ детали (данные не теряются при обрыве прогона).
 * Цвет строки по разреженности рынка:
 *   красный — Барнаул<4 и Сибирь<10; фиолетовый — Сибирь<10; жёлтый — Барнаул<4; без заливки — иначе.
 */
@Slf4j
public class ExcelReport {

    private static final String[] HEADERS = {
            "№", "Номер товара", "OEM", "Запчасть", "Авто", "Цена, ₽", "Создан", "Изменено в",
            "Барнаул, конк.", "Сибирь, конк.", "Ссылка на моё объявление", "Статус", "Переоценка"
    };

    private final Path file;
    private final XSSFWorkbook wb;
    private final Sheet sheet;
    private int rowNum = 0;
    private int dataCount = 0;

    private final CellStyle headerStyle, plainStyle, yellowStyle, purpleStyle, redStyle;

    public ExcelReport(Path file) {
        this.file = file;
        this.wb = new XSSFWorkbook();
        this.sheet = wb.createSheet("Наличие");

        headerStyle = base(0xD9D9D9, 0x000000, true);
        plainStyle  = base(0xFFFFFF, 0x000000, false);   // без заливки — конкуренции достаточно
        yellowStyle = base(0xFFEB9C, 0x9C6500, false);   // мало в Барнауле (<4)
        purpleStyle = base(0xE9D5FF, 0x6B21A8, false);   // мало по Сибири (<10)
        redStyle    = base(0xFFC7CE, 0x9C0006, false);   // дефицит: Барнаул<4 и Сибирь<10

        int[] widths = {1500, 3600, 5200, 14000, 9000, 3400, 5000, 5000, 3600, 3600, 16000, 14000, 3600};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i]);
        sheet.createFreezePane(0, 1);

        Row header = sheet.createRow(rowNum++);
        for (int i = 0; i < HEADERS.length; i++) {
            Cell c = header.createCell(i);
            c.setCellValue(HEADERS[i]);
            c.setCellStyle(headerStyle);
        }
        save();
    }

    private CellStyle base(int fillRgb, int fontRgb, boolean bold) {
        XSSFCellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(new XSSFColor(rgb(fillRgb), null));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        s.setVerticalAlignment(VerticalAlignment.TOP);
        s.setWrapText(true);
        XSSFFont f = wb.createFont();
        f.setColor(new XSSFColor(rgb(fontRgb), null));
        f.setBold(bold);
        s.setFont(f);
        return s;
    }

    private static byte[] rgb(int hex) {
        return new byte[]{(byte) ((hex >> 16) & 0xFF), (byte) ((hex >> 8) & 0xFF), (byte) (hex & 0xFF)};
    }

    /** Добавляет строку по одной детали и сразу сохраняет файл. Потокобезопасно (вызов из потока анализа). */
    public synchronized void append(String oem, String itemNumber, String partName, String auto,
                                    BigDecimal price, String created, String priceChanged, AvailabilityResult r) {
        CellStyle style = styleFor(r);

        Row row = sheet.createRow(rowNum++);
        int col = 0;
        set(row, col++, ++dataCount, style);
        set(row, col++, itemNumber == null ? "" : itemNumber, style);
        set(row, col++, oem, style);
        set(row, col++, partName, style);
        set(row, col++, auto, style);
        set(row, col++, price != null && price.signum() > 0 ? price.toPlainString() : "", style);
        set(row, col++, created == null ? "" : created, style);
        set(row, col++, priceChanged == null ? "" : priceChanged, style);
        set(row, col++, r == null ? "" : String.valueOf(r.getBarnaulCount()), style);
        set(row, col++, r == null || !r.isSearchedSiberia() ? "" : String.valueOf(r.getSiberiaCount()), style);
        setLink(row, col++, r == null ? null : r.getMyListingUrl(), style);
        set(row, col++, r == null ? "" : nz(r.getStatus()), style);
        set(row, col, r == null ? "" : (Object) r.isReprice(), style);   // переоценка: true/false

        save();
    }

    private CellStyle styleFor(AvailabilityResult r) {
        if (r == null || r.getColor() == null) return plainStyle;
        return switch (r.getColor()) {
            case RED    -> redStyle;
            case PURPLE -> purpleStyle;
            case YELLOW -> yellowStyle;
            case NONE   -> plainStyle;
        };
    }

    /** Ячейка-гиперссылка на объявление (кликабельная в Excel). */
    private void setLink(Row row, int col, String url, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellStyle(style);
        if (url == null || url.isBlank()) { c.setCellValue(""); return; }
        c.setCellValue(url);
        try {
            Hyperlink link = wb.getCreationHelper().createHyperlink(HyperlinkType.URL);
            link.setAddress(url);
            c.setHyperlink(link);
        } catch (Exception ignored) {}
    }

    private void set(Row row, int col, Object val, CellStyle style) {
        Cell c = row.createCell(col);
        if (val instanceof Number n) c.setCellValue(n.doubleValue());
        else if (val instanceof Boolean b) c.setCellValue(b);
        else c.setCellValue(val == null ? "" : val.toString());
        c.setCellStyle(style);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private void save() {
        try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
            wb.write(fos);
        } catch (Exception e) {
            log.warn("Не удалось сохранить Excel-отчёт {}: {}", file, e.getMessage());
        }
    }

    public void close() {
        save();
        try { wb.close(); } catch (Exception ignored) {}
    }

    public Path path() { return file; }
    public int rows() { return dataCount; }
}
