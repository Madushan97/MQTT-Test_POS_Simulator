package com.hnb.wireme.tms.simulator;

import com.google.gson.Gson;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * POS terminal simulator for the TMS MQTT push channel.
 *
 * Topics (prefix = topic.prefix, sn = terminal serial number):
 *   publish   {prefix}/push-devices/create              register
 *   publish   {prefix}/push-devices/message             ECHO ping / poll
 *   publish   {prefix}/push-devices/update/confirm      operation confirm
 *   subscribe {prefix}/push-devices/{sn}/create/ack
 *   subscribe {prefix}/push-devices/{sn}/message/response   ECHO "pong" (or a pending operation)
 *   subscribe {prefix}/push-devices/{sn}/commands           server-pushed operation
 *   subscribe {prefix}/push-devices/{sn}/update/confirm/ack
 */
public final class PosSimulator implements MqttCallbackExtended {

    private static final String ECHO = "ECHO";
    private static final String REG = "REG";
    private static final String DIN = "DIN";

    private final Gson gson = new Gson();

    private final String serial;
    private final String imei;
    private final String merchantId;
    private final String terminalId;
    private final String model;
    private final String firmware;
    private final String brokerUrl;
    private final String username;
    private final String password;
    private final String baseTopic;
    private final int qos;
    private final int keepAlive;
    private final int connectTimeout;
    private final boolean autoRegister;
    private final int echoIntervalSec;
    private final int statusIntervalSec;
    private final String confirmStatus;

    private final TerminalLog log;
    private final MqttClient client;

