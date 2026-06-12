package ru.retail.service.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Пул «дорожек» (lane) для параллельного батча: одна дорожка = свой Playwright-браузер + свой прокси (IP)
 * + своя сессия + свои предохранители. Число дорожек = число прокси в {@code drom.proxies}
 * (если список пуст — одна дорожка из {@code drom.proxy.url}).
 */
@Slf4j
@Service
public class DromParserPool {

    private final CaptchaSolverService captchaSolver;
    private final ApifyDromService apifyDromService;
    private final LocalSocksProxy localSocksProxy;

    @Value("${drom.proxies:}")
    private List<String> proxies;
    @Value("${drom.proxy.url:}")
    private String singleProxy;
    @Value("${drom.headless:true}")
    private boolean headless;

    private final List<DromParser> lanes = new ArrayList<>();

    public DromParserPool(CaptchaSolverService captchaSolver, ApifyDromService apifyDromService,
                          LocalSocksProxy localSocksProxy) {
        this.captchaSolver = captchaSolver;
        this.apifyDromService = apifyDromService;
        this.localSocksProxy = localSocksProxy;
    }

    @PostConstruct
    public void init() {
        List<String> px = new ArrayList<>();
        if (proxies != null) for (String s : proxies) if (s != null && !s.isBlank()) px.add(s.trim());
        if (px.isEmpty()) px.add(singleProxy == null ? "" : singleProxy);   // фолбэк: одна дорожка

        for (int i = 0; i < px.size(); i++) {
            DromParser lane = new DromParser(captchaSolver, apifyDromService, localSocksProxy,
                    px.get(i), Paths.get("drom-session-" + i + ".json"), headless, "L" + i);
            lane.init();
            lanes.add(lane);
            log.info("Дорожка пула L{} поднята (прокси: {})", i, mask(px.get(i)));
        }
        log.info("Пул дорожек готов: {} шт.", lanes.size());
    }

    public int size() { return lanes.size(); }

    public DromParser lane(int i) { return lanes.get(i); }

    @PreDestroy
    public void shutdown() {
        for (DromParser l : lanes) try { l.destroy(); } catch (Exception ignored) {}
    }

    private static String mask(String url) {
        return url == null ? "" : url.replaceAll(":[^@/]+@", ":***@");
    }
}
