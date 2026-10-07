package ru.retail.service.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
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

    /**
     * Постоянный домен ngrok (бесплатный тариф даёт один статический, вида {@code xxx.ngrok-free.app}).
     * Задан — ngrok идёт ПЕРВЫМ провайдером, и публичная ссылка перестаёт меняться при переподключении.
     * Пусто — работаем как раньше: cloudflared → serveo → localhost.run со случайными именами.
     * Токен здесь НЕ хранится: он прописывается один раз командой {@code ngrok config add-authtoken …}
     * и лежит в профиле пользователя, вне репозитория.
     */
    @Value("${tunnel.ngrok-domain:}")
    private String ngrokDomain;

    /**
     * Токен агента ngrok. Задан — передаём его флагом {@code --authtoken}, и туннель поднимается
     * на любой машине без предварительного {@code ngrok config add-authtoken} (у Windows-дистрибутива
     * профиля пользователя с ngrok.yml нет). Пусто — агент берёт токен из профиля, как раньше.
     */
    @Value("${tunnel.ngrok-authtoken:}")
    private String ngrokAuthtoken;

    @Getter
    private String publicUrl;

    @Getter
    private String localUrl;

    private volatile Process tunnelProcess;
    private final CountDownLatch urlReady = new CountDownLatch(1);

    /** Сколько ждём URL от одного провайдера, прежде чем перейти к следующему. */
    private static final long PROVIDER_WAIT_MS = 25_000;
    /** Как часто сторож проверяет, что публичный адрес реально отвечает. */
    private static final long HEALTH_PERIOD_MS = 30_000;
    private static final int HEALTH_TIMEOUT_MS = 10_000;
    /** Сколько неудачных проверок подряд считаем поломкой туннеля (одиночные 502 бывают на ровном месте). */
    private static final int HEALTH_FAILS_BEFORE_RECONNECT = 2;
    /**
     * Как часто пытаться вернуться с запасного туннеля на постоянный домен. Возврат = несколько
     * секунд недоступности (гасим рабочий процесс, поднимаем ngrok), поэтому редко.
     */
    private static final long RESTORE_PREFERRED_PERIOD_MS = 10 * 60_000;

    /** Индекс поднятого провайдера в {@link #providers} (−1 — публичного адреса нет). */
    private volatile int activeProvider = -1;
    /** Общее ожидание URL для потребителей (3 провайдера × PROVIDER_WAIT_MS + запас). */
    private static final long TOTAL_WAIT_MS = 90_000;

    // Порядок попыток: cloudflared → serveo → localhost.run.
    // cloudflared первым намеренно: машина работает через VPN с потерей пакетов, а ssh-туннели
    // (serveo/localhost.run) на таком канале рвутся — соединение живёт, но проксирование отваливается,
    // и снаружи страница отдаёт 502. cloudflared сам переустанавливает соединение и переживает потери
    // заметно лучше.
    // ВАЖНО: --protocol http2 обязателен. В режиме auto cloudflared берёт QUIC (UDP), который через
    // этот VPN не ходит: туннель поднимается и адрес выдаётся, но КАЖДЫЙ запрос отдаёт 530 (проверено).
    private static final List<TunnelProvider> FALLBACK_PROVIDERS = List.of(
            new TunnelProvider(
                    List.of("cloudflared", "tunnel", "--url", "http://localhost:{port}", "--protocol", "http2"),
                    Pattern.compile("(https://[a-z0-9][a-z0-9-]+\\.trycloudflare\\.com)"),
                    "cloudflared", false
            ),
            new TunnelProvider(
                    List.of("ssh", "-o", "StrictHostKeyChecking=no", "-o", "ServerAliveInterval=30",
                            "-R", "80:localhost:{port}", "serveo.net"),
                    // URL вида https://xxx.serveousercontent.com (с ANSI-кодами в строке)
                    Pattern.compile("(https://[a-zA-Z0-9-]+\\.serveousercontent\\.com)"),
                    "serveo.net", false
            ),
            new TunnelProvider(
                    List.of("ssh", "-o", "StrictHostKeyChecking=no", "-o", "ServerAliveInterval=30",
                            "-R", "80:localhost:{port}", "nokey@localhost.run"),
                    Pattern.compile("(https://[a-zA-Z0-9-]+\\.lhr\\.life)"),
                    "localhost.run", false
            )
    );

    /**
     * @param stableUrl адрес НЕ меняется при перезапуске провайдера (ngrok со статическим доменом).
     *                  У quick-туннелей (trycloudflare/serveo/lhr.life) каждый запуск даёт новое имя,
     *                  поэтому их перезапуск ссылку не спасает — там сразу идём к следующему.
     */
    record TunnelProvider(List<String> cmdTemplate, Pattern urlPattern, String name, boolean stableUrl) {}

    /** Реальный список провайдеров этого запуска: с ngrok впереди, если задан постоянный домен. */
    private volatile List<TunnelProvider> providers = FALLBACK_PROVIDERS;

    /**
     * ngrok со статическим доменом: единственный из бесплатных, у кого ссылка НЕ меняется при
     * переподключении — заказчику можно отдать один адрес навсегда. Токен берётся из профиля
     * пользователя ({@code ngrok config add-authtoken …}), в конфиге приложения только домен.
     */
    private static TunnelProvider ngrokProvider(String domain) {
        return new TunnelProvider(
                List.of("ngrok", "http", "--domain=" + domain, "--log", "stdout", "--log-format", "logfmt", "{port}"),
                Pattern.compile("(https://" + Pattern.quote(domain) + ")"),
                "ngrok (" + domain + ")", true);
    }

    public void start() {
        localUrl = "http://" + lanAddress() + ":" + port;
        log.info("Локальный адрес (сеть): {}", localUrl);

        if (ngrokDomain != null && !ngrokDomain.isBlank()) {
            String d = ngrokDomain.trim().replaceFirst("^https?://", "").replaceAll("/+$", "");
            List<TunnelProvider> list = new ArrayList<>();
            list.add(ngrokProvider(d));                  // постоянный адрес — приоритет
            list.addAll(FALLBACK_PROVIDERS);             // запасные, если ngrok не поднялся
            providers = List.copyOf(list);
            log.info("Туннель: постоянный домен ngrok — https://{}", d);
        }

        // Перебор провайдеров — в фоне: каждый ждёт URL до PROVIDER_WAIT_MS,
        // старт приложения и открытие окна на это не завязываем.
        Thread starter = new Thread(() -> {
            connect(0);
            startWatchdog();
        }, "tunnel-starter");
        starter.setDaemon(true);
        starter.start();
    }

    /**
     * Адрес машины в локальной сети — по нему страница открывается с телефона/ноутбука рядом.
     * Виртуальные интерфейсы (VPN {@code utun*}, docker, мосты) пропускаем: раньше сюда попадал
     * адрес VPN, который снаружи недоступен, и «локальная» ссылка не открывалась ни у кого.
     */
    private String lanAddress() {
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isPointToPoint() || ni.isVirtual()) continue;
                String name = ni.getName();
                if (name.startsWith("utun") || name.startsWith("ipsec") || name.startsWith("ppp")
                        || name.startsWith("docker") || name.startsWith("br-") || name.startsWith("bridge")) continue;
                for (java.net.InetAddress addr : java.util.Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress())
                        return addr.getHostAddress();
                }
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "localhost";
        }
    }

    /**
     * Перебор провайдеров начиная с {@code from} (по кругу). Возвращает индекс поднявшегося
     * провайдера или -1, если не поднялся никто.
     */
    private int connect(int from) {
        for (int i = 0; i < providers.size(); i++) {
            int idx = (from + i) % providers.size();
            if (tryProvider(providers.get(idx))) {
                activeProvider = idx;
                return idx;
            }
        }
        log.warn("Ни один туннель не запустился. Доступен только локальный адрес: {}", localUrl);
        urlReady.countDown();
        activeProvider = -1;
        return -1;
    }

    /**
     * Сторож туннеля. Провайдер может «умереть тихо»: ssh-процесс жив, а публичный адрес отдаёт
     * 502 — снаружи страница просто недоступна, и понять это изнутри приложения было нельзя.
     * Раз в {@link #HEALTH_PERIOD_MS} дёргаем свой же {@code /api/v1/info} ЧЕРЕЗ публичный адрес;
     * после {@link #HEALTH_FAILS_BEFORE_RECONNECT} неудач подряд гасим процесс и поднимаем туннель
     * заново. Новый URL сразу виден в {@code /info}.
     *
     * <p><b>Постоянный адрес важнее быстрого восстановления.</b> У провайдера со статическим доменом
     * (ngrok) перезапуск возвращает ТУ ЖЕ ссылку, поэтому сначала пробуем поднять его же — иначе
     * один сбой уводил на quick-туннель со случайным именем, и ссылка, отданная заказчику, умирала
     * безвозвратно. Если статический домен не поднялся — уходим к следующему провайдеру, но
     * продолжаем раз в {@link #RESTORE_PREFERRED_PERIOD_MS} пытаться вернуться на него.
     */
    private void startWatchdog() {
        Thread watchdog = new Thread(() -> {
            int fails = 0;
            long lastRestoreTry = System.currentTimeMillis();
            while (true) {
                try { Thread.sleep(HEALTH_PERIOD_MS); } catch (InterruptedException e) { return; }
                String url = publicUrl;
                if (url == null) {                      // туннеля нет вовсе — пробуем поднять заново
                    if (connect(0) >= 0) fails = 0;     // с начала списка: постоянный домен в приоритете
                    continue;
                }
                if (healthy(url)) {
                    fails = 0;
                    // Живы, но на запасном провайдере со случайным адресом — периодически
                    // возвращаемся на постоянный домен, чтобы ссылка снова стала неизменной.
                    if (shouldRestorePreferred()
                            && System.currentTimeMillis() - lastRestoreTry >= RESTORE_PREFERRED_PERIOD_MS) {
                        lastRestoreTry = System.currentTimeMillis();
                        restorePreferred();
                    }
                    continue;
                }
                fails++;
                log.warn("Туннель {}: публичный адрес не отвечает ({}/{}) — {}",
                        providerName(activeProvider), fails, HEALTH_FAILS_BEFORE_RECONNECT, url);
                if (fails < HEALTH_FAILS_BEFORE_RECONNECT) continue;

                int broken = activeProvider;
                boolean stable = broken >= 0 && broken < providers.size() && providers.get(broken).stableUrl();
                stopProcess();
                publicUrl = null;
                fails = 0;

                // Статический домен — сначала он же: так ссылка у заказчика не меняется.
                if (stable) {
                    log.warn("Туннель {}: перезапускаю того же провайдера (адрес постоянный)", providerName(broken));
                    if (tryProvider(providers.get(broken))) {
                        activeProvider = broken;
                        log.info("Туннель восстановлен на том же адресе: {}", publicUrl);
                        continue;
                    }
                    publicUrl = null;
                }

                log.warn("Туннель {}: переподключаюсь на следующий провайдер", providerName(broken));
                int idx = connect(broken + 1);
                if (idx >= 0) log.info("Туннель восстановлен через {}: {}", providerName(idx), publicUrl);
                lastRestoreTry = System.currentTimeMillis();
            }
        }, "tunnel-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** Публичный адрес считается живым, если наш собственный /info отвечает 200 (502/504 от туннеля — нет). */
    private boolean healthy(String url) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    java.net.URI.create(url + "/api/v1/info").toURL().openConnection();
            c.setConnectTimeout(HEALTH_TIMEOUT_MS);
            c.setReadTimeout(HEALTH_TIMEOUT_MS);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** Работаем на запасном провайдере, хотя первым в списке стоит постоянный домен? */
    private boolean shouldRestorePreferred() {
        return activeProvider > 0 && !providers.isEmpty() && providers.get(0).stableUrl();
    }

    /**
     * Пробует вернуть туннель на постоянный домен (провайдер 0). Текущий рабочий процесс гасим
     * только на время попытки: если ngrok не поднялся — сразу возвращаемся на запасного.
     */
    private void restorePreferred() {
        int current = activeProvider;
        log.info("Туннель: пробую вернуться на постоянный домен {}", providerName(0));
        stopProcess();
        publicUrl = null;
        if (tryProvider(providers.get(0))) {
            activeProvider = 0;
            log.info("Туннель снова на постоянном адресе: {}", publicUrl);
            return;
        }
        publicUrl = null;
        log.warn("Туннель: постоянный домен по-прежнему недоступен — остаюсь на запасном");
        int idx = connect(current);
        if (idx >= 0) log.info("Туннель: запасной поднят, адрес {}", publicUrl);
    }

    private String providerName(int idx) {
        return idx >= 0 && idx < providers.size() ? providers.get(idx).name() : "—";
    }

    private void stopProcess() {
        Process p = tunnelProcess;
        if (p == null) return;
        try {
            p.destroy();
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (Exception ignored) {}
    }

    /** @return true, если провайдер выдал публичный URL. */
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
            // Токен ngrok — через окружение дочернего процесса, а не аргументом командной строки:
            // аргументы видны в `ps` любому пользователю машины.
            if (binary.equals("ngrok") && ngrokAuthtoken != null && !ngrokAuthtoken.isBlank()) {
                pb.environment().put("NGROK_AUTHTOKEN", ngrokAuthtoken.trim());
            }
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            tunnelProcess = proc;

            // Последние строки вывода — чтобы показать причину, если URL не пришёл
            List<String> tail = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch gotUrl = new CountDownLatch(1);
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        log.debug("[{}] {}", provider.name(), line);
                        tail.add(line);
                        if (tail.size() > 10) tail.remove(0);
                        if (publicUrl == null) {
                            Matcher m = provider.urlPattern().matcher(line);
                            if (m.find()) {
                                // группа 1 если есть (паттерн с контекстом), иначе вся совпавшая строка
                                publicUrl = m.groupCount() > 0 ? m.group(1) : m.group();
                                log.info("╔════════════════════════════════════════════════════════╗");
                                log.info("║  Публичный адрес: {}  ║", publicUrl);
                                log.info("╚════════════════════════════════════════════════════════╝");
                                gotUrl.countDown();
                                urlReady.countDown();
                            }
                        }
                    }
                } catch (Exception ignored) {}
                gotUrl.countDown();   // поток вывода закрылся — процесс умер, ждать нечего
            }, provider.name() + "-reader");
            reader.setDaemon(true);
            reader.start();

            log.info("{} запущен, ожидаем публичный URL...", provider.name());
            gotUrl.await(PROVIDER_WAIT_MS, TimeUnit.MILLISECONDS);
            if (publicUrl != null) return true;

            // URL не пришёл (провайдер лёг / ssh отвалился) — гасим и пробуем следующий
            log.warn("{}: публичный URL не получен за {} с, пробуем следующий туннель. Вывод: {}",
                    provider.name(), PROVIDER_WAIT_MS / 1000,
                    tail.isEmpty() ? "(пусто)" : String.join(" | ", tail));
            proc.destroy();
            if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly();
            return false;

        } catch (Exception e) {
            log.warn("{} недоступен: {}", provider.name(), e.getMessage());
            return false;
        }
    }

    public String waitForPublicUrl() {
        try {
            urlReady.await(TOTAL_WAIT_MS, TimeUnit.MILLISECONDS);
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
