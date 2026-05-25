package ru.retail.service.ui;

import ru.retail.service.dto.AggregationResult;
import ru.retail.service.dto.PartPrice;
import ru.retail.service.service.PriceAnalyzer;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainWindow extends JFrame {

    private static final Color GREEN  = new Color(34, 197, 94);
    private static final Color YELLOW = new Color(234, 179, 8);
    private static final Color RED    = new Color(239, 68, 68);
    private static final Color DIM    = new Color(160, 160, 160);

    private final PriceAnalyzer priceAnalyzer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "analysis-thread");
        t.setDaemon(true);
        return t;
    });

    // ── Inputs ──
    private JTextField oemField;
    private JTextField urlField;
    private JComboBox<String> regionCombo;
    private JTextField priceField;
    private JButton analyzeBtn;
    private JLabel statusLabel;

    // ── Results ──
    private JScrollPane resultScroll;
    private JLabel recPriceLabel;
    private JLabel confidenceLabel;
    private JLabel statsLabel;
    private JLabel marketNoteLabel;
    private JTextArea aiReasonArea;
    private DefaultTableModel cityModel;
    private DefaultTableModel siberiaModel;
    private JPanel siberiaSection;

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
        setMinimumSize(new Dimension(900, 680));
        setPreferredSize(new Dimension(1100, 820));

        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.add(buildFormPanel(), BorderLayout.NORTH);

        resultScroll = new JScrollPane(buildResultPanel());
        resultScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(60, 60, 60)));
        resultScroll.getVerticalScrollBar().setUnitIncrement(16);
        root.add(resultScroll, BorderLayout.CENTER);

        setContentPane(root);
        pack();
        setLocationRelativeTo(null);
    }

    private JPanel buildFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));

        GridBagConstraints lbl = gbc(0, 0, 0.0, new Insets(5, 0, 5, 8));
        lbl.anchor = GridBagConstraints.WEST;
        GridBagConstraints fld = gbc(1, 0, 1.0, new Insets(5, 0, 5, 0));

        // OEM
        lbl.gridy = 0; panel.add(label("OEM номер:"), lbl);
        fld.gridy = 0;
        oemField = new JTextField();
        oemField.setToolTipText("Например: 4785033210");
        panel.add(oemField, fld);

        // URL
        lbl.gridy = 1; panel.add(label("URL моего объявления:"), lbl);
        fld.gridy = 1;
        urlField = new JTextField();
        urlField.setToolTipText("Полная ссылка на baza.drom.ru");
        panel.add(urlField, fld);

        // Region + Price
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

        // Button
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

        // ── Recommended price ──
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

        // ── Stats ──
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

        // ── AI reason ──
        panel.add(sectionHeader("Обоснование AI"));
        aiReasonArea = textArea(5);
        panel.add(wrapScroll(aiReasonArea, 100));
        panel.add(vgap(14));

        // ── City competitors ──
        panel.add(sectionHeader("Конкуренты в городе"));
        String[] cols = {"Цена, ₽", "Название", "Продавец", "Дата", "URL"};
        cityModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        panel.add(buildTable(cityModel));
        panel.add(vgap(14));

        // ── Siberia competitors ──
        siberiaSection = new JPanel();
        siberiaSection.setLayout(new BoxLayout(siberiaSection, BoxLayout.Y_AXIS));
        siberiaSection.setAlignmentX(LEFT_ALIGNMENT);
        siberiaSection.add(sectionHeader("Конкуренты в Новосибирске"));
        siberiaModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        siberiaSection.add(buildTable(siberiaModel));
        siberiaSection.setVisible(false);
        panel.add(siberiaSection);

        return panel;
    }

    // ═══════════════════════════════════════════════════════
    // ANALYSIS
    // ═══════════════════════════════════════════════════════

    private void runAnalysis() {
        String oem    = oemField.getText().trim();
        String url    = urlField.getText().trim();
        String region = ((String) regionCombo.getSelectedItem()).trim();
        String priceText = priceField.getText().trim();

        if (oem.isEmpty() || url.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Заполните OEM номер и URL объявления", "Ошибка", JOptionPane.WARNING_MESSAGE);
            return;
        }

        BigDecimal myPrice = BigDecimal.ZERO;
        if (!priceText.isEmpty()) {
            try {
                myPrice = new BigDecimal(priceText.replaceAll("[^\\d.]", ""));
            } catch (NumberFormatException ex) {
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
        // Price
        if (r.getRecommendedPrice() != null) {
            recPriceLabel.setText(formatPrice(r.getRecommendedPrice()) + " ₽");
        }
        // Confidence color
        if (r.getAiConfidence() != null) {
            String conf = r.getAiConfidence().toLowerCase();
            Color c = conf.contains("высок") ? GREEN : conf.contains("средн") ? YELLOW : RED;
            confidenceLabel.setForeground(c);
            confidenceLabel.setText("уверенность: " + r.getAiConfidence());
        }

        // Stats
        statsLabel.setText(String.format(
                "Город: %d  •  Новосибирск: %d  •  Мин: %s ₽  •  Макс: %s ₽  •  Средняя: %s ₽  •  Медиана: %s ₽",
                r.getCityCompetitorCount(), r.getSiberiaCompetitorCount(),
                formatPrice(r.getMinPrice()), formatPrice(r.getMaxPrice()),
                formatPrice(r.getAvgPrice()), formatPrice(r.getMedianPrice())));
        if (r.getMarketNote() != null) marketNoteLabel.setText(r.getMarketNote());

        // AI reason
        aiReasonArea.setText(r.getAiReason() != null ? r.getAiReason() : "");
        aiReasonArea.setCaretPosition(0);

        // Tables
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
    // WIDGET HELPERS
    // ═══════════════════════════════════════════════════════

    private JScrollPane buildTable(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowHeight(24);
        table.getTableHeader().setReorderingAllowed(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Column widths
        int[] widths = {80, 360, 130, 120, 400};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        // Right-align price column
        DefaultTableCellRenderer rightAlign = new DefaultTableCellRenderer();
        rightAlign.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(0).setCellRenderer(rightAlign);

        // Double-click copies URL
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = table.getSelectedRow();
                    if (row >= 0) {
                        String urlVal = (String) model.getValueAt(row, 4);
                        if (urlVal != null && !urlVal.isEmpty()) {
                            Toolkit.getDefaultToolkit().getSystemClipboard()
                                    .setContents(new StringSelection(urlVal), null);
                            setStatus("URL скопирован в буфер", DIM);
                        }
                    }
                }
            }
        });

        JScrollPane sp = new JScrollPane(table,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        sp.setAlignmentX(LEFT_ALIGNMENT);
        sp.setPreferredSize(new Dimension(0, model == cityModel ? 220 : 180));
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, model == cityModel ? 220 : 180));
        return sp;
    }

    private JPanel hPanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    private JLabel label(String text) {
        return new JLabel(text);
    }

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

    private Component vgap(int h) {
        return Box.createRigidArea(new Dimension(0, h));
    }

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
