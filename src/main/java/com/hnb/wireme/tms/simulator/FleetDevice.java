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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One simulated POS terminal within a fleet run: its own MQTT client
 * (register + ECHO/pong + auto-confirm of any pushed command), plus the
 * periodic "sale" call to WireMe core that is what actually moves this
 * device's Fleet Overview status (see CoreSaleClient's class doc).
 *
 * Trimmed-down sibling of PosSimulator for many concurrent instances: no
 * per-device console/log file, no retry-loop-on-its-own-thread (FleetSimulator
 * staggers and owns scheduling), confirms every pushed operation with a fixed
 * SUCCESS status. Identity (serialNo/IMEI/terminalId/merchantId) comes from a
 * real DB row (DeviceDbClient), so register should succeed (status 000).
 */
final class FleetDevice implements MqttCallbackExtended {

    private static final String ECHO = "ECHO";

    private final Gson gson = new Gson();

    final String serialNumber;
    final String terminalId;
    final String merchantId;
    final String imei;
    final double lat;
    final double lng;

    private final String brokerUrl;
    private final String username;
    private final String password;
    private final String baseTopic;
    private final int qos;
    private final int keepAlive;
    private final int connectTimeoutSec;

    private final TerminalLog log;
    private final MqttClient client;

    private final AtomicBoolean confirmInFlight = new AtomicBoolean();
    private final AtomicLong echoSent = new AtomicLong();
    private final AtomicLong echoReceived = new AtomicLong();
    private final AtomicBoolean registered = new AtomicBoolean();

    /** Fleet Overview state: whether this device is currently being kept "alive"
     * by periodic sale calls, or has been churned to look offline/inactive.
     * Independent of MQTT connectivity, which FleetDevice tracks separately. */
    private final AtomicBoolean saleActive = new AtomicBoolean(true);

    // owned by FleetSimulator so churn can cancel/reschedule it
    volatile ScheduledFuture<?> echoTask;

    FleetDevice(DeviceDbClient.DbDevice device, String brokerUrl,
                String username, String password, String topicPrefix, int qos, int keepAlive,
                int connectTimeoutSec, double lat, double lng, TerminalLog log) throws MqttException {
        this.serialNumber = device.serialNo();
        this.terminalId = device.terminalId();
        this.merchantId = device.merchantId();
        this.imei = device.emiNo();
        this.lat = lat;
        this.lng = lng;
        this.brokerUrl = brokerUrl;
        this.username = username;
        this.password = password;
        this.baseTopic = topicPrefix + "/push-devices";
        this.qos = qos;
        this.keepAlive = keepAlive;
        this.connectTimeoutSec = connectTimeoutSec;
        this.log = log;
        this.client = new MqttClient(brokerUrl, "fleet-sim-" + serialNumber, new MemoryPersistence());
        this.client.setCallback(this);
    }

    boolean isSaleActive() {
        return saleActive.get();
    }

    void setSaleActive(boolean active) {
        saleActive.set(active);
    }

    boolean isMqttConnected() {
        return client.isConnected();
    }

    /** Blocking; call from a connect-executor thread, not the shared scheduler. */
    void connect() {
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setAutomaticReconnect(true);
        opts.setKeepAliveInterval(keepAlive);
        opts.setConnectionTimeout(connectTimeoutSec);
        if (username != null && !username.isBlank()) {
            opts.setUserName(username);
            opts.setPassword(password == null ? new char[0] : password.toCharArray());
        }
        try {
            client.connect(opts);
        } catch (MqttException e) {
            log.error("CONN", serialNumber + " connect failed: " + describe(e));
        }
    }

    void disconnectQuiet() {
        try {
            if (client.isConnected()) {
                client.disconnect(1000);
            }
        } catch (MqttException ignored) {
            // best effort — this device is being churned offline anyway
        }
    }

    void closeQuiet() {
        try {
            client.close(true);
        } catch (MqttException ignored) {
            // shutting down
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverUri) {
        try {
            String base = baseTopic + "/" + serialNumber;
            client.subscribe(new String[]{base + "/create/ack", base + "/message/response",
                    base + "/commands", base + "/update/confirm/ack"}, new int[]{qos, qos, qos, qos});
            register();
        } catch (MqttException e) {
            log.error("SUB", serialNumber + " subscribe failed: " + describe(e));
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("CONN", serialNumber + " connection lost: " + cause);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void messageArrived(String topic, MqttMessage message) {
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        String base = baseTopic + "/" + serialNumber + "/";
        String suffix = topic.startsWith(base) ? topic.substring(base.length()) : topic;
        Map<String, Object> msg;
        try {
            msg = gson.fromJson(payload, Map.class);
        } catch (Exception e) {
            return;
        }
        if (msg == null) {
            msg = Map.of();
        }
        switch (suffix) {
            case "create/ack" -> registered.set("000".equals(msg.get("status")));
            case "message/response" -> {
                echoReceived.incrementAndGet();
                String op = (String) msg.get("operationCode");
                if (op != null && !ECHO.equals(op) && confirmInFlight.compareAndSet(false, true)) {
                    confirm(op);
                }
            }
            case "commands" -> {
                String op = (String) msg.get("operationCode");
                if (op != null && confirmInFlight.compareAndSet(false, true)) {
                    confirm(op);
                }
            }
            case "update/confirm/ack" -> confirmInFlight.set(false);
            default -> {
                // ignore
            }
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // no-op; fire-and-forget at fleet scale
    }

    void register() {
        Map<String, Object> body = new HashMap<>();
        body.put("serialNumber", serialNumber);
        body.put("imeiNumber", imei);
        publish(baseTopic + "/create", body);
    }

    /** Scheduled periodically by FleetSimulator while this device's MQTT link is up. */
    void sendEcho() {
        if (!client.isConnected()) {
            return;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("serialNumber", serialNumber);
        body.put("imeiNumber", imei);
        body.put("operationCode", ECHO);
        body.put("params", Map.of("merchantId", merchantId, "terminalId", terminalId));
        echoSent.incrementAndGet();
        publish(baseTopic + "/message", body);
    }

    private void confirm(String operationCode) {
        Map<String, Object> body = new HashMap<>();
        body.put("serialNumber", serialNumber);
        body.put("imeiNumber", imei);
        body.put("operationCode", operationCode);
        body.put("status", "SUCCESS");
        publish(baseTopic + "/update/confirm", body);
    }

    private void publish(String topic, Object body) {
        try {
            if (!client.isConnected()) {
                return;
            }
            client.publish(topic, gson.toJson(body).getBytes(StandardCharsets.UTF_8), qos, false);
        } catch (MqttException e) {
            log.warn("TX", serialNumber + " publish failed on " + topic + ": " + describe(e));
        }
    }

    long echoSentCount() {
        return echoSent.get();
    }

    long echoReceivedCount() {
        return echoReceived.get();
    }

    boolean isRegistered() {
        return registered.get();
    }

    private static String describe(MqttException e) {
        return "[" + e.getReasonCode() + "] " + e.getMessage();
    }
}
