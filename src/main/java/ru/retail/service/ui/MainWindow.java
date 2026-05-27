package ru.retail.service.ui;

import ru.retail.service.dto.AggregationResult;
import ru.retail.service.dto.PartPrice;
import ru.retail.service.service.PriceAnalyzer;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainWindow extends JFrame {

    private static final Color GREEN  = new Color(34, 197, 94);
    private static final Color YELLOW = new Color(234, 179, 8);
    private static final Color RED    = new Color(239, 68, 68);
    private static final Color DIM    = new Color(160, 160, 160);
    private static final Color BLUE   = new Color(96, 165, 250);

    private final PriceAnalyzer priceAnalyzer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "analysis-thread");
        t.setDaemon(true);
        return t;
    });

    // ── Tab 1: Single analysis ──
    private JTextField oemField;
    private JTextField urlField;
    private JComboBox<String> regionCombo;
    private JTextField priceField;
    private JButton analyzeBtn;
    private JLabel statusLabel;
    private JScrollPane resultScroll;
    private JLabel recPriceLabel;
    private JLabel confidenceLabel;
    private JLabel statsLabel;
    private JLabel marketNoteLabel;
    private JTextArea aiReasonArea;
    private DefaultTableModel cityModel;
    private DefaultTableModel siberiaModel;
    private JPanel siberiaSection;

    // ── Tab 2: Batch analysis ──
    private JTextField csvPathField;
    private JTextField batchLimitField;
    private JComboBox<String> batchRegionCombo;
    private JTextField companyField;
    private JButton batchAnalyzeBtn;
    private JButton batchStopBtn;
    private JLabel batchStatusLabel;
    private DefaultTableModel batchModel;
    private JTable batchTable;
    private List<String[]> catalogRows = new ArrayList<>();
    private List<AggregationResult> batchResults = new ArrayList<>();
    private volatile boolean batchStopped = false;

    // ── Tab 2: Batch detail panel ──
    private JLabel batchRecPriceLabel;
    private JLabel batchConfidenceLabel;
    private JLabel batchStatsLabel;
    private JLabel batchMarketNoteLabel;
    private JTextArea batchAiReasonArea;
    private DefaultTableModel batchCityModel;
    private DefaultTableModel batchSiberiaModel;
    private JPanel batchSiberiaSection;
    private JLabel batchDetailHint;
    private JScrollPane batchDetailScroll;

    public MainWindow(PriceAnalyzer priceAnalyzer) {
        this.priceAnalyzer = priceAnalyzer;
        buildUI();
    }

    // ═══════════════════════════════════════════════════════
    // BUILD
    // ═══════════════════════════════════════════════════════

    private void buildUI() {
        setTitle("Анализ цен на запчасти — Drom.ru");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(950, 700));
        setPreferredSize(new Dimension(1200, 960));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Анализ по ссылке", buildSinglePanel());
        tabs.addTab("Анализ по каталогу", buildBatchPanel());

        setContentPane(tabs);
        pack();
        setLocationRelativeTo(null);
    }

    // ──────────────────────────────────────────────────────
    // TAB 1: Single analysis
    // ──────────────────────────────────────────────────────

    private JPanel buildSinglePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.add(buildFormPanel(), BorderLayout.NORTH);

        resultScroll = new JScrollPane(buildResultPanel());
        resultScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(60, 60, 60)));
        resultScroll.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(resultScroll, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));

        GridBagConstraints lbl = gbc(0, 0, 0.0, new Insets(5, 0, 5, 8));
        lbl.anchor = GridBagConstraints.WEST;
        GridBagConstraints fld = gbc(1, 0, 1.0, new Insets(5, 0, 5, 0));

        lbl.gridy = 0; panel.add(label("OEM номер:"), lbl);
        fld.gridy = 0;
        oemField = new JTextField();
        oemField.setToolTipText("Например: 4785033210");
        panel.add(oemField, fld);

        lbl.gridy = 1; panel.add(label("URL моего объявления:"), lbl);
        fld.gridy = 1;
        urlField = new JTextField();
        urlField.setToolTipText("Полная ссылка на baza.drom.ru");
        panel.add(urlField, fld);

        lbl.gridy = 2; panel.add(label("Регион:"), lbl);
        fld.gridy = 2;
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        regionCombo = new JComboBox<>(new String[]{
                "barnaul", "novosibirsk", "omsk", "tomsk", "kemerovo", "krasnoyarsk", "irkutsk"
        });
        regionCombo.setEditable(true);
        regionCombo.setPreferredSize(new Dimension(160, 30));
        row.add(regionCombo);
        row.add(Box.createHorizontalStrut(24));
        row.add(label("Моя цена, ₽ (необязательно):"));
        row.add(Box.createHorizontalStrut(8));
        priceField = new JTextField(10);
        priceField.setToolTipText("Оставьте пустым — цена возьмётся из объявления");
        row.add(priceField);
        panel.add(row, fld);

        lbl.gridy = 3; panel.add(new JLabel(""), lbl);
        fld.gridy = 3;
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        analyzeBtn = new JButton("Анализировать");
        analyzeBtn.setPreferredSize(new Dimension(160, 34));
        analyzeBtn.addActionListener(e -> runAnalysis());
        btnRow.add(analyzeBtn);
        btnRow.add(Box.createHorizontalStrut(14));
        statusLabel = new JLabel("");
        statusLabel.setForeground(DIM);
        btnRow.add(statusLabel);
        panel.add(btnRow, fld);

        return panel;
    }

    private JPanel buildResultPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JPanel priceRow = hPanel();
        recPriceLabel = new JLabel("—");
        recPriceLabel.setFont(recPriceLabel.getFont().deriveFont(Font.BOLD, 32f));
        recPriceLabel.setForeground(GREEN);
        priceRow.add(recPriceLabel);
        priceRow.add(Box.createHorizontalStrut(12));
        confidenceLabel = new JLabel("");
        confidenceLabel.setFont(confidenceLabel.getFont().deriveFont(Font.PLAIN, 13f));
        confidenceLabel.setForeground(DIM);
        priceRow.add(confidenceLabel);
        panel.add(priceRow);
        panel.add(vgap(6));

        statsLabel = new JLabel("—");
        statsLabel.setForeground(DIM);
        statsLabel.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(statsLabel);
        panel.add(vgap(4));

        marketNoteLabel = new JLabel("");
        marketNoteLabel.setForeground(DIM);
        marketNoteLabel.setFont(marketNoteLabel.getFont().deriveFont(Font.ITALIC, 12f));
        marketNoteLabel.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(marketNoteLabel);
        panel.add(vgap(12));

        panel.add(sectionHeader("Обоснование AI"));
        aiReasonArea = textArea(5);
        panel.add(wrapScroll(aiReasonArea, 100));
        panel.add(vgap(14));

        panel.add(sectionHeader("Конкуренты в городе"));
        String[] cols = {"Цена, ₽", "Название", "Продавец", "Дата", "URL"};
        cityModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        panel.add(buildCompetitorTable(cityModel, 220));
        panel.add(vgap(14));

        siberiaSection = new JPanel();
        siberiaSection.setLayout(new BoxLayout(siberiaSection, BoxLayout.Y_AXIS));
        siberiaSection.setAlignmentX(LEFT_ALIGNMENT);
        siberiaSection.add(sectionHeader("Конкуренты в Новосибирске"));
        siberiaModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        siberiaSection.add(buildCompetitorTable(siberiaModel, 180));
        siberiaSection.setVisible(false);
        panel.add(siberiaSection);

        return panel;
    }

    // ──────────────────────────────────────────────────────
    // TAB 2: Batch analysis
    // ──────────────────────────────────────────────────────

    private JPanel buildBatchPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.add(buildBatchFormPanel(), BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                buildBatchTableScrollPane(),
                buildBatchDetailPanel());
        split.setDividerLocation(260);
        split.setResizeWeight(0.35);
        split.setBorder(null);
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildBatchFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));

        GridBagConstraints lbl = gbc(0, 0, 0.0, new Insets(5, 0, 5, 8));
        lbl.anchor = GridBagConstraints.WEST;
        GridBagConstraints fld = gbc(1, 0, 1.0, new Insets(5, 0, 5, 0));

        // File picker
        lbl.gridy = 0; panel.add(label("Файл каталога (CSV):"), lbl);
        fld.gridy = 0;
        JPanel fileRow = new JPanel(new BorderLayout(6, 0));
        csvPathField = new JTextField();
        csvPathField.setEditable(false);
        JButton browseBtn = new JButton("Обзор...");
        browseBtn.addActionListener(e -> browseForCsv());
        fileRow.add(csvPathField, BorderLayout.CENTER);
        fileRow.add(browseBtn, BorderLayout.EAST);
        panel.add(fileRow, fld);

        // Settings row
        lbl.gridy = 1; panel.add(label("Регион:"), lbl);
        fld.gridy = 1;
        JPanel settingsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        batchRegionCombo = new JComboBox<>(new String[]{
                "barnaul", "novosibirsk", "omsk", "tomsk", "kemerovo", "krasnoyarsk", "irkutsk"
        });
        batchRegionCombo.setEditable(true);
        batchRegionCombo.setPreferredSize(new Dimension(160, 30));
        settingsRow.add(batchRegionCombo);
        settingsRow.add(Box.createHorizontalStrut(20));
        settingsRow.add(label("Компания на Drom:"));
        settingsRow.add(Box.createHorizontalStrut(8));
        companyField = new JTextField("YARD86", 12);
        companyField.setToolTipText("Частичное совпадение, без учёта регистра");
        settingsRow.add(companyField);
        settingsRow.add(Box.createHorizontalStrut(20));
        settingsRow.add(label("Кол-во OEM (0 = все):"));
        settingsRow.add(Box.createHorizontalStrut(8));
        batchLimitField = new JTextField("0", 5);
        batchLimitField.setToolTipText("0 — обработать все строки из файла");
        settingsRow.add(batchLimitField);
        panel.add(settingsRow, fld);

        // Buttons
        lbl.gridy = 2; panel.add(new JLabel(""), lbl);
        fld.gridy = 2;
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        batchAnalyzeBtn = new JButton("Запустить анализ");
        batchAnalyzeBtn.setPreferredSize(new Dimension(160, 34));
        batchAnalyzeBtn.setEnabled(false);
        batchAnalyzeBtn.addActionListener(e -> runBatchAnalysis());
        batchStopBtn = new JButton("Стоп");
        batchStopBtn.setPreferredSize(new Dimension(80, 34));
        batchStopBtn.setEnabled(false);
        batchStopBtn.addActionListener(e -> {
            batchStopped = true;
            batchStopBtn.setEnabled(false);
        });
        btnRow.add(batchAnalyzeBtn);
        btnRow.add(Box.createHorizontalStrut(8));
        btnRow.add(batchStopBtn);
        btnRow.add(Box.createHorizontalStrut(14));
        batchStatusLabel = new JLabel("Загрузите CSV-файл каталога");
        batchStatusLabel.setForeground(DIM);
        btnRow.add(batchStatusLabel);
        panel.add(btnRow, fld);

        return panel;
    }

    private JScrollPane buildBatchTableScrollPane() {
        String[] cols = {"OEM", "Запчасть", "Марка / Модель", "Цена каталог, ₽", "Рек. цена, ₽", "Конкурентов", "Статус"};
        batchModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        batchTable = new JTable(batchModel);
        batchTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        batchTable.setRowHeight(24);
        batchTable.getTableHeader().setReorderingAllowed(false);
        batchTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        int[] widths = {130, 200, 200, 120, 120, 90, 120};
        for (int i = 0; i < widths.length; i++) {
            batchTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        DefaultTableCellRenderer rightAlign = new DefaultTableCellRenderer();
        rightAlign.setHorizontalAlignment(SwingConstants.RIGHT);
        batchTable.getColumnModel().getColumn(3).setCellRenderer(rightAlign);
        batchTable.getColumnModel().getColumn(4).setCellRenderer(rightAlign);
        batchTable.getColumnModel().getColumn(5).setCellRenderer(rightAlign);

        batchTable.getColumnModel().getColumn(6).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object val, boolean sel, boolean foc, int row, int col) {
                super.getTableCellRendererComponent(t, val, sel, foc, row, col);
                String s = val == null ? "" : val.toString();
                setForeground(switch (s) {
                    case "Готово"           -> GREEN;
                    case "Не найдено"       -> RED;
                    case "Ошибка"           -> RED;
                    case "Анализируется..." -> BLUE;
                    default                 -> DIM;
                });
                return this;
            }
        });

        // Row selection → show detail
        batchTable.getSelectionModel().addListSelectionListener((ListSelectionEvent e) -> {
            if (e.getValueIsAdjusting()) return;
            int row = batchTable.getSelectedRow();
            if (row >= 0 && row < batchResults.size()) {
                AggregationResult r = batchResults.get(row);
                if (r != null) showBatchDetail(r);
                else clearBatchDetail();
            }
        });

        JScrollPane sp = new JScrollPane(batchTable,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        sp.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(60, 60, 60)));
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    private JScrollPane buildBatchDetailPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        // Hint shown before any row is selected
        batchDetailHint = new JLabel("Выберите строку в таблице чтобы увидеть детали анализа");
        batchDetailHint.setForeground(DIM);
        batchDetailHint.setFont(batchDetailHint.getFont().deriveFont(Font.ITALIC, 13f));
        batchDetailHint.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(batchDetailHint);
        panel.add(vgap(8));

        // Price row
        JPanel priceRow = hPanel();
        batchRecPriceLabel = new JLabel("—");
        batchRecPriceLabel.setFont(batchRecPriceLabel.getFont().deriveFont(Font.BOLD, 28f));
        batchRecPriceLabel.setForeground(GREEN);
        priceRow.add(batchRecPriceLabel);
        priceRow.add(Box.createHorizontalStrut(12));
        batchConfidenceLabel = new JLabel("");
        batchConfidenceLabel.setFont(batchConfidenceLabel.getFont().deriveFont(Font.PLAIN, 13f));
        batchConfidenceLabel.setForeground(DIM);
        priceRow.add(batchConfidenceLabel);
        panel.add(priceRow);
        panel.add(vgap(6));

        batchStatsLabel = new JLabel("—");
        batchStatsLabel.setForeground(DIM);
        batchStatsLabel.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(batchStatsLabel);
        panel.add(vgap(4));

        batchMarketNoteLabel = new JLabel("");
        batchMarketNoteLabel.setForeground(DIM);
        batchMarketNoteLabel.setFont(batchMarketNoteLabel.getFont().deriveFont(Font.ITALIC, 12f));
        batchMarketNoteLabel.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(batchMarketNoteLabel);
        panel.add(vgap(10));

        panel.add(sectionHeader("Обоснование AI"));
        batchAiReasonArea = textArea(4);
        panel.add(wrapScroll(batchAiReasonArea, 90));
        panel.add(vgap(12));

        panel.add(sectionHeader("Конкуренты в городе"));
        String[] cols = {"Цена, ₽", "Название", "Продавец", "Дата", "URL"};
        batchCityModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        panel.add(buildCompetitorTable(batchCityModel, 200));
        panel.add(vgap(12));

        batchSiberiaSection = new JPanel();
        batchSiberiaSection.setLayout(new BoxLayout(batchSiberiaSection, BoxLayout.Y_AXIS));
        batchSiberiaSection.setAlignmentX(LEFT_ALIGNMENT);
        batchSiberiaSection.add(sectionHeader("Конкуренты в Новосибирске"));
        batchSiberiaModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        batchSiberiaSection.add(buildCompetitorTable(batchSiberiaModel, 160));
        batchSiberiaSection.setVisible(false);
        panel.add(batchSiberiaSection);

        batchDetailScroll = new JScrollPane(panel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        batchDetailScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(60, 60, 60)));
        batchDetailScroll.getVerticalScrollBar().setUnitIncrement(16);
        return batchDetailScroll;
    }

    // ═══════════════════════════════════════════════════════
    // SINGLE ANALYSIS
    // ═══════════════════════════════════════════════════════

    private void runAnalysis() {
        String oem      = oemField.getText().trim();
        String url      = urlField.getText().trim();
        String region   = ((String) regionCombo.getSelectedItem()).trim();
        String priceText = priceField.getText().trim();

        if (oem.isEmpty() || url.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Заполните OEM номер и URL объявления", "Ошибка", JOptionPane.WARNING_MESSAGE);
            return;
        }

        BigDecimal myPrice = BigDecimal.ZERO;
        if (!priceText.isEmpty()) {
            try { myPrice = new BigDecimal(priceText.replaceAll("[^\\d.]", "")); }
            catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Некорректная цена", "Ошибка", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }

        BigDecimal finalPrice = myPrice;
        analyzeBtn.setEnabled(false);
        setStatus("Анализ идёт...", DIM);
        clearResults();

        executor.submit(() -> {
            try {
                AggregationResult res = priceAnalyzer.analyze(oem, region, url, finalPrice);
                SwingUtilities.invokeLater(() -> showResult(res));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setStatus("Ошибка: " + ex.getMessage(), RED);
                    analyzeBtn.setEnabled(true);
                });
            }
        });
    }

    private void clearResults() {
        recPriceLabel.setText("—");
        recPriceLabel.setForeground(GREEN);
        confidenceLabel.setText("");
        statsLabel.setText("—");
        marketNoteLabel.setText("");
        aiReasonArea.setText("");
        cityModel.setRowCount(0);
        siberiaModel.setRowCount(0);
        siberiaSection.setVisible(false);
    }

    private void showResult(AggregationResult r) {
        if (r.getRecommendedPrice() != null)
            recPriceLabel.setText(formatPrice(r.getRecommendedPrice()) + " ₽");

        if (r.getAiConfidence() != null) {
            String conf = r.getAiConfidence().toLowerCase();
            Color c = conf.contains("высок") ? GREEN : conf.contains("средн") ? YELLOW : RED;
            confidenceLabel.setForeground(c);
            confidenceLabel.setText("уверенность: " + r.getAiConfidence());
        }
        statsLabel.setText(String.format(
                "Город: %d  •  Новосибирск: %d  •  Мин: %s ₽  •  Макс: %s ₽  •  Ср: %s ₽  •  Медиана: %s ₽",
                r.getCityCompetitorCount(), r.getSiberiaCompetitorCount(),
                formatPrice(r.getMinPrice()), formatPrice(r.getMaxPrice()),
                formatPrice(r.getAvgPrice()), formatPrice(r.getMedianPrice())));
        if (r.getMarketNote() != null) marketNoteLabel.setText(r.getMarketNote());

        aiReasonArea.setText(r.getAiReason() != null ? r.getAiReason() : "");
        aiReasonArea.setCaretPosition(0);

        fillTable(cityModel, r.getItems());
        fillTable(siberiaModel, r.getSiberiaItems());
        siberiaSection.setVisible(r.getSiberiaItems() != null && !r.getSiberiaItems().isEmpty());

        setStatus("Готово", GREEN);
        analyzeBtn.setEnabled(true);
        SwingUtilities.invokeLater(() -> resultScroll.getVerticalScrollBar().setValue(0));
    }

    private void fillTable(DefaultTableModel model, List<PartPrice> items) {
        model.setRowCount(0);
        if (items == null) return;
        for (PartPrice p : items) {
            model.addRow(new Object[]{
                    p.getPrice() != null ? p.getPrice().toPlainString() : "—",
                    p.getTitle() != null ? p.getTitle() : "",
                    p.getDealer() != null ? p.getDealer() : p.getLocation(),
                    p.getPublishedDate() != null ? p.getPublishedDate() : "",
                    p.getUrl() != null ? p.getUrl() : ""
            });
        }
    }

    // ═══════════════════════════════════════════════════════
    // BATCH ANALYSIS
    // ═══════════════════════════════════════════════════════

    private void browseForCsv() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("CSV файлы (*.csv)", "csv"));
        chooser.setCurrentDirectory(new File(System.getProperty("user.home")));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            csvPathField.setText(file.getAbsolutePath());
            loadCsvFile(file);
        }
    }

    private void loadCsvFile(File file) {
        try {
            catalogRows = parseCsvFile(file);
            batchModel.setRowCount(0);
            for (String[] row : catalogRows) {
                String oem      = col(row, 4);
                String partName = col(row, 3);
                String brand    = col(row, 8);
                String model    = col(row, 9);
                String price    = col(row, 5);
                if (oem.isEmpty()) continue;
                batchModel.addRow(new Object[]{
                        oem,
                        partName,
                        brand + (model.isEmpty() ? "" : " " + model),
                        price.isEmpty() ? "—" : price,
                        "—", "—", "Ожидает"
                });
            }
            int count = batchModel.getRowCount();
            batchResults = new ArrayList<>(Collections.nCopies(count, null));
            batchStatusLabel.setText("Загружено строк: " + count);
            batchStatusLabel.setForeground(GREEN);
            batchAnalyzeBtn.setEnabled(count > 0);
            clearBatchDetail();
        } catch (Exception ex) {
            batchStatusLabel.setText("Ошибка чтения файла: " + ex.getMessage());
            batchStatusLabel.setForeground(RED);
        }
    }

    private void runBatchAnalysis() {
        if (catalogRows.isEmpty()) return;

        batchStopped = false;
        batchAnalyzeBtn.setEnabled(false);
        batchStopBtn.setEnabled(true);
        analyzeBtn.setEnabled(false);

        // Reset rows
        for (int i = 0; i < batchModel.getRowCount(); i++) {
            batchModel.setValueAt("Ожидает", i, 6);
            batchModel.setValueAt("—", i, 4);
            batchModel.setValueAt("—", i, 5);
        }
        batchResults = new ArrayList<>(Collections.nCopies(batchModel.getRowCount(), null));
        clearBatchDetail();

        String region  = ((String) batchRegionCombo.getSelectedItem()).trim();
        String company = companyField.getText().trim();
        int limitVal   = 0;
        try { limitVal = Integer.parseInt(batchLimitField.getText().trim()); } catch (Exception ignored) {}
        int total = (limitVal > 0 && limitVal < batchModel.getRowCount()) ? limitVal : batchModel.getRowCount();

        executor.submit(() -> {
            int tableRow = 0;
            for (String[] csvRow : catalogRows) {
                if (batchStopped || tableRow >= total) break;

                String oem = col(csvRow, 4);
                if (oem.isEmpty()) continue;

                BigDecimal catalogPrice = BigDecimal.ZERO;
                try { catalogPrice = new BigDecimal(col(csvRow, 5).replaceAll("[^\\d.]", "")); }
                catch (Exception ignored) {}

                final int row = tableRow++;
                final int done = row + 1;
                final BigDecimal price = catalogPrice;

                SwingUtilities.invokeLater(() -> {
                    batchModel.setValueAt("Анализируется...", row, 6);
                    batchStatusLabel.setText("Анализируется " + done + " / " + total + "...");
                    batchStatusLabel.setForeground(BLUE);
                });

                try {
                    AggregationResult result = priceAnalyzer.analyzeFromCatalog(oem, price, region, company);

                    SwingUtilities.invokeLater(() -> {
                        batchResults.set(row, result);
                        boolean found = result.getRecommendedPrice() != null
                                && result.getRecommendedPrice().compareTo(BigDecimal.ZERO) > 0;
                        if (found) {
                            batchModel.setValueAt(formatPrice(result.getRecommendedPrice()) + " ₽", row, 4);
                            batchModel.setValueAt(
                                    result.getCityCompetitorCount() + result.getSiberiaCompetitorCount(), row, 5);
                            batchModel.setValueAt("Готово", row, 6);
                        } else {
                            batchModel.setValueAt("—", row, 4);
                            batchModel.setValueAt(0, row, 5);
                            batchModel.setValueAt("Не найдено", row, 6);
                        }
                        // Если эта строка выбрана — обновить детали
                        if (batchTable.getSelectedRow() == row) showBatchDetail(result);
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> batchModel.setValueAt("Ошибка", row, 6));
                }
            }

            SwingUtilities.invokeLater(() -> {
                batchAnalyzeBtn.setEnabled(true);
                batchStopBtn.setEnabled(false);
                analyzeBtn.setEnabled(true);
                batchStatusLabel.setText(batchStopped ? "Остановлено" : "Анализ завершён");
                batchStatusLabel.setForeground(batchStopped ? YELLOW : GREEN);
            });
        });
    }

    private void showBatchDetail(AggregationResult r) {
        batchDetailHint.setVisible(false);

        if (r.getRecommendedPrice() != null)
            batchRecPriceLabel.setText(formatPrice(r.getRecommendedPrice()) + " ₽");

        if (r.getAiConfidence() != null) {
            String conf = r.getAiConfidence().toLowerCase();
            Color c = conf.contains("высок") ? GREEN : conf.contains("средн") ? YELLOW : RED;
            batchConfidenceLabel.setForeground(c);
            batchConfidenceLabel.setText("уверенность: " + r.getAiConfidence());
        }
        batchStatsLabel.setText(String.format(
                "Город: %d  •  Новосибирск: %d  •  Мин: %s ₽  •  Макс: %s ₽  •  Ср: %s ₽  •  Медиана: %s ₽",
                r.getCityCompetitorCount(), r.getSiberiaCompetitorCount(),
                formatPrice(r.getMinPrice()), formatPrice(r.getMaxPrice()),
                formatPrice(r.getAvgPrice()), formatPrice(r.getMedianPrice())));
        if (r.getMarketNote() != null) batchMarketNoteLabel.setText(r.getMarketNote());

        batchAiReasonArea.setText(r.getAiReason() != null ? r.getAiReason() : "");
        batchAiReasonArea.setCaretPosition(0);

        fillTable(batchCityModel, r.getItems());
        fillTable(batchSiberiaModel, r.getSiberiaItems());
        batchSiberiaSection.setVisible(r.getSiberiaItems() != null && !r.getSiberiaItems().isEmpty());

        SwingUtilities.invokeLater(() -> batchDetailScroll.getVerticalScrollBar().setValue(0));
    }

    private void clearBatchDetail() {
        batchDetailHint.setVisible(true);
        batchRecPriceLabel.setText("—");
        batchRecPriceLabel.setForeground(GREEN);
        batchConfidenceLabel.setText("");
        batchStatsLabel.setText("—");
        batchMarketNoteLabel.setText("");
        batchAiReasonArea.setText("");
        batchCityModel.setRowCount(0);
        batchSiberiaModel.setRowCount(0);
        if (batchSiberiaSection != null) batchSiberiaSection.setVisible(false);
    }

    // ═══════════════════════════════════════════════════════
    // CSV PARSER (Windows-1251, semicolon, quoted)
    // ═══════════════════════════════════════════════════════

    private List<String[]> parseCsvFile(File file) throws Exception {
        List<String[]> result = new ArrayList<>();
        try (var reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "Windows-1251"))) {

            List<String> currentRow = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            boolean inQuotes = false;
            boolean firstRow = true;

            while (true) {
                int c = reader.read();
                if (c == -1) {
                    currentRow.add(sb.toString().trim());
                    if (!firstRow && !currentRow.stream().allMatch(String::isEmpty))
                        result.add(currentRow.toArray(new String[0]));
                    break;
                }
                char ch = (char) c;

                if (ch == '"') {
                    inQuotes = !inQuotes;
                } else if (ch == ';' && !inQuotes) {
                    currentRow.add(sb.toString().trim());
                    sb.setLength(0);
                } else if (ch == '\n' && !inQuotes) {
                    currentRow.add(sb.toString().trim());
                    sb.setLength(0);
                    if (!currentRow.stream().allMatch(String::isEmpty)) {
                        if (firstRow) firstRow = false;
                        else result.add(currentRow.toArray(new String[0]));
                    }
                    currentRow = new ArrayList<>();
                } else if (ch != '\r') {
                    sb.append(ch);
                }
            }
        }
        return result;
    }

    private String col(String[] row, int index) {
        if (row == null || index >= row.length) return "";
        String v = row[index];
        return v == null ? "" : v.trim();
    }

    // ═══════════════════════════════════════════════════════
    // WIDGET HELPERS
    // ═══════════════════════════════════════════════════════

    private JScrollPane buildCompetitorTable(DefaultTableModel model, int height) {
        JTable table = new JTable(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowHeight(24);
        table.getTableHeader().setReorderingAllowed(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        int[] widths = {80, 360, 130, 120, 400};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        DefaultTableCellRenderer rightAlign = new DefaultTableCellRenderer();
        rightAlign.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(0).setCellRenderer(rightAlign);

        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = table.getSelectedRow();
                    if (row >= 0) {
                        String urlVal = (String) model.getValueAt(row, 4);
                        if (urlVal != null && !urlVal.isEmpty()) {
                            Toolkit.getDefaultToolkit().getSystemClipboard()
                                    .setContents(new StringSelection(urlVal), null);
                            if (statusLabel != null) setStatus("URL скопирован в буфер", DIM);
                        }
                    }
                }
            }
        });

        JScrollPane sp = new JScrollPane(table,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        sp.setAlignmentX(LEFT_ALIGNMENT);
        sp.setPreferredSize(new Dimension(0, height));
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        return sp;
    }

    private JPanel hPanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    private JLabel label(String text) { return new JLabel(text); }

    private JLabel sectionHeader(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 14f));
        l.setAlignmentX(LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        return l;
    }

    private JTextArea textArea(int rows) {
        JTextArea ta = new JTextArea(rows, 0);
        ta.setEditable(false);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        ta.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        return ta;
    }

    private JScrollPane wrapScroll(JTextArea ta, int maxHeight) {
        JScrollPane sp = new JScrollPane(ta);
        sp.setAlignmentX(LEFT_ALIGNMENT);
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, maxHeight));
        return sp;
    }

    private Component vgap(int h) { return Box.createRigidArea(new Dimension(0, h)); }

    private GridBagConstraints gbc(int x, int y, double wx, Insets insets) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x; c.gridy = y; c.weightx = wx;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = insets;
        return c;
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
    }

    private String formatPrice(BigDecimal price) {
        if (price == null) return "—";
        return String.format("%,.0f", price).replace(',', ' ');
    }
}
