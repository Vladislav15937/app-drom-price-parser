package ru.retail.service.ui;

import ru.retail.service.config.AnalysisProfiles;
import ru.retail.service.dto.AvailabilityResult;
import ru.retail.service.service.BazonClient;
import ru.retail.service.service.CatalogLoader;
import ru.retail.service.service.PriceAnalyzer;
import ru.retail.service.service.TunnelService;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Десктоп-окно анализа наличия/плотности рынка (без ИИ).
 * Загрузка каталога → фильтр по «Создан» (старше 6 мес) → подсчёт конкурентов по OEM
 * (Барнаул, при разреженном рынке — вся Сибирь) → цветной xlsx-отчёт со ссылкой на наше объявление.
 */
public class MainWindow extends JFrame {

    private static final Color GREEN  = new Color(34, 197, 94);
    private static final Color YELLOW = new Color(234, 179, 8);
    private static final Color RED    = new Color(239, 68, 68);
    private static final Color DIM    = new Color(160, 160, 160);
    private static final Color BLUE   = new Color(96, 165, 250);

    // Фон строк таблицы по цвету результата (тёмная тема).
    private static final Color BG_RED    = new Color(80, 30, 30);
    private static final Color BG_PURPLE = new Color(60, 40, 80);
    private static final Color BG_YELLOW = new Color(80, 70, 20);

