package ru.retail.service.ui;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import ru.retail.service.dto.AggregationResult;

import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.nio.file.Path;

/**
 * Пишет отчёт по анализу каталога в .xlsx, строка за строкой, с пересохранением файла
 * после КАЖДОЙ детали (данные не теряются при обрыве прогона).
 * Цвет строки по сравнению моей цены с рекомендованной (рыночной):
 *   зелёный — совпадает (±допуск), красный — ВЫШЕ рынка, синий — НИЖЕ рынка.
 */
@Slf4j
public class ExcelReport {

    private static final String[] HEADERS = {
            "№", "OEM", "Запчасть", "Авто", "Цена на сайте", "Рекоменд.", "Δ к рынку, %",
            "Состояние", "Уверенность", "Город", "НСК", "Ссылка на объявление", "Причина"
    };

    private final Path file;
    private final double tolerance;          // напр. 0.02 = ±2%
    private final XSSFWorkbook wb;
    private final Sheet sheet;
    private int rowNum = 0;
    private int dataCount = 0;

    private final CellStyle headerStyle, greenStyle, redStyle, blueStyle, greyStyle;

    public ExcelReport(Path file, double tolerance) {
        this.file = file;
        this.tolerance = tolerance;
        this.wb = new XSSFWorkbook();
        this.sheet = wb.createSheet("Цены");

        headerStyle = base(0xD9D9D9, 0x000000, true);
        greenStyle  = base(0xC6EFCE, 0x006100, false);   // совпадает
        redStyle    = base(0xFFC7CE, 0x9C0006, false);   // выше рынка
        blueStyle   = base(0xBDD7EE, 0x1F4E78, false);   // ниже рынка
        greyStyle   = base(0xF2F2F2, 0x808080, false);   // нет данных

        int[] widths = {1500, 5200, 11000, 9000, 3400, 3400, 3400, 5200, 3200, 2200, 2200, 16000, 18000};
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
    public synchronized void append(String oem, String partName, String car,
                                    BigDecimal catalogPrice, AggregationResult r) {
        BigDecimal rec = (r == null) ? null : r.getRecommendedPrice();
        boolean found = rec != null && rec.compareTo(BigDecimal.ZERO) > 0;

        BigDecimal sitePrice = (r == null) ? null : r.getMyListingPrice();
        String url = (r == null) ? null : r.getMyListingUrl();
        // Состояние считаем по ЦЕНЕ НА САЙТЕ vs рекомендованной; если сайт не спарсился — по цене из каталога
        BigDecimal cmp = (sitePrice != null && sitePrice.signum() > 0) ? sitePrice : catalogPrice;

        String state;
        CellStyle style;
        Double diffPct = null;

        if (!found) {
            state = "нет данных";
            style = greyStyle;
        } else if (cmp == null || cmp.signum() <= 0) {
            state = "цена не указана";
            style = greyStyle;
        } else {
            double my = cmp.doubleValue();
            double rc = rec.doubleValue();
            double diff = (my - rc) / rc;
            diffPct = diff * 100.0;
            if (Math.abs(diff) <= tolerance) { state = "совпадает с рынком"; style = greenStyle; }
            else if (my > rc)                { state = "выше рынка";        style = redStyle; }
            else                             { state = "ниже рынка";        style = blueStyle; }
        }

        Row row = sheet.createRow(rowNum++);
        int col = 0;
        set(row, col++, ++dataCount, style);
        set(row, col++, oem, style);
        set(row, col++, partName, style);
        set(row, col++, car, style);
        set(row, col++, sitePrice != null && sitePrice.signum() > 0 ? sitePrice.toPlainString() : "", style);
        set(row, col++, found ? rec.toPlainString() : "", style);
        set(row, col++, diffPct == null ? "" : String.format("%+.1f", diffPct), style);
        set(row, col++, state, style);
        set(row, col++, r == null ? "" : nz(r.getAiConfidence()), style);
        set(row, col++, r == null ? "" : String.valueOf(r.getCityCompetitorCount()), style);
        set(row, col++, r == null ? "" : String.valueOf(r.getSiberiaCompetitorCount()), style);
        setLink(row, col++, url, style);
        set(row, col,   r == null ? "" : nz(r.getAiReason()), style);

        save();
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
