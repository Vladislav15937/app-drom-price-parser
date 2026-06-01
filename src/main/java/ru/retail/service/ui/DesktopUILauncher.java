package ru.retail.service.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import ru.retail.service.service.PriceAnalyzer;
import ru.retail.service.service.TunnelService;

import javax.swing.*;

@Component
@RequiredArgsConstructor
public class DesktopUILauncher implements ApplicationListener<ApplicationReadyEvent> {

    private final PriceAnalyzer priceAnalyzer;
    private final TunnelService tunnelService;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        tunnelService.start();
        SwingUtilities.invokeLater(() -> {
            FlatDarkLaf.setup();
            UIManager.put("defaultFont", new java.awt.Font("Segoe UI", java.awt.Font.PLAIN, 14));
            new MainWindow(priceAnalyzer, tunnelService).setVisible(true);
        });
    }
}