    /** Paho callbacks must not block; all message handling and publishing runs here. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(daemon("pos-worker"));
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, daemon("pos-sched"));

    // liveness / stats
    private final ConcurrentLinkedQueue<Long> echoInFlight = new ConcurrentLinkedQueue<>();
    private final AtomicLong echoSent = new AtomicLong();
    private final AtomicLong echoReceived = new AtomicLong();
    private final AtomicLong rttTotalMs = new AtomicLong();
    private final AtomicLong lastRttMs = new AtomicLong(-1);
    private final AtomicInteger reconnects = new AtomicInteger();
    private final AtomicBoolean confirmInFlight = new AtomicBoolean();
    private volatile Instant startedAt = Instant.now();
    private volatile Instant connectedAt;
    private volatile Instant lastPongAt;
    private volatile boolean registered;

    private PosSimulator(SimulatorConfig cfg) throws Exception {
        serial = cfg.require("terminal.serial-number");
        imei = cfg.require("terminal.imei");
        merchantId = cfg.get("terminal.merchant-id", "");
        terminalId = cfg.get("terminal.terminal-id", "");
        model = cfg.get("terminal.model", "SIM-POS");
        firmware = cfg.get("terminal.firmware", "1.0.0");
        brokerUrl = cfg.require("broker.url");
        username = cfg.get("broker.username", "");
        password = cfg.get("broker.password", "");
        baseTopic = cfg.require("topic.prefix") + "/push-devices";
        qos = cfg.getInt("qos", 1);
        keepAlive = cfg.getInt("keepalive.seconds", 30);
        connectTimeout = cfg.getInt("connect.timeout.seconds", 10);
        autoRegister = cfg.getBool("auto-register", true);
        echoIntervalSec = cfg.getInt("echo.interval.seconds", 10);
        statusIntervalSec = cfg.getInt("status.interval.seconds", 30);
        confirmStatus = cfg.get("command.confirm-status", "SUCCESS");

        log = new TerminalLog(Path.of(cfg.get("log.dir", "logs")), serial);
        client = new MqttClient(brokerUrl, "pos-sim-" + serial, new MemoryPersistence());
        client.setCallback(this);
    }

    public static void main(String[] args) throws Exception {
        Path cfgPath = Path.of(args.length > 0 ? args[0] : "config.properties");
        if (!Files.exists(cfgPath)) {
            System.err.println("Config file not found: " + cfgPath.toAbsolutePath()
                    + "\nUsage: java -jar pos-simulator.jar [path/to/config.properties]");
            System.exit(1);
        }
        PosSimulator sim = new PosSimulator(new SimulatorConfig(cfgPath));
        sim.run();
    }

    // ------------------------------------------------------------------ lifecycle

    private void run() {
        log.info("INIT", "==== POS simulator starting ====");
        log.info("INIT", "serial=" + serial + " imei=" + imei + " merchantId=" + merchantId
                + " terminalId=" + terminalId + " model=" + model);
        log.info("INIT", "broker=" + brokerUrl + " clientId=" + client.getClientId() + " topics=" + baseTopic
                + " qos=" + qos + " keepAlive=" + keepAlive + "s echoInterval=" + echoIntervalSec + "s");
        log.info("INIT", "log file: " + log.file().toAbsolutePath());

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "pos-shutdown"));
        scheduler.scheduleWithFixedDelay(this::echoTick, echoIntervalSec, echoIntervalSec, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::statusTick, statusIntervalSec, statusIntervalSec, TimeUnit.SECONDS);

        connectWithRetry();
        consoleLoop();
    }

    private void connectWithRetry() {
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setAutomaticReconnect(true);
        opts.setKeepAliveInterval(keepAlive);
        opts.setConnectionTimeout(connectTimeout);
        if (!username.isEmpty()) {
            opts.setUserName(username);
            opts.setPassword(password.toCharArray());
        }
        int attempt = 0;
        while (!client.isConnected()) {
            attempt++;
            try {
                log.info("CONN", "connecting to " + brokerUrl + " (attempt " + attempt + ")");
                client.connect(opts); // fires connectComplete on success
            } catch (MqttException e) {
                long wait = Math.min(30, 2L * attempt);
                log.error("CONN", "connect failed: " + describe(e) + " - retrying in " + wait + "s");
                sleep(wait * 1000);
            }
        }
    }

    private void shutdown() {
        log.info("CONN", "shutting down; " + statsLine());
        try {
            if (client.isConnected()) {
                client.disconnect(2000);
            }
            client.close();
        } catch (MqttException ignored) {
            // best effort
        }
        scheduler.shutdownNow();
        worker.shutdownNow();
        log.info("INIT", "==== POS simulator stopped ====");
    }

    // ------------------------------------------------------------------ MQTT callbacks

    @Override
    public void connectComplete(boolean reconnect, String serverUri) {
        connectedAt = Instant.now();
        echoInFlight.clear();
        registered = false;
        if (reconnect) {
            reconnects.incrementAndGet();
        }
        log.info("CONN", (reconnect ? "RECONNECTED" : "CONNECTED") + " to " + serverUri
                + " (keepAlive=" + keepAlive + "s, MQTT PINGREQ/PINGRESP handled by client)");
        worker.submit(() -> {
            subscribeAll();
            if (autoRegister) {
                register();
            }
        });
    }

    @Override
    public void connectionLost(Throwable cause) {
        long upSec = connectedAt == null ? 0 : Duration.between(connectedAt, Instant.now()).toSeconds();
        log.error("CONN", "CONNECTION LOST after " + upSec + "s up: " + cause
                + " - auto-reconnect will retry");
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        worker.submit(() -> handle(topic, payload));
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // QoS>=1 publish acknowledged by the broker (PUBACK); logged at TX time, nothing extra needed.
    }

    // ------------------------------------------------------------------ outbound

    private void subscribeAll() {
        String base = baseTopic + "/" + serial;
        List<String> topics = List.of(base + "/create/ack", base + "/message/response",
                base + "/commands", base + "/update/confirm/ack");
        for (String t : topics) {
            try {
                client.subscribe(t, qos);
                log.info("SUB", t + " (qos " + qos + ")");
            } catch (MqttException e) {
                log.error("SUB", "subscribe failed for " + t + ": " + describe(e));
            }
        }
    }

    private void register() {
        Map<String, Object> body = new HashMap<>();
        body.put("serialNumber", serial);
        body.put("imeiNumber", imei);
        body.put("merchantId", merchantId); // not read by the server today; carried for visibility
        body.put("terminalId", terminalId);
        publish(baseTopic + "/create", body, "REGISTER");
    }

    private void sendEcho() {
        Map<String, Object> body = new HashMap<>();
        body.put("serialNumber", serial);
        body.put("imeiNumber", imei);
        body.put("operationCode", ECHO);
        body.put("params", Map.of("merchantId", merchantId, "terminalId", terminalId));
        echoInFlight.add(System.nanoTime());
        echoSent.incrementAndGet();
        publish(baseTopic + "/message", body, "PING");
    }

    private void sendConfirm(String operationCode, Map<String, Object> params) {
        Map<String, Object> body = new HashMap<>();
        body.put("serialNumber", serial);
        body.put("imeiNumber", imei);
        body.put("operationCode", operationCode);
        body.put("status", confirmStatus);
        if (params != null) {
            body.put("params", params);
        }
        publish(baseTopic + "/update/confirm", body, "CONFIRM");
        // Release the guard even if the ack never comes, so a lost ack cannot wedge command handling.
        scheduler.schedule(() -> confirmInFlight.set(false), 15, TimeUnit.SECONDS);
    }

    private void publish(String topic, Object body, String category) {
        String json = gson.toJson(body);
        try {
            if (!client.isConnected()) {
                log.warn("TX", category + " skipped, not connected: " + topic);
                return;
            }
            client.publish(topic, json.getBytes(StandardCharsets.UTF_8), qos, false);
            log.info("TX", category + " -> " + topic + " " + json);
        } catch (MqttException e) {
            log.error("TX", category + " publish failed on " + topic + ": " + describe(e));
        }
    }

    // ------------------------------------------------------------------ inbound

    @SuppressWarnings("unchecked")
    private void handle(String topic, String payload) {
        String suffix = topic.startsWith(baseTopic + "/" + serial + "/")
                ? topic.substring((baseTopic + "/" + serial + "/").length()) : topic;
        Map<String, Object> msg;
        try {
            msg = gson.fromJson(payload, Map.class);
        } catch (Exception e) {
            log.error("RX", "unparseable payload on " + topic + ": " + payload);
            return;
        }
        if (msg == null) {
            msg = Map.of();
        }
        try {
            switch (suffix) {
                case "create/ack" -> onRegisterAck(msg, payload);
                case "message/response" -> onMessageResponse(msg, payload);
                case "commands" -> {
                    log.info("RX", "COMMAND <- " + topic + " " + payload);
                    executeOperation((String) msg.get("operationCode"), (Map<String, Object>) msg.get("params"), "push");
                }
                case "update/confirm/ack" -> {
                    confirmInFlight.set(false);
                    boolean ok = "000".equals(msg.get("responseCode"));
                    if (ok) {
                        log.info("RX", "CONFIRM ACK <- " + payload);
                    } else {
                        log.warn("RX", "CONFIRM ACK (rejected) <- " + payload);
                    }
                }
                default -> log.warn("RX", "unhandled topic " + topic + " " + payload);
            }
        } catch (Exception e) {
            log.error("RX", "error handling " + topic + ": " + e);
        }
    }

    private void onRegisterAck(Map<String, Object> msg, String payload) {
        if ("000".equals(msg.get("status"))) {
            registered = true;
            log.info("RX", "REGISTER OK <- " + payload);
        } else {
            registered = false;
            log.error("RX", "REGISTER REJECTED (status=" + msg.get("status")
                    + ", 101 = serial/imei not an active device in the TMS DB) <- " + payload);
        }
    }

    @SuppressWarnings("unchecked")
    private void onMessageResponse(Map<String, Object> msg, String payload) {
        Long sentNanos = echoInFlight.poll();
        long rttMs = sentNanos == null ? -1 : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sentNanos);
        lastPongAt = Instant.now();
        echoReceived.incrementAndGet();
        if (rttMs >= 0) {
            lastRttMs.set(rttMs);
            rttTotalMs.addAndGet(rttMs);
        }
        String op = (String) msg.get("operationCode");
        if (op == null || ECHO.equals(op)) {
            log.info("PONG", "ECHO reply, rtt=" + rttMs + "ms, no pending operation <- " + payload);
        } else {
            log.info("PONG", "ECHO reply, rtt=" + rttMs + "ms, PENDING OPERATION " + op + " <- " + payload);
            executeOperation(op, (Map<String, Object>) msg.get("params"), "poll");
        }
    }

    /** Simulates running a server operation, then confirms it. */
    private void executeOperation(String op, Map<String, Object> params, String via) {
        if (op == null || ECHO.equals(op) || REG.equals(op)) {
            return;
        }
        // Server re-delivers a PEND operation both on /commands and on the next ECHO reply
        // until it is confirmed; only act once.
        if (!confirmInFlight.compareAndSet(false, true)) {
            log.info("CMD", op + " (via " + via + ") already being confirmed, skipping duplicate");
            return;
        }
        log.info("CMD", "executing " + op + " (via " + via + ") params=" + gson.toJson(params));
        sendConfirm(op, DIN.equals(op) ? deviceInfo() : null);
    }

