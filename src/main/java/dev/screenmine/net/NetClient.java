package dev.screenmine.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.screenmine.Cfg;
import dev.screenmine.ScreenMineClient;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Newline-delimited JSON client for the ScreenMine companion server.
 *
 * <p>Runs a reader and a writer thread; incoming packets are parked in a queue
 * and drained on the client tick so all mod state is touched from the main
 * thread only.
 */
public final class NetClient {

    public enum Status {OFFLINE, CONNECTING, ONLINE}

    private final Queue<JsonObject> inbound = new ConcurrentLinkedQueue<>();
    private final Queue<String> outbound = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Status status = Status.OFFLINE;
    private volatile String lastError = "";
    private volatile Socket socket;
    private volatile String playerName = "";

    public Status status() {
        return status;
    }

    public boolean isOnline() {
        return status == Status.ONLINE;
    }

    public String lastError() {
        return lastError;
    }

    public String playerName() {
        return playerName;
    }

    /** Starts the background connection loop. Safe to call repeatedly. */
    public void start(String playerName) {
        this.playerName = playerName;
        if (!Cfg.serverConfigured()) {
            // No server address configured: stay offline quietly. Everything
            // except watching together works without one, and nagging about a
            // connection the player never asked for would just be noise.
            status = Status.OFFLINE;
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(this::loop, "ScreenMine-Net");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running.set(false);
        closeSocket();
    }

    /** Forces an immediate reconnect attempt. */
    public void reconnect() {
        closeSocket();
    }

    public JsonObject poll() {
        return inbound.poll();
    }

    public void send(JsonObject packet) {
        if (status == Status.OFFLINE) {
            return;
        }
        outbound.add(packet.toString());
        Socket s = socket;
        if (s != null) {
            synchronized (this) {
                notifyAll();
            }
        }
    }

    // ------------------------------------------------------------------ //

    private void loop() {
        while (running.get()) {
            try {
                status = Status.CONNECTING;
                Socket s = new Socket();
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress(Cfg.SERVER_HOST, Cfg.SERVER_PORT), 8000);
                socket = s;

                BufferedWriter out = new BufferedWriter(
                        new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));

                outbound.clear();
                status = Status.ONLINE;
                lastError = "";

                JsonObject hello = new JsonObject();
                hello.addProperty("t", "hello");
                hello.addProperty("name", playerName);
                hello.addProperty("ver", Cfg.PROTOCOL_VERSION);
                outbound.add(hello.toString());

                Thread writer = new Thread(() -> writeLoop(out), "ScreenMine-Net-Writer");
                writer.setDaemon(true);
                writer.start();

                String line;
                while (running.get() && (line = in.readLine()) != null) {
                    try {
                        JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
                        inbound.add(obj);
                    } catch (Exception ex) {
                        ScreenMineClient.LOG.warn("bad packet from server: {}", line);
                    }
                }
            } catch (Exception ex) {
                lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                ScreenMineClient.LOG.warn("ScreenMine connection failed: {}", lastError);
            } finally {
                closeSocket();
                boolean wasOnline = status == Status.ONLINE;
                status = Status.OFFLINE;
                // Only announce a real drop. A failed reconnect attempt is not a
                // disconnect, and firing one every retry made the mod reset
                // session state several times a minute while the server was down.
                if (wasOnline) {
                    JsonObject dc = new JsonObject();
                    dc.addProperty("t", "__disconnected");
                    inbound.add(dc);
                }
            }

            if (!running.get()) {
                break;
            }
            try {
                Thread.sleep(Cfg.RECONNECT_DELAY_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        status = Status.OFFLINE;
    }

    private void writeLoop(BufferedWriter out) {
        try {
            while (running.get() && socket != null && !socket.isClosed()) {
                String msg = outbound.poll();
                if (msg == null) {
                    synchronized (this) {
                        wait(200);
                    }
                    continue;
                }
                out.write(msg);
                out.write('\n');
                out.flush();
            }
        } catch (Exception ignored) {
            // socket closed; the read loop handles reconnection
        }
    }

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
                // nothing useful to do
            }
        }
        synchronized (this) {
            notifyAll();
        }
    }
}
