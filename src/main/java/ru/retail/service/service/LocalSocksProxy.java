package ru.retail.service.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Локальный HTTP CONNECT → SOCKS5 прокси-мост.
 * Chromium не поддерживает SOCKS5 с авторизацией напрямую,
 * поэтому запускаем локальный HTTP-прокси без авторизации,
 * который сам проходит авторизацию на удалённом SOCKS5.
 */
@Slf4j
@Component
public class LocalSocksProxy {

    @Value("${drom.proxy.url:}")
    private String proxyUrl;

    private ServerSocket server;
    private ExecutorService pool;
    private volatile int localPort;

    private String remoteHost;
    private int remotePort;
    private String user;
    private String pass;

    @PostConstruct
    public void start() {
        if (proxyUrl == null || proxyUrl.isBlank()) return;
        try {
            URI uri = new URI(proxyUrl);
            if (!uri.getScheme().startsWith("socks")) return;

            remoteHost = uri.getHost();
            remotePort = uri.getPort();
            String info = uri.getUserInfo();
            if (info != null) {
                int sep = info.indexOf(':');
                user = info.substring(0, sep);
                pass = info.substring(sep + 1);
            }

            server = new ServerSocket(0);
            localPort = server.getLocalPort();
            pool = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "local-proxy");
                t.setDaemon(true);
                return t;
            });
            pool.submit(this::acceptLoop);
            log.info("LocalSocksProxy: HTTP→SOCKS5 мост запущен на localhost:{}", localPort);
        } catch (Exception e) {
            log.error("LocalSocksProxy: не удалось запустить: {}", e.getMessage());
        }
    }

    /** Возвращает URL локального HTTP-прокси для Playwright, или null если мост не запущен. */
    public String getLocalProxyUrl() {
        return localPort > 0 ? "http://localhost:" + localPort : null;
    }

    @PreDestroy
    public void stop() {
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        if (pool != null) pool.shutdown();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                client.setSoTimeout(30_000);
                pool.submit(() -> handleClient(client));
            } catch (Exception e) {
                if (!server.isClosed()) log.warn("LocalSocksProxy accept: {}", e.getMessage());
            }
        }
    }

    private void handleClient(Socket client) {
        try (client) {
            InputStream cin = client.getInputStream();
            OutputStream cout = client.getOutputStream();

            // Читаем строку CONNECT host:port HTTP/1.1
            String firstLine = readLine(cin);
            if (firstLine == null || !firstLine.startsWith("CONNECT ")) {
                cout.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes());
                return;
            }
            String[] parts = firstLine.split(" ");
            String[] hp = parts[1].split(":");
            String targetHost = hp[0];
            int targetPort = Integer.parseInt(hp[1]);

            // Дочитываем заголовки
            while (true) {
                String line = readLine(cin);
                if (line == null || line.isEmpty()) break;
            }

            // Подключаемся к удалённому SOCKS5
            Socket socks = connectViaSocks5(targetHost, targetPort);

            // Сообщаем клиенту об успехе
            cout.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes());
            cout.flush();

            // Двунаправленный туннель
            tunnel(cin, cout, socks);

        } catch (Exception e) {
            log.warn("LocalSocksProxy client error: {}", e.getMessage());
        }
    }

    private Socket connectViaSocks5(String host, int port) throws IOException {
        Socket sock = new Socket(remoteHost, remotePort);
        sock.setSoTimeout(20_000);
        InputStream rawIn   = sock.getInputStream();
        OutputStream rawOut = sock.getOutputStream();

        // Предлагаем оба метода: no-auth (0x00) и username/password (0x02) — как curl
        rawOut.write(new byte[]{0x05, 0x02, 0x00, 0x02});
        rawOut.flush();

        int sVer    = rawIn.read();
        int sMethod = rawIn.read();
        log.debug("SOCKS5 handshake: ver={} method={}", sVer, sMethod);

        if (sMethod == 0x02 && user != null && !user.isBlank()) {
            // RFC 1929 username/password auth — один атомарный write
            byte[] u = user.getBytes(StandardCharsets.UTF_8);
            byte[] p = pass.getBytes(StandardCharsets.UTF_8);
            byte[] auth = new byte[3 + u.length + p.length];
            auth[0] = 0x01;
            auth[1] = (byte) u.length;
            System.arraycopy(u, 0, auth, 2, u.length);
            auth[2 + u.length] = (byte) p.length;
            System.arraycopy(p, 0, auth, 3 + u.length, p.length);
            rawOut.write(auth);
            rawOut.flush();
            rawIn.read(); // sub-ver
            int status = rawIn.read();
            if (status != 0x00) throw new IOException("SOCKS5 auth failed, status=" + status);
            log.debug("SOCKS5 auth OK");
        } else if (sMethod == 0xFF) {
            throw new IOException("SOCKS5: сервер отверг все методы авторизации");
        }
        // sMethod == 0x00: авторизация не нужна

        // CONNECT к целевому хосту (IPv4 — резолвим локально, как curl)
        InetAddress addr = InetAddress.getByName(host);
        byte[] ip = addr.getAddress(); // 4 bytes for IPv4
        byte[] connect = new byte[10];
        connect[0] = 0x05; connect[1] = 0x01; connect[2] = 0x00; // ver CONNECT RSV
        connect[3] = 0x01;                                          // ATYP: IPv4
        System.arraycopy(ip, 0, connect, 4, 4);
        connect[8] = (byte) (port >>> 8);
        connect[9] = (byte) (port & 0xFF);
        rawOut.write(connect);
        rawOut.flush();

        // Читаем ответ
        rawIn.read(); // ver
        int rep = rawIn.read();
        if (rep != 0x00) throw new IOException("SOCKS5 CONNECT rejected, rep=" + rep);
        rawIn.read(); // rsv
        int atyp = rawIn.read();
        switch (atyp) {
            case 0x01 -> rawIn.readNBytes(4);
            case 0x03 -> rawIn.readNBytes(rawIn.read());
            case 0x04 -> rawIn.readNBytes(16);
        }
        rawIn.readNBytes(2); // bound port
        log.debug("SOCKS5 CONNECT granted: {}:{}", host, port);

        return sock;
    }

    private void tunnel(InputStream cin, OutputStream cout, Socket socks) {
        try (socks) {
            InputStream sin  = socks.getInputStream();
            OutputStream sout = socks.getOutputStream();

            Thread t = new Thread(() -> {
                try { copy(cin, sout); } catch (Exception ignored) {}
                finally { try { socks.close(); } catch (Exception ignored) {} }
            }, "local-proxy-up");
            t.setDaemon(true);
            t.start();
            try { copy(sin, cout); } catch (Exception ignored) {}
            try { t.join(3_000); } catch (InterruptedException ignored) {}
        } catch (Exception ignored) {}
    }

    private void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            out.flush();
        }
    }

    private String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') { in.read(); return sb.toString(); }
            if (b == '\n') return sb.toString();
            sb.append((char) b);
        }
        return null;
    }
}
