package com.hnb.wireme.tms.simulator;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One simulated POS terminal on the TMS push channel, for the desktop UI. Same protocol as
 * PosSimulator / FleetDevice, but every connect, subscribe, publish and inbound message is recorded
 * as an {@link Exchange}, and each publish is paired with the TMS's reply:
 *
 *   publish {prefix}/push-devices/create          ->  {prefix}/push-devices/{sn}/create/ack
 *   publish {prefix}/push-devices/message         ->  {prefix}/push-devices/{sn}/message/response
 *   publish {prefix}/push-devices/update/confirm  ->  {prefix}/push-devices/{sn}/update/confirm/ack
 *
 * Replies are matched to publishes in order per reply topic; one that doesn't arrive within
 * {@link #REPLY_TIMEOUT_SEC} marks the publish "NO REPLY". Messages nobody waited for (pushed
 * commands, late replies) are recorded as RECEIVE exchanges.
 */
final class MqttTerminal implements MqttCallbackExtended {

    static final String ECHO = "ECHO";
    static final String REG = "REG";
    static final String DIN = "DIN";
    private static final int REPLY_TIMEOUT_SEC = 15;

    record Identity(String serial, String imei, String terminalId, String merchantId, String model,
                    String firmware, double lat, double lng) {
    }

    /** Told about every push operation the TMS delivers (ECHO reply or /commands), confirmed or not. */
    interface OperationListener {
        void received(MqttTerminal terminal, String operationCode, Map<String, Object> params, String via);
    }

    final Identity id;
    final ExchangeLog log;
    private final String brokerUrl;
    private final String username;
    private final String password;
    private final String baseTopic;
    private final int qos;
    private final int keepAlive;
    private final int connectTimeout;
    private final MqttClient client;
    /** Handles inbound messages and timeouts; Paho's callback thread must never block on a publish. */
    private final ScheduledExecutorService exec;
    private final Map<String, Queue<Exchange>> pending = new ConcurrentHashMap<>();
    private final List<OperationListener> operationListeners = new CopyOnWriteArrayList<>();

    volatile boolean autoRegister = true;
    volatile boolean autoConfirm = true;
    volatile String confirmStatus = "SUCCESS";

    private final AtomicBoolean confirmInFlight = new AtomicBoolean();
    private final AtomicLong echoSent = new AtomicLong();
    private final AtomicLong echoReceived = new AtomicLong();
    private volatile boolean registered;
    /** True once the reply topics are subscribed; publishing earlier would lose the reply. */
    private volatile boolean subscribed;
    private volatile String lastEvent = "";
    private volatile ScheduledFuture<?> echoTask;

    MqttTerminal(Identity id, String brokerUrl, String username, String password, String topicPrefix, int qos,
                 int keepAlive, int connectTimeout, String clientIdPrefix, ExchangeLog log,
                 ScheduledExecutorService exec) throws MqttException {
        this.id = id;
        this.log = log;
        this.brokerUrl = brokerUrl;
        this.username = username;
        this.password = password;
        this.baseTopic = topicPrefix + "/push-devices";
        this.qos = qos;
        this.keepAlive = keepAlive;
        this.connectTimeout = connectTimeout;
        this.exec = exec;
        this.client = new MqttClient(brokerUrl, clientIdPrefix + id.serial(), new MemoryPersistence());
        this.client.setCallback(this);
    }

    // ------------------------------------------------------------------ connection