    /** Device-info payload; the server persists these fields (see PushConfirmServiceImpl). */
    private Map<String, Object> deviceInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("battery", 87);
        info.put("cpu", 12);
        info.put("ram", 41);
        info.put("storage", 33);
        info.put("firmware", firmware);
        info.put("printer_status", "OK");
        info.put("printer_cut_mode", "FULL");
        info.put("lat", 6.9271);
        info.put("lng", 79.8612);
        info.put("installedApks", List.of());
        info.put("tid", terminalId);
        info.put("mid", merchantId);
        info.put("model", model);
        info.put("simSN", "8994000000000000001");
        info.put("additionalData", "simulator");
        return info;
    }

    // ------------------------------------------------------------------ liveness

    private void echoTick() {
        try {
            if (!client.isConnected()) {
                log.warn("PING", "not connected, echo skipped");
                return;
            }
            if (echoInFlight.size() >= 3) {
                log.warn("PING", echoInFlight.size() + " echoes unanswered - server not responding "
                        + "(is the TMS running and subscribed to " + baseTopic + "/message?)");
            }
            sendEcho();
        } catch (Exception e) {
            log.error("PING", "echo failed: " + e);
        }
    }

    private void statusTick() {
        log.info("STATUS", livenessLine());
    }

    private String livenessLine() {
        String state;
        if (!client.isConnected()) {
            state = "DISCONNECTED";
        } else if (lastPongAt == null
                ? echoSent.get() >= 3
                : Duration.between(lastPongAt, Instant.now()).toSeconds() > 3L * echoIntervalSec) {
            state = "STALE (connected to broker, no reply from TMS)";
        } else {
            state = "HEALTHY";
        }
        return "liveness=" + state + " | " + statsLine();
    }

    private String statsLine() {
        long recv = echoReceived.get();
        String avg = recv == 0 ? "n/a" : (rttTotalMs.get() / recv) + "ms";
        String lastPong = lastPongAt == null ? "never"
                : Duration.between(lastPongAt, Instant.now()).toSeconds() + "s ago";
        String up = connectedAt == null ? "n/a" : Duration.between(connectedAt, Instant.now()).toSeconds() + "s";
        return "connected=" + client.isConnected() + " registered=" + registered
                + " connUptime=" + up + " reconnects=" + reconnects.get()
                + " echoSent=" + echoSent.get() + " echoRecv=" + recv
                + " lastRtt=" + lastRttMs.get() + "ms avgRtt=" + avg + " lastPong=" + lastPong
                + " runTime=" + Duration.between(startedAt, Instant.now()).toSeconds() + "s";
    }

    // ------------------------------------------------------------------ console

    private void consoleLoop() {
        System.out.println("Console commands: status | echo | register | din | disconnect | connect | help | quit");
        Scanner in = new Scanner(System.in);
        while (in.hasNextLine()) {
            String cmd = in.nextLine().trim().toLowerCase();
            switch (cmd) {
                case "status" -> log.info("STATUS", livenessLine());
                case "echo" -> worker.submit(this::sendEcho);
                case "register" -> worker.submit(this::register);
                case "din" -> worker.submit(() -> {
                    confirmInFlight.set(false);
                    executeOperation(DIN, null, "console");
                });
                case "disconnect" -> {
                    try {
                        log.info("CONN", "manual disconnect (auto-reconnect is bypassed until 'connect')");
                        client.disconnect();
                    } catch (MqttException e) {
                        log.error("CONN", describe(e));
                    }
                }
                case "connect" -> new Thread(this::connectWithRetry, "pos-connect").start();
                case "quit", "exit" -> {
                    System.exit(0);
                }
                case "", "help" -> System.out.println("status | echo | register | din | disconnect | connect | quit");
                default -> System.out.println("Unknown command: " + cmd);
            }
        }
        // stdin closed (e.g. run as a service): keep running until killed
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String describe(MqttException e) {
        return "[" + e.getReasonCode() + "] " + e.getMessage()
                + (e.getCause() == null ? "" : " (cause: " + e.getCause() + ")");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