    private final PriceAnalyzer priceAnalyzer;
    private final TunnelService tunnelService;
    private final AnalysisProfiles profiles;
    private final BazonClient bazonClient;
    private JLabel publicUrlLabel;
    private JComboBox<String> profileCombo;        // тип детали (батч)
    private JComboBox<String> singleProfileCombo;  // тип детали (одиночный)
    private File loadedFile;                        // текущий загруженный каталог (для перезагрузки при смене профиля)

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "analysis-thread");
        t.setDaemon(true);
        return t;
    });

    // ── Tab 1: Single (по OEM) ──
    private JTextField oemField;
    private JTextField priceField;
    private JTextField singleCompanyField;
    private JButton analyzeBtn;
    private JLabel statusLabel;
    private JLabel resultTitleLabel;
    private JLabel resultCountsLabel;
    private JLabel resultStatusLabel;
    private JLabel myLinkLabel;

    // ── Tab 2: Batch (по каталогу) ──
    private JTextField csvPathField;
    private JTextField batchLimitField;
    private JTextField companyField;
    private JButton batchAnalyzeBtn;
    private JButton batchStopBtn;
    private JButton bazonBtn;
    private JLabel batchStatusLabel;
    private DefaultTableModel batchModel;
    private JTable batchTable;
    private JLabel batchLinkLabel;
    private List<CatalogLoader.CatalogItem> catalogItems = new ArrayList<>();
    private List<AvailabilityResult> batchResults = new ArrayList<>();
    private volatile boolean batchStopped = false;

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public MainWindow(PriceAnalyzer priceAnalyzer, TunnelService tunnelService,
                      AnalysisProfiles profiles, BazonClient bazonClient) {
        this.priceAnalyzer = priceAnalyzer;
        this.tunnelService = tunnelService;
        this.profiles = profiles;
        this.bazonClient = bazonClient;
        buildUI();
    }

    private String[] profileNames() {
        List<String> n = profiles == null ? List.of() : profiles.names();
        return n.isEmpty() ? new String[]{"(нет профилей)"} : n.toArray(new String[0]);
    }

    private AnalysisProfiles.Profile prof(JComboBox<String> combo) {
        return profiles == null ? null : profiles.byName((String) combo.getSelectedItem());
    }

    // ═══════════════════════════════════════════════════════
    // BUILD
    // ═══════════════════════════════════════════════════════

    private void buildUI() {
        setTitle("Анализ наличия запчастей — Drom.ru");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(950, 700));
        setPreferredSize(new Dimension(1200, 900));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Проверка по OEM", buildSinglePanel());
        tabs.addTab("Анализ по каталогу", buildBatchPanel());

        JPanel content = new JPanel(new BorderLayout());
        content.add(buildUrlBar(), BorderLayout.NORTH);
        content.add(tabs, BorderLayout.CENTER);
        setContentPane(content);
        pack();
        setLocationRelativeTo(null);

        Thread urlWaiter = new Thread(() -> {
            String pub = tunnelService.waitForPublicUrl();
            SwingUtilities.invokeLater(() -> updatePublicUrl(pub));
        }, "url-waiter");
        urlWaiter.setDaemon(true);
        urlWaiter.start();
    }

    private JPanel buildUrlBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 5));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(60, 60, 60)));
        bar.setBackground(new Color(43, 43, 43));

        String local = tunnelService.getLocalUrl() != null
                ? tunnelService.getLocalUrl() : "http://localhost:8081";

        JLabel localLbl = new JLabel("Локальный: " + local);
        localLbl.setFont(localLbl.getFont().deriveFont(Font.PLAIN, 12f));
        localLbl.setForeground(DIM);
        localLbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        localLbl.setToolTipText("Нажмите, чтобы скопировать");
        localLbl.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                copy(local);
            }
            @Override public void mouseEntered(java.awt.event.MouseEvent e) { localLbl.setForeground(BLUE); }
            @Override public void mouseExited(java.awt.event.MouseEvent e)  { localLbl.setForeground(DIM); }
        });

        JLabel sep = new JLabel("  |  ");
        sep.setForeground(new Color(80, 80, 80));

        publicUrlLabel = new JLabel("Публичный: определяется...");
        publicUrlLabel.setFont(publicUrlLabel.getFont().deriveFont(Font.PLAIN, 12f));
        publicUrlLabel.setForeground(DIM);

        bar.add(localLbl);
        bar.add(sep);
        bar.add(publicUrlLabel);
        return bar;
    }

    private void updatePublicUrl(String pub) {
        if (pub != null) {
            publicUrlLabel.setText("Публичный: " + pub);
            publicUrlLabel.setForeground(GREEN);
            publicUrlLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            publicUrlLabel.setToolTipText("Нажмите, чтобы скопировать");
            publicUrlLabel.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent e) { copy(pub); }
                @Override public void mouseEntered(java.awt.event.MouseEvent e) { publicUrlLabel.setForeground(BLUE); }
                @Override public void mouseExited(java.awt.event.MouseEvent e)  { publicUrlLabel.setForeground(GREEN); }
            });
        } else {
            publicUrlLabel.setText("Публичный: cloudflared не найден  →  brew install cloudflared");
            publicUrlLabel.setForeground(YELLOW);
        }
    }

    // ──────────────────────────────────────────────────────
    // TAB 1: по OEM
    // ──────────────────────────────────────────────────────

    private JPanel buildSinglePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.add(buildFormPanel(), BorderLayout.NORTH);
        panel.add(buildResultPanel(), BorderLayout.CENTER);
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
        oemField.setToolTipText("Например: 4775068010");
        panel.add(oemField, fld);

        lbl.gridy = 1; panel.add(label("Параметры:"), lbl);
        fld.gridy = 1;
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.add(label("Тип детали:"));
        row.add(Box.createHorizontalStrut(8));
        singleProfileCombo = new JComboBox<>(profileNames());
        row.add(singleProfileCombo);
        row.add(Box.createHorizontalStrut(20));
        row.add(label("Компания на Drom:"));
        row.add(Box.createHorizontalStrut(8));
        singleCompanyField = new JTextField("YARD86", 12);
        singleCompanyField.setToolTipText("Частичное совпадение, без учёта регистра");
        row.add(singleCompanyField);
        row.add(Box.createHorizontalStrut(24));
        row.add(label("Цена, ₽ (для выбора объявления):"));
        row.add(Box.createHorizontalStrut(8));
        priceField = new JTextField(10);
        priceField.setToolTipText("Необязательно — помогает выбрать наше объявление среди нескольких");
        row.add(priceField);
        panel.add(row, fld);

        lbl.gridy = 2; panel.add(new JLabel(""), lbl);
        fld.gridy = 2;
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        analyzeBtn = new JButton("Проверить наличие");
        analyzeBtn.setPreferredSize(new Dimension(190, 34));
        analyzeBtn.addActionListener(e -> runSingle());
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

        resultTitleLabel = new JLabel("—");
        resultTitleLabel.setFont(resultTitleLabel.getFont().deriveFont(Font.BOLD, 26f));
        resultTitleLabel.setForeground(DIM);
        resultTitleLabel.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(resultTitleLabel);
        panel.add(vgap(10));

        resultCountsLabel = new JLabel("—");
        resultCountsLabel.setFont(resultCountsLabel.getFont().deriveFont(Font.PLAIN, 15f));
        resultCountsLabel.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(resultCountsLabel);
        panel.add(vgap(6));

        resultStatusLabel = new JLabel("");
        resultStatusLabel.setFont(resultStatusLabel.getFont().deriveFont(Font.ITALIC, 13f));
        resultStatusLabel.setForeground(DIM);
        resultStatusLabel.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(resultStatusLabel);
        panel.add(vgap(14));

        myLinkLabel = new JLabel("");
        myLinkLabel.setForeground(BLUE);
        myLinkLabel.setAlignmentX(LEFT_ALIGNMENT);
        myLinkLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        myLinkLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                String u = myLinkLabel.getToolTipText();
                if (u != null && !u.isBlank()) { copy(u); setStatus("Ссылка скопирована", DIM); }
            }
        });
        panel.add(myLinkLabel);
        return panel;
    }

    // ──────────────────────────────────────────────────────
    // TAB 2: по каталогу
    // ──────────────────────────────────────────────────────

    private JPanel buildBatchPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.add(buildBatchFormPanel(), BorderLayout.NORTH);
        panel.add(buildBatchTableScrollPane(), BorderLayout.CENTER);
        panel.add(buildBatchFooter(), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildBatchFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));

        GridBagConstraints lbl = gbc(0, 0, 0.0, new Insets(5, 0, 5, 8));
        lbl.anchor = GridBagConstraints.WEST;
        GridBagConstraints fld = gbc(1, 0, 1.0, new Insets(5, 0, 5, 0));

        lbl.gridy = 0; panel.add(label("Файл каталога (CSV/XLSX):"), lbl);
        fld.gridy = 0;
        JPanel fileRow = new JPanel(new BorderLayout(6, 0));
        csvPathField = new JTextField();
        csvPathField.setEditable(false);
        JPanel srcBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        bazonBtn = new JButton("Из Bazon");
        bazonBtn.setToolTipText("Выгрузить из учётной системы контрактные детали выбранного типа старше 6 мес (склад «Ткацкая»)");
        bazonBtn.addActionListener(e -> loadFromBazon());
        JButton browseBtn = new JButton("Обзор...");
        browseBtn.setToolTipText("Загрузить каталог из файла (CSV/XLSX) — запасной вариант");
        browseBtn.addActionListener(e -> browseForCsv());
        srcBtns.add(bazonBtn);
        srcBtns.add(browseBtn);
        fileRow.add(csvPathField, BorderLayout.CENTER);
        fileRow.add(srcBtns, BorderLayout.EAST);
        panel.add(fileRow, fld);

        lbl.gridy = 1; panel.add(label("Параметры:"), lbl);
        fld.gridy = 1;
        JPanel settingsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        settingsRow.add(label("Тип детали:"));
        settingsRow.add(Box.createHorizontalStrut(8));
        profileCombo = new JComboBox<>(profileNames());
        profileCombo.setToolTipText("Профиль из application.yml: ключевое слово каталога + стоп-слова");
        profileCombo.addActionListener(e -> { if (loadedFile != null) loadCsvFile(loadedFile); });
        settingsRow.add(profileCombo);
        settingsRow.add(Box.createHorizontalStrut(20));
        settingsRow.add(label("Компания на Drom:"));
        settingsRow.add(Box.createHorizontalStrut(8));
        companyField = new JTextField("YARD86", 12);
        companyField.setToolTipText("Частичное совпадение, без учёта регистра");
        settingsRow.add(companyField);
        settingsRow.add(Box.createHorizontalStrut(20));
        settingsRow.add(label("Кол-во позиций (0 = все):"));
        settingsRow.add(Box.createHorizontalStrut(8));
        batchLimitField = new JTextField("0", 5);
        settingsRow.add(batchLimitField);
        panel.add(settingsRow, fld);

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
        batchStopBtn.addActionListener(e -> { batchStopped = true; batchStopBtn.setEnabled(false); });
        btnRow.add(batchAnalyzeBtn);
        btnRow.add(Box.createHorizontalStrut(8));
        btnRow.add(batchStopBtn);
        btnRow.add(Box.createHorizontalStrut(14));
        batchStatusLabel = new JLabel("Загрузите файл каталога (CSV или XLSX)");
        batchStatusLabel.setForeground(DIM);
        btnRow.add(batchStatusLabel);
        panel.add(btnRow, fld);

        return panel;
    }

    private JScrollPane buildBatchTableScrollPane() {
        String[] cols = {"Номер товара", "OEM", "Запчасть", "Авто", "Цена, ₽", "Создан", "Барнаул", "Сибирь", "Статус", "Переоценка"};
        batchModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        batchTable = new JTable(batchModel);
        batchTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        batchTable.setRowHeight(24);
        batchTable.getTableHeader().setReorderingAllowed(false);
        batchTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        int[] widths = {100, 120, 340, 200, 90, 100, 80, 80, 320, 90};
        for (int i = 0; i < widths.length; i++)
            batchTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);

        ColorRowRenderer renderer = new ColorRowRenderer();
        for (int i = 0; i < cols.length; i++)
            batchTable.getColumnModel().getColumn(i).setCellRenderer(renderer);

        // выбор строки → показать ссылку на наше объявление
        batchTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int row = batchTable.getSelectedRow();
            if (row >= 0 && row < batchResults.size() && batchResults.get(row) != null) {
                String url = batchResults.get(row).getMyListingUrl();
                setBatchLink(url);
            } else setBatchLink(null);
        });

        JScrollPane sp = new JScrollPane(batchTable,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        sp.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(60, 60, 60)));
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    private JPanel buildBatchFooter() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 6));
        p.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(60, 60, 60)));
        JLabel legend = new JLabel("Цвета: красный — дефицит (Барнаул<4 и Сибирь<10) · фиолетовый — Сибирь<10 · жёлтый — Барнаул<4");
        legend.setForeground(DIM);
        legend.setFont(legend.getFont().deriveFont(Font.PLAIN, 12f));
        p.add(legend);
        p.add(new JLabel("   "));
        batchLinkLabel = new JLabel("");
        batchLinkLabel.setForeground(BLUE);
        batchLinkLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        batchLinkLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                String u = batchLinkLabel.getToolTipText();
                if (u != null && !u.isBlank()) copy(u);
            }
        });
        p.add(batchLinkLabel);
        return p;
    }

    private void setBatchLink(String url) {
        if (url == null || url.isBlank()) {
            batchLinkLabel.setText("");
            batchLinkLabel.setToolTipText(null);
        } else {
            batchLinkLabel.setText("Моё объявление: " + url + "  (клик — копировать)");
            batchLinkLabel.setToolTipText(url);
        }
    }

    /** Рендерер строки таблицы: фон по цвету результата (красный/фиолетовый/жёлтый/без). */
    private class ColorRowRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object val, boolean sel, boolean foc, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, val, sel, foc, row, col);
            if (sel) return c;
            Color bg = t.getBackground();
            if (row < batchResults.size() && batchResults.get(row) != null) {
                bg = switch (batchResults.get(row).getColor()) {
                    case RED    -> BG_RED;
                    case PURPLE -> BG_PURPLE;
                    case YELLOW -> BG_YELLOW;
                    case NONE   -> t.getBackground();
                };
            }
            c.setBackground(bg);
            return c;
        }
    }

    // ═══════════════════════════════════════════════════════
    // SINGLE
    // ═══════════════════════════════════════════════════════

    private void runSingle() {
        String oem = oemField.getText().trim();
        if (oem.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Введите OEM номер", "Ошибка", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String company = singleCompanyField.getText().trim();
        BigDecimal price = BigDecimal.ZERO;
        try { if (!priceField.getText().isBlank()) price = new BigDecimal(priceField.getText().replaceAll("[^\\d.]", "")); }
        catch (NumberFormatException ignored) {}

        final BigDecimal priceF = price;
        analyzeBtn.setEnabled(false);
        setStatus("Анализ идёт...", DIM);
        clearSingle();

        executor.submit(() -> {
            try {
                priceAnalyzer.resetBreakers();
                AnalysisProfiles.Profile pr = prof(singleProfileCombo);
                AvailabilityResult res = priceAnalyzer.analyzeByOem(oem, priceF, company,
                        pr == null ? List.of() : pr.getStopWords());
                SwingUtilities.invokeLater(() -> showSingle(res));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setStatus("Ошибка: " + ex.getMessage(), RED);
                    analyzeBtn.setEnabled(true);
                });
            }
        });
    }

    private void clearSingle() {
        resultTitleLabel.setText("—");
        resultTitleLabel.setForeground(DIM);
        resultCountsLabel.setText("—");
        resultStatusLabel.setText("");
        myLinkLabel.setText("");
        myLinkLabel.setToolTipText(null);
    }

    private void showSingle(AvailabilityResult r) {
        resultTitleLabel.setText(colorTitle(r.getColor()));
        resultTitleLabel.setForeground(swingColor(r.getColor()));
        resultCountsLabel.setText(String.format("Барнаул: %d конкурентов   •   Сибирь: %s",
                r.getBarnaulCount(), r.isSearchedSiberia() ? r.getSiberiaCount() + " конкурентов" : "не искали (в Барнауле ≥ 10)"));
        resultStatusLabel.setText(r.getStatus() != null ? r.getStatus() : "");
        if (r.getMyListingUrl() != null && !r.getMyListingUrl().isBlank()) {
            myLinkLabel.setText("Моё объявление: " + r.getMyListingUrl() + "  (клик — копировать)");
            myLinkLabel.setToolTipText(r.getMyListingUrl());
        } else {
            myLinkLabel.setText("Моё объявление не найдено на drom.");
            myLinkLabel.setToolTipText(null);
        }
        setStatus("Готово", GREEN);
        analyzeBtn.setEnabled(true);
    }

    private String colorTitle(AvailabilityResult.Color c) {
        return switch (c) {
            case RED    -> "🔴 Дефицит";
            case PURPLE -> "🟣 Мало по Сибири";
            case YELLOW -> "🟡 Мало в Барнауле";
            case NONE   -> "⚪ Конкуренция достаточная";
        };
    }

    private Color swingColor(AvailabilityResult.Color c) {
        return switch (c) {
            case RED    -> RED;
            case PURPLE -> new Color(168, 120, 220);
            case YELLOW -> YELLOW;
            case NONE   -> GREEN;
        };
    }

    // ═══════════════════════════════════════════════════════
    // BATCH
    // ═══════════════════════════════════════════════════════

    private void browseForCsv() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Каталог (*.csv, *.xlsx)", "csv", "xlsx"));
        chooser.setCurrentDirectory(new File(System.getProperty("user.home")));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            csvPathField.setText(file.getAbsolutePath());
            loadCsvFile(file);
        }
    }

    private void loadCsvFile(File file) {
        try {
            loadedFile = file;
            AnalysisProfiles.Profile pr = prof(profileCombo);
            String kw = pr == null ? "" : pr.getKeyword();
            List<String> stops = pr == null ? List.of() : pr.getStopWords();
            populateCatalog(CatalogLoader.loadOlderThan6Months(file, kw, stops), "файла");
        } catch (Exception ex) {
            batchStatusLabel.setText("Ошибка чтения файла: " + ex.getMessage());
            batchStatusLabel.setForeground(RED);
        }
    }

    /** Выгрузка каталога из Bazon: контрактные детали выбранного типа старше 6 мес, свободные на «Ткацкой». */
    private void loadFromBazon() {
        AnalysisProfiles.Profile pr = prof(profileCombo);
        if (pr == null) return;
        bazonBtn.setEnabled(false);
        batchStatusLabel.setText("Загрузка из Bazon (" + pr.getName() + ")…");
        batchStatusLabel.setForeground(DIM);
        executor.submit(() -> {
            try {
                List<CatalogLoader.CatalogItem> items = bazonClient.fetchContractParts(
                        pr.getBazonPartnameIds(), LocalDateTime.now().minusMonths(6));
                SwingUtilities.invokeLater(() -> { populateCatalog(items, "Bazon"); bazonBtn.setEnabled(true); });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    batchStatusLabel.setText("Ошибка Bazon: " + ex.getMessage());
                    batchStatusLabel.setForeground(RED);
                    bazonBtn.setEnabled(true);
                });
            }
        });
    }

    /** Заполняет таблицу каталога (общий код для файла и Bazon). */
    private void populateCatalog(List<CatalogLoader.CatalogItem> items, String src) {
        catalogItems = items;
        batchModel.setRowCount(0);
        for (CatalogLoader.CatalogItem it : catalogItems) {
            batchModel.addRow(new Object[]{
                    it.itemNumber(), it.oem(), it.name(), auto(it),
                    it.price() != null && it.price().signum() > 0 ? it.price().toPlainString() : "—",
                    it.created().format(D), "—", "—", "Ожидает", "—"
            });
        }
        int count = catalogItems.size();
        batchResults = new ArrayList<>(Collections.nCopies(count, null));
        batchStatusLabel.setText("Загружено из " + src + " (старше 6 мес): " + count);
        batchStatusLabel.setForeground(count > 0 ? GREEN : YELLOW);
        batchAnalyzeBtn.setEnabled(count > 0);
        setBatchLink(null);
    }

    private static String auto(CatalogLoader.CatalogItem it) {
        return (nz(it.brand()) + " " + nz(it.model())).trim();
    }

    private ExcelReport createReport() {
        try {
            String name = "availability-report_"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".xlsx";
            Path path = Paths.get(name).toAbsolutePath();
            return new ExcelReport(path);
        } catch (Exception e) {
            System.err.println("Не удалось создать Excel-отчёт: " + e.getMessage());
            return null;
        }
    }

    private void runBatchAnalysis() {
        if (catalogItems.isEmpty()) return;

        batchStopped = false;
        batchAnalyzeBtn.setEnabled(false);
        batchStopBtn.setEnabled(true);
        analyzeBtn.setEnabled(false);

        for (int i = 0; i < batchModel.getRowCount(); i++) {
            batchModel.setValueAt("—", i, 6);
            batchModel.setValueAt("—", i, 7);
            batchModel.setValueAt("Ожидает", i, 8);
            batchModel.setValueAt("—", i, 9);
        }
        batchResults = new ArrayList<>(Collections.nCopies(catalogItems.size(), null));
        setBatchLink(null);

        final String company = companyField.getText().trim();
        AnalysisProfiles.Profile pr = prof(profileCombo);
        final List<String> stops = pr == null ? List.of() : pr.getStopWords();
        int limitVal = 0;
        try { limitVal = Integer.parseInt(batchLimitField.getText().trim()); } catch (Exception ignored) {}
        final int total = (limitVal > 0 && limitVal < catalogItems.size()) ? limitVal : catalogItems.size();
        final int lanes = Math.max(1, priceAnalyzer.laneCount());

        priceAnalyzer.resetBreakers();

        executor.submit(() -> {
            final ExcelReport report = createReport();
            final java.util.concurrent.atomic.AtomicInteger cursor = new java.util.concurrent.atomic.AtomicInteger(0);
            final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);

            ExecutorService lanePool = Executors.newFixedThreadPool(lanes, r -> {
                Thread t = new Thread(r, "lane-worker");
                t.setDaemon(true);
                return t;
            });

            for (int L = 0; L < lanes; L++) {
                final int laneId = L;
                lanePool.submit(() -> {
                    while (!batchStopped) {
                        if (priceAnalyzer.isProxyDown(laneId) || priceAnalyzer.isCaptchaBlocked(laneId)) {
                            SwingUtilities.invokeLater(() -> {
                                batchStatusLabel.setText("Дорожка " + laneId + ": прокси/капча — остановлена. Смените IP.");
                                batchStatusLabel.setForeground(RED);
                            });
                            break;
                        }
                        int idx = cursor.getAndIncrement();
                        if (idx >= total) break;

                        final CatalogLoader.CatalogItem it = catalogItems.get(idx);
                        final int rowIdx = idx;
                        SwingUtilities.invokeLater(() -> batchModel.setValueAt("Анализ (L" + laneId + ")...", rowIdx, 8));

                        try {
                            AvailabilityResult res = priceAnalyzer.analyzeCatalogLane(
                                    it.oem(), it.price(), company, laneId, stops);
                            if (report != null)
                                report.append(it.oem(), it.itemNumber(), it.name(), auto(it), it.price(),
                                        it.created().format(D), res);
                            final int d = done.incrementAndGet();
                            SwingUtilities.invokeLater(() -> {
                                batchResults.set(rowIdx, res);
                                batchModel.setValueAt(res.getBarnaulCount(), rowIdx, 6);
                                batchModel.setValueAt(res.isSearchedSiberia() ? res.getSiberiaCount() : "—", rowIdx, 7);
                                batchModel.setValueAt(shortStatus(res.getColor()), rowIdx, 8);
                                batchModel.setValueAt(res.isReprice(), rowIdx, 9);
                                batchStatusLabel.setText("Готово " + d + " / " + total + " (дорожек: " + lanes + ")");
                                batchStatusLabel.setForeground(BLUE);
                                batchTable.repaint();
                            });
                        } catch (Exception ex) {
                            if (report != null) report.append(it.oem(), it.itemNumber(), it.name(), auto(it), it.price(),
                                    it.created().format(D), null);
                            SwingUtilities.invokeLater(() -> batchModel.setValueAt("Ошибка", rowIdx, 8));
                        }
                    }
                });
            }

            lanePool.shutdown();
            try { lanePool.awaitTermination(24, java.util.concurrent.TimeUnit.HOURS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

            if (report != null) report.close();
            final String reportInfo = report == null ? ""
                    : "  •  Excel: " + report.path().getFileName() + " (" + report.rows() + " строк)";

            SwingUtilities.invokeLater(() -> {
                batchAnalyzeBtn.setEnabled(true);
                batchStopBtn.setEnabled(false);
                analyzeBtn.setEnabled(true);
                batchStatusLabel.setText((batchStopped ? "Остановлено" : "Анализ завершён") + reportInfo);
                batchStatusLabel.setForeground(batchStopped ? YELLOW : GREEN);
            });
        });
    }

    private String shortStatus(AvailabilityResult.Color c) {
        return switch (c) {
            case RED    -> "Дефицит";
            case PURPLE -> "Мало (Сибирь)";
            case YELLOW -> "Мало (Барнаул)";
            case NONE   -> "OK";
        };
    }

    // ═══════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════

    private static String nz(String s) { return s == null ? "" : s; }

    private void copy(String s) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
    }

    private JLabel label(String text) { return new JLabel(text); }

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
}