    /** Blocking; call off the UI thread. */
    Exchange connect() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Client-Id", client.getClientId());
        h.put("Keep-Alive", keepAlive + "s");
        h.put("Clean-Session", "true");
        h.put("Auto-Reconnect", "true");
        if (!username.isBlank()) {
            h.put("Username", username);
        }
        Exchange ex = log.add(new Exchange(Exchange.Kind.MQTT, "CONNECT", brokerUrl, h, ""));
        if (client.isConnected()) {
            ex.complete(true, "ALREADY CONNECTED", Map.of(), "");
            return ex;
        }
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setAutomaticReconnect(true);
        opts.setKeepAliveInterval(keepAlive);
        opts.setConnectionTimeout(connectTimeout);
        if (!username.isBlank()) {
            opts.setUserName(username);
            opts.setPassword(password.toCharArray());
        }
        try {
            client.connect(opts);
            ex.complete(true, "CONNACK accepted", Map.of("Server-URI", String.valueOf(client.getCurrentServerURI())), "");
        } catch (MqttException e) {
            ex.complete(false, "CONNECT FAILED [" + e.getReasonCode() + "]", Map.of(), describe(e)
                    + "\n\nIs the broker running at " + brokerUrl + "? (docker compose up -d)");
        }
        return ex;
    }

    Exchange disconnect() {
        stopAutoEcho();
        Exchange ex = log.add(new Exchange(Exchange.Kind.MQTT, "DISCONNECT", brokerUrl,
                Map.of("Client-Id", client.getClientId()), ""));
        try {
            if (client.isConnected()) {
                client.disconnect(2000);
                ex.complete(true, "DISCONNECTED", Map.of(), "");
            } else {
                ex.complete(true, "WAS NOT CONNECTED", Map.of(), "");
            }
        } catch (MqttException e) {
            ex.complete(false, "DISCONNECT FAILED", Map.of(), describe(e));
        }
        registered = false;
        subscribed = false;
        return ex;
    }

    void close() {
        stopAutoEcho();
        try {
            if (client.isConnected()) {
                client.disconnect(1000);
            }
            client.close(true);
        } catch (MqttException ignored) {
            // shutting down
        }
    }

    void startAutoEcho(int intervalSec) {
        stopAutoEcho();
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, intervalSec)) + 1;
        echoTask = exec.scheduleWithFixedDelay(() -> {
            if (client.isConnected()) {
                echo();
            }
        }, jitter, intervalSec, TimeUnit.SECONDS);
    }

    void stopAutoEcho() {
        ScheduledFuture<?> t = echoTask;
        if (t != null) {
            t.cancel(false);
            echoTask = null;
        }
    }

    // ------------------------------------------------------------------ outbound

    String topic(String suffix) {
        return baseTopic + "/" + suffix;
    }

    // Payload builders are static so the UI can preview them before any client exists.

    static String registerPayload(Identity id) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serialNumber", id.serial());
        body.put("imeiNumber", id.imei());
        body.put("merchantId", id.merchantId()); // not read by the server today; carried for visibility
        body.put("terminalId", id.terminalId());
        return Json.pretty(body);
    }

    static String echoPayload(Identity id) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serialNumber", id.serial());
        body.put("imeiNumber", id.imei());
        body.put("operationCode", ECHO);
        body.put("params", Map.of("merchantId", id.merchantId(), "terminalId", id.terminalId()));
        return Json.pretty(body);
    }

    static String confirmPayload(Identity id, String operationCode, String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serialNumber", id.serial());
        body.put("imeiNumber", id.imei());
        body.put("operationCode", operationCode);
        body.put("status", status);
        if (DIN.equals(operationCode)) {
            body.put("params", deviceInfo(id));
        }
        return Json.pretty(body);
    }

    Exchange register() {
        return publish(topic("create"), registerPayload(id), qos, false);
    }

    Exchange echo() {
        return publish(topic("message"), echoPayload(id), qos, false);
    }

    /**
     * Publishes the payload as given. Blocking until the broker acks (QoS 1+), so never call it on
     * Paho's callback thread. The returned exchange completes when the reply arrives or times out.
     */
    Exchange publish(String topic, String payload, int q, boolean retained) {
        return publish(topic, payload, q, retained, log);
    }

    /** As above, recording the exchange in {@code into} (e.g. the Push tab's history) instead of this terminal's. */
    Exchange publish(String topic, String payload, int q, boolean retained, ExchangeLog into) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Broker", brokerUrl);
        h.put("Client-Id", client.getClientId());
        h.put("QoS", String.valueOf(q));
        h.put("Retained", String.valueOf(retained));
        Exchange ex = into.add(new Exchange(Exchange.Kind.MQTT, "PUBLISH", topic, h, payload));
        if (!client.isConnected()) {
            ex.complete(false, "NOT CONNECTED", Map.of(), "Connect to the broker first.");
            return ex;
        }
        String replySuffix = replySuffixFor(topic);
        String replyTopic = replySuffix == null ? null : baseTopic + "/" + id.serial() + "/" + replySuffix;
        if ("message/response".equals(replySuffix)) {
            echoSent.incrementAndGet(); // counted here so echoes sent from the editor count too
        }
        if (replySuffix != null) {
            pending.computeIfAbsent(replySuffix, k -> new ConcurrentLinkedQueue<>()).add(ex);
            exec.schedule(() -> {
                Queue<Exchange> queue = pending.get(replySuffix);
                if (queue != null && queue.remove(ex)) {
                    ex.complete(false, "NO REPLY within " + REPLY_TIMEOUT_SEC + "s", Map.of("Expected-Topic", replyTopic),
                            "The TMS did not answer on " + replyTopic + ".\nIs it running, connected to "
                                    + brokerUrl + " and using topic prefix '" + baseTopic.replace("/push-devices", "") + "'?");
                }
            }, REPLY_TIMEOUT_SEC, TimeUnit.SECONDS);
        }
        try {
            client.publish(topic, payload.getBytes(StandardCharsets.UTF_8), q, retained);
            if (replySuffix == null) {
                ex.complete(true, "PUBLISHED (no reply topic for this publish)", Map.of(), "");
            } else {
                ex.progress("published, waiting on " + replySuffix);
            }
        } catch (MqttException e) {
            if (replySuffix != null) {
                pending.get(replySuffix).remove(ex);
            }
            ex.complete(false, "PUBLISH FAILED [" + e.getReasonCode() + "]", Map.of(), describe(e));
        }
        return ex;
    }

    private String replySuffixFor(String topic) {
        String prefix = baseTopic + "/";
        if (!topic.startsWith(prefix)) {
            return null;
        }
        return switch (topic.substring(prefix.length())) {
            case "create" -> "create/ack";
            case "message" -> "message/response";
            case "update/confirm" -> "update/confirm/ack";
            default -> null;
        };
    }

    // ------------------------------------------------------------------ MQTT callbacks

    @Override
    public void connectComplete(boolean reconnect, String serverUri) {
        if (reconnect) {
            log.add(Exchange.inbound(Exchange.Kind.MQTT, serverUri, true, "RECONNECTED (auto)", Map.of(), ""));
        }
        exec.execute(() -> {
            subscribeAll();
            if (autoRegister) {
                register();
            }
        });
    }

    @Override
    public void connectionLost(Throwable cause) {
        registered = false;
        subscribed = false;
        lastEvent = "connection lost";
        log.add(Exchange.inbound(Exchange.Kind.MQTT, brokerUrl, false, "CONNECTION LOST (auto-reconnect will retry)",
                Map.of(), String.valueOf(cause)));
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Topic", topic);
        h.put("QoS", String.valueOf(message.getQos()));
        h.put("Retained", String.valueOf(message.isRetained()));
        h.put("Duplicate", String.valueOf(message.isDuplicate()));
        exec.execute(() -> handleInbound(topic, payload, h));
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // PUBACK; the publish call itself already waited for it
    }

    private void subscribeAll() {
        String base = baseTopic + "/" + id.serial();
        String[] topics = {base + "/create/ack", base + "/message/response", base + "/commands", base + "/update/confirm/ack"};
        int[] qosArr = new int[topics.length];
        Arrays.fill(qosArr, qos);
        Exchange ex = log.add(new Exchange(Exchange.Kind.MQTT, "SUBSCRIBE", base + "/#(4 topics)",
                Map.of("QoS", String.valueOf(qos)), String.join("\n", topics)));
        try {
            IMqttToken token = client.subscribeWithResponse(topics, qosArr);
            int[] granted = token.getGrantedQos();
            boolean ok = granted != null && Arrays.stream(granted).noneMatch(g -> g == 128);
            subscribed = ok;
            ex.complete(ok, "SUBACK granted QoS " + Arrays.toString(granted), Map.of(), "");
        } catch (MqttException e) {
            ex.complete(false, "SUBSCRIBE FAILED", Map.of(), describe(e));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleInbound(String topic, String payload, Map<String, String> headers) {
        String mine = baseTopic + "/" + id.serial() + "/";
        String suffix = topic.startsWith(mine) ? topic.substring(mine.length()) : topic;
        Map<String, Object> msg = Json.object(payload);
        if (msg == null) {
            msg = Map.of();
        }
        String op = (String) msg.get("operationCode");
        boolean ok;
        String status;
        switch (suffix) {
            case "create/ack" -> {
                Object s = msg.get("status");
                ok = "000".equals(s);
                registered = ok;
                status = ok ? "REGISTERED (status 000)"
                        : "REGISTER REJECTED (status " + s + ("101".equals(s) ? " = serial/IMEI not an active device" : "") + ")";
            }
            case "message/response" -> {
                echoReceived.incrementAndGet();
                ok = true;
                status = op == null || ECHO.equals(op) ? "ECHO reply, no pending operation" : "ECHO reply + PENDING " + op;
            }
            case "commands" -> {
                ok = true;
                status = "COMMAND " + op;
            }
            case "update/confirm/ack" -> {
                confirmInFlight.set(false);
                Object code = msg.get("responseCode");
                ok = "000".equals(code);
                status = ok ? "CONFIRM ACK (000)" : "CONFIRM REJECTED (" + code + ")";
            }
            default -> {
                ok = true;
                status = "message";
            }
        }
        lastEvent = suffix + ": " + status;

        Queue<Exchange> queue = pending.get(suffix);
        Exchange waiting = queue == null ? null : queue.poll();
        String body = Json.pretty(payload);
        if (waiting == null || !waiting.complete(ok, status, headers, body)) {
            log.add(Exchange.inbound(Exchange.Kind.MQTT, topic, ok, status, headers, body));
        }

        if (("message/response".equals(suffix) && op != null && !ECHO.equals(op) && !REG.equals(op))
                || "commands".equals(suffix)) {
            Map<String, Object> params = msg.get("params") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
            String via = "commands".equals(suffix) ? "/commands" : "ECHO reply";
            for (OperationListener l : operationListeners) {
                l.received(this, op, params, via);
            }
            maybeConfirm(op);
        }
    }

    /** Server re-delivers a pending operation (on /commands and in ECHO replies) until confirmed; confirm once. */
    private void maybeConfirm(String op) {
        if (op == null || !autoConfirm || !confirmInFlight.compareAndSet(false, true)) {
            return;
        }
        publish(topic("update/confirm"), confirmPayload(id, op, confirmStatus), qos, false);
        // release even if the ack never comes, so a lost ack can't wedge command handling
        exec.schedule(() -> confirmInFlight.set(false), REPLY_TIMEOUT_SEC, TimeUnit.SECONDS);
    }

    /** Device-info payload for a DIN confirm; the server persists these fields (see PushConfirmServiceImpl). */
    private static Map<String, Object> deviceInfo(Identity id) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("battery", 87);
        info.put("cpu", 12);
        info.put("ram", 41);
        info.put("storage", 33);
        info.put("firmware", id.firmware());
        info.put("printer_status", "OK");
        info.put("printer_cut_mode", "FULL");
        info.put("lat", id.lat());
        info.put("lng", id.lng());
        info.put("installedApks", List.of());
        info.put("tid", id.terminalId());
        info.put("mid", id.merchantId());
        info.put("model", id.model());
        info.put("simSN", "8994000000000000001");
        info.put("additionalData", "simulator");
        return info;
    }

    // ------------------------------------------------------------------ state

    void addOperationListener(OperationListener l) {
        operationListeners.add(l);
    }

    boolean isConnected() {
        return client.isConnected();
    }

    boolean isRegistered() {
        return registered;
    }

    boolean isSubscribed() {
        return subscribed;
    }

    long echoSent() {
        return echoSent.get();
    }

    long echoReceived() {
        return echoReceived.get();
    }

    String lastEvent() {
        return lastEvent;
    }

    String brokerUrl() {
        return brokerUrl;
    }

    int qos() {
        return qos;
    }

    private static String describe(MqttException e) {
        return "[" + e.getReasonCode() + "] " + e.getMessage() + (e.getCause() == null ? "" : "\ncause: " + e.getCause());
    }
}
