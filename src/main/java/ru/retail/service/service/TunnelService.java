package ru.retail.service.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class TunnelService {

    @Value("${server.port:8081}")
    private int port;

    @Getter
    private String publicUrl;

    @Getter
    private String localUrl;

    private Process tunnelProcess;
    private final CountDownLatch urlReady = new CountDownLatch(1);

    // Порядок попыток: serveo → localhost.run → cloudflared
    private static final List<TunnelProvider> PROVIDERS = List.of(
            new TunnelProvider(
                    List.of("ssh", "-o", "StrictHostKeyChecking=no", "-o", "ServerAliveInterval=30",
                            "-R", "80:localhost:{port}", "serveo.net"),
                    // URL вида https://xxx.serveousercontent.com (с ANSI-кодами в строке)
                    Pattern.compile("(https://[a-zA-Z0-9-]+\\.serveousercontent\\.com)"),
                    "serveo.net"
            ),
            new TunnelProvider(
                    List.of("ssh", "-o", "StrictHostKeyChecking=no", "-o", "ServerAliveInterval=30",
                            "-R", "80:localhost:{port}", "nokey@localhost.run"),
                    Pattern.compile("(https://[a-zA-Z0-9-]+\\.lhr\\.life)"),
                    "localhost.run"
            ),
            new TunnelProvider(
                    List.of("cloudflared", "tunnel", "--url", "http://localhost:{port}", "--protocol", "http2"),
                    Pattern.compile("(https://[a-z0-9][a-z0-9-]+\\.trycloudflare\\.com)"),
                    "cloudflared"
            )
    );

    record TunnelProvider(List<String> cmdTemplate, Pattern urlPattern, String name) {}

    public void start() {
        try {
            String ip = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
                    .stream()
                    .filter(ni -> { try { return ni.isUp() && !ni.isLoopback(); } catch (Exception e) { return false; } })
                    .flatMap(ni -> java.util.Collections.list(ni.getInetAddresses()).stream())
                    .filter(addr -> addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress())
                    .map(java.net.InetAddress::getHostAddress)
                    .findFirst()
                    .orElse(InetAddress.getLocalHost().getHostAddress());
            localUrl = "http://" + ip + ":" + port;
        } catch (Exception e) {
            localUrl = "http://localhost:" + port;
        }
        log.info("Локальный адрес (сеть): {}", localUrl);

        for (TunnelProvider provider : PROVIDERS) {
            if (tryProvider(provider)) return;
        }
        log.warn("Ни один туннель не запустился. Доступен только локальный адрес: {}", localUrl);
        urlReady.countDown();
    }

    private boolean tryProvider(TunnelProvider provider) {
        List<String> cmd = provider.cmdTemplate().stream()
                .map(s -> s.replace("{port}", String.valueOf(port)))
                .toList();
        try {
            // Проверяем доступность команды (первый элемент — бинарник)
            String binary = cmd.get(0);
            if (!binary.equals("ssh")) {
                ProcessBuilder check = new ProcessBuilder(binary, "version");
                check.redirectErrorStream(true);
                Process p = check.start();
                if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroy(); return false; }
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            tunnelProcess = pb.start();

            CountDownLatch started = new CountDownLatch(1);
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(tunnelProcess.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        log.trace("[{}] {}", provider.name(), line);
                        if (publicUrl == null) {
                            Matcher m = provider.urlPattern().matcher(line);
                            if (m.find()) {
                                // группа 1 если есть (паттерн с контекстом), иначе вся совпавшая строка
                                publicUrl = m.groupCount() > 0 ? m.group(1) : m.group();
                                log.info("╔════════════════════════════════════════════════════════╗");
                                log.info("║  Публичный адрес: {}  ║", publicUrl);
                                log.info("╚════════════════════════════════════════════════════════╝");
                                urlReady.countDown();
                            }
                        }
                    }
                } catch (Exception ignored) {}
                started.countDown();
                urlReady.countDown();
            }, provider.name() + "-reader");
            reader.setDaemon(true);
            reader.start();

            log.info("{} запущен, ожидаем публичный URL...", provider.name());
            return true;

        } catch (Exception e) {
            log.debug("{} недоступен: {}", provider.name(), e.getMessage());
            return false;
        }
    }

    public String waitForPublicUrl() {
        try {
            urlReady.await(40, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return publicUrl;
    }

    @PreDestroy
    public void stop() {
        if (tunnelProcess != null && tunnelProcess.isAlive()) {
            tunnelProcess.destroy();
            log.info("Туннель остановлен");
        }
    }
}
