package ru.retail.service.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final MobileProxyService mobileProxyService;

    @Value("${drom.proxies:}")
    private List<String> proxies;
    @Value("${drom.proxy.url:}")
    private String singleProxy;
    @Value("${drom.headless:true}")
    private boolean headless;

    private final List<DromParser> lanes = new ArrayList<>();

    public DromParserPool(CaptchaSolverService captchaSolver, ApifyDromService apifyDromService,
                          LocalSocksProxy localSocksProxy, MobileProxyService mobileProxyService) {
        this.captchaSolver = captchaSolver;
        this.apifyDromService = apifyDromService;
        this.localSocksProxy = localSocksProxy;
        this.mobileProxyService = mobileProxyService;
    }

    @PostConstruct
    public void init() {
        List<String> px = new ArrayList<>();
        if (proxies != null) for (String s : proxies) if (s != null && !s.isBlank()) px.add(s.trim());
        if (px.isEmpty()) px.add(singleProxy == null ? "" : singleProxy);   // фолбэк: одна дорожка

        // change-IP ссылки по портам из API mobileproxy (для реактивной ротации при капче)
        Map<Integer, String> changeIpByPort = mobileProxyService.changeIpUrlsByPort();

        for (int i = 0; i < px.size(); i++) {
            String proxy = px.get(i);
            String changeIpUrl = resolveChangeIpUrl(proxy, changeIpByPort);
            DromParser lane = new DromParser(captchaSolver, apifyDromService, localSocksProxy,
                    proxy, Paths.get("drom-session-" + i + ".json"), headless, "L" + i, changeIpUrl);
            lane.init();
            lanes.add(lane);
            log.info("Дорожка пула L{} поднята (прокси: {}, ротация IP: {})",
                    i, mask(proxy), changeIpUrl != null ? "вкл" : "выкл");
        }
        log.info("Пул дорожек готов: {} шт.", lanes.size());
    }

    /** Сопоставляет прокси дорожки с change-IP ссылкой по номеру порта (host:PORT в URL прокси). */
    private static String resolveChangeIpUrl(String proxyUrl, Map<Integer, String> changeIpByPort) {
        if (proxyUrl == null || changeIpByPort.isEmpty()) return null;
        Matcher m = Pattern.compile(":(\\d+)(?:/|$)").matcher(proxyUrl);
        Integer port = null;
        while (m.find()) port = Integer.parseInt(m.group(1));   // последнее ":число" = порт прокси
        return port == null ? null : changeIpByPort.get(port);
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
