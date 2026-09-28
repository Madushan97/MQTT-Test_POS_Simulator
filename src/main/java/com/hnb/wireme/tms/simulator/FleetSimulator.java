package com.hnb.wireme.tms.simulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Brings a random pool of ~50-100 real devices (read straight from the
 * DEVICES/TERMINALS/MERCHANTS tables — see DeviceDbClient) "online" at once,
 * then periodically churns some of them offline/back-online — for exercising
 * Fleet Overview, Dormancy Analysis and Merchant Feedback together, the way a
 * real fleet would drift.
 *
 * See fleet-config.properties for the two independent signals this drives
 * (MQTT push churn vs. the WireMe-core sale call that actually moves
 * DEVICES.LASTACTIVE) and README.md for how to read the results.
 */
public final class FleetSimulator {

    private final SimulatorConfig cfg;
    private final TerminalLog log;
    private final CoreSaleClient coreSaleClient;

    private final String brokerUrl;
    private final String username;
    private final String password;
    private final String topicPrefix;
    private final int qos;
    private final int keepAlive;
    private final int connectTimeoutSec;
    private final int echoIntervalSec;
    private final int saleIntervalSec;
    private final String dbUrl;
    private final String dbUsername;
    private final String dbPassword;

    private final ExecutorService connectExecutor =
            Executors.newCachedThreadPool(daemon("fleet-connect"));
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(32, daemon("fleet-sched"));

    private final Map<String, FleetDevice> devices = new ConcurrentHashMap<>();
    private final AtomicLong saleOk = new AtomicLong();
    private final AtomicLong saleFail = new AtomicLong();
    private final java.util.Random random = new java.util.Random();

    private FleetSimulator(SimulatorConfig cfg) throws IOException {
        this.cfg = cfg;
        this.log = TerminalLog.forFileName(Path.of(cfg.get("log.dir", "logs")), cfg.get("log.file", "fleet.log"));
        this.brokerUrl = cfg.require("broker.url");
        this.username = cfg.get("broker.username", "");
        this.password = cfg.get("broker.password", "");
        this.topicPrefix = cfg.require("topic.prefix");
        this.qos = cfg.getInt("qos", 1);
        this.keepAlive = cfg.getInt("keepalive.seconds", 30);
        this.connectTimeoutSec = cfg.getInt("connect.timeout.seconds", 10);
        this.echoIntervalSec = cfg.getInt("echo.interval.seconds", 20);
        this.saleIntervalSec = cfg.getInt("sale.interval.seconds", 60);
        this.dbUrl = "jdbc:oracle:thin:@" + cfg.require("db.host") + ":" + cfg.get("db.port", "1521")
                + "/" + cfg.require("db.sid");
        this.dbUsername = cfg.require("db.username");
        this.dbPassword = cfg.require("db.password");
        this.coreSaleClient = new CoreSaleClient(cfg.require("core.sale-url"),
                cfg.getInt("core.amount-min", 100), cfg.getInt("core.amount-max", 250000),
                cfg.get("core.currency", "LKR"));
    }

    public static void main(String[] args) throws Exception {
        Path cfgPath = Path.of(args.length > 0 ? args[0] : "fleet-config.properties");
        if (!Files.exists(cfgPath)) {
            System.err.println("Config file not found: " + cfgPath.toAbsolutePath()
                    + "\nUsage: java -cp pos-simulator.jar com.hnb.wireme.tms.simulator.FleetSimulator [path/to/fleet-config.properties]");
            System.exit(1);
        }
        new FleetSimulator(new SimulatorConfig(cfgPath)).run();
    }

    private void run() throws IOException, InterruptedException, SQLException {
        log.info("INIT", "==== Fleet simulator starting ====");
        log.info("INIT", "log file: " + log.file().toAbsolutePath());

        int requestedSize = cfg.getInt("fleet.size", 80);
        log.info("INIT", "fetching " + requestedSize + " random active devices from " + dbUrl);
        List<DeviceDbClient.DbDevice> selected = new DeviceDbClient()
                .fetchRandomActiveDevices(dbUrl, dbUsername, dbPassword, requestedSize);
        if (selected.isEmpty()) {
            throw new IllegalStateException("No active devices with a serialNo+IMEI+terminalId+merchantId "
                    + "were found in DEVICES/TERMINALS/MERCHANTS");
        }
        log.info("INIT", "DB returned " + selected.size() + " devices to simulate");
        if (selected.size() < requestedSize) {
            log.warn("INIT", "requested fleet.size=" + requestedSize
                    + " but only " + selected.size() + " eligible devices exist in the DB");
        }

        // Bootstrap a DEVICE_STATUS row for whichever of these devices don't already have one —
        // see DeviceStatusSeeder's class doc for why (works around markOnline()'s currently-broken
        // first-time INSERT without touching the push side itself).
        new DeviceStatusSeeder().seedMissing(dbUrl, dbUsername, dbPassword,
                selected.stream().map(DeviceDbClient.DbDevice::deviceId).toList(), log);

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "fleet-shutdown"));

        long rampUpMs = cfg.getInt("fleet.connect-ramp-up-ms", 150);
        long delay = 0;
        for (DeviceDbClient.DbDevice d : selected) {
            long thisDelay = delay;
            scheduler.schedule(() -> onboard(d), thisDelay, TimeUnit.MILLISECONDS);
            delay += rampUpMs;
        }

        int statusIntervalSec = cfg.getInt("status.interval.seconds", 30);
        scheduler.scheduleWithFixedDelay(this::statusTick, statusIntervalSec, statusIntervalSec, TimeUnit.SECONDS);

        if (cfg.getBool("sale.enabled", false)) {
            // First sale batch fires once the whole fleet has finished connecting.
            long saleStartDelay = delay / 1000 + 5;
            scheduler.scheduleWithFixedDelay(this::saleTick, saleStartDelay, saleIntervalSec, TimeUnit.SECONDS);
            log.info("INIT", "sales enabled: first batch in ~" + saleStartDelay + "s, every " + saleIntervalSec + "s after");
        } else {
            log.info("INIT", "sales disabled (sale.enabled=false): MQTT register/ECHO only, no WireMe core calls");
        }

        if (cfg.getBool("churn.enabled", true)) {
            long churnStartDelay = delay / 1000 + cfg.getInt("churn.start-delay-seconds", 180);
            int churnInterval = cfg.getInt("churn.interval-seconds", 120);
            scheduler.scheduleWithFixedDelay(this::churnTick, churnStartDelay, churnInterval, TimeUnit.SECONDS);
            log.info("INIT", "churn enabled: first pass in ~" + churnStartDelay + "s, every " + churnInterval + "s after");
        } else {
            log.info("INIT", "churn disabled: fleet stays online once connected");
        }

        consoleLoop();
    }

    /** Onboard one device: connect MQTT, then start its echo + sale schedules. */
    private void onboard(DeviceDbClient.DbDevice d) {
        try {
            // Merchant's own LAT/LNG when the DB has it; otherwise a Colombo-area jitter.
            double lat = d.lat() != null ? d.lat() : 6.9271 + (random.nextDouble() - 0.5) * 0.6;
            double lng = d.lng() != null ? d.lng() : 79.8612 + (random.nextDouble() - 0.5) * 0.6;
            FleetDevice device = new FleetDevice(d, brokerUrl, username, password, topicPrefix,
                    qos, keepAlive, connectTimeoutSec, lat, lng, log);
            // Defense in depth: DeviceDbClient's query is deduplicated per device, but if a
            // duplicate serial ever slipped through anyway, two MqttClients with the identical
            // "fleet-sim-<serial>" client id would perpetually kick each other off the broker
            // ("session taken over" — a connect/connectionLost loop). Skip rather than risk that.
            if (devices.putIfAbsent(d.serialNo(), device) != null) {
                log.warn("FLEET", d.serialNo() + " duplicate serial from the DB query, skipping second instance");
                return;
            }
            connectExecutor.submit(device::connect);
            startSchedules(device);
            log.info("FLEET", d.serialNo() + " onboarded (terminalId=" + d.terminalId()
                    + " merchantId=" + d.merchantId() + ")");
        } catch (Exception e) {
            log.error("FLEET", "failed to onboard " + d.serialNo() + ": " + e);
        }
    }

    private void startSchedules(FleetDevice device) {
        long echoJitter = random.nextInt(Math.max(1, echoIntervalSec));
        device.echoTask = scheduler.scheduleWithFixedDelay(device::sendEcho, echoJitter, echoIntervalSec, TimeUnit.SECONDS);
        // Sales are NOT scheduled per-device: see saleTick() — once per sale.interval.seconds, a
        // random subset of the fleet sells, not every device every interval.
    }

    /** Once per sale.interval.seconds: a different random subset of the currently-online fleet
     * sells, rather than every device selling on its own timer — closer to how a real merchant
     * base actually transacts, and easy to read in the log as distinct batches. */
    private void saleTick() {
        List<FleetDevice> online = devices.values().stream().filter(FleetDevice::isSaleActive).toList();
        if (online.isEmpty()) {
            return;
        }
        int batchSize = Math.min(cfg.getInt("sale.batch-size", 15), online.size());
        List<FleetDevice> pool = new ArrayList<>(online);
        Collections.shuffle(pool, random);
        List<FleetDevice> batch = pool.subList(0, batchSize);
        for (FleetDevice device : batch) {
            connectExecutor.submit(() -> runSale(device));
        }
        log.info("SALE", "batch of " + batch.size() + " device(s) selling this round: "
                + batch.stream().map(d -> d.serialNumber).toList());
    }

    private void runSale(FleetDevice device) {
        if (!device.isSaleActive()) {
            return;
        }
        CoreSaleClient.SaleResult result = coreSaleClient.sendSale(
                device.serialNumber, device.terminalId, device.merchantId, device.lat, device.lng);
        // Full request payload every time, plus the response — an HTTP 200 alone doesn't prove
        // core actually persisted the transaction, so both sides are worth seeing.
        log.info("SALE", device.serialNumber + " request=" + result.requestJson());
        if (result.ok()) {
            saleOk.incrementAndGet();
            log.info("SALE", device.serialNumber + " sale accepted: HTTP " + result.httpStatus()
                    + " body=" + result.body());
        } else {
            saleFail.incrementAndGet();
            log.warn("SALE", device.serialNumber + " sale call failed: HTTP " + result.httpStatus()
                    + (result.error() != null ? " " + result.error() : " " + result.body()));
        }
    }

    // ------------------------------------------------------------------ churn

    private void churnTick() {
        List<FleetDevice> online = devices.values().stream().filter(FleetDevice::isSaleActive).toList();
        List<FleetDevice> offline = devices.values().stream().filter(d -> !d.isSaleActive()).toList();

        int toOffline = (int) Math.ceil(online.size() * cfg.getDouble("churn.offline-fraction", 0.15));
        int toRecover = (int) Math.ceil(offline.size() * cfg.getDouble("churn.recover-fraction", 0.05));

        List<FleetDevice> onlineShuffled = new ArrayList<>(online);
        Collections.shuffle(onlineShuffled, random);
        for (FleetDevice d : onlineShuffled.subList(0, Math.min(toOffline, onlineShuffled.size()))) {
            goOffline(d);
        }

        List<FleetDevice> offlineShuffled = new ArrayList<>(offline);
        Collections.shuffle(offlineShuffled, random);
        for (FleetDevice d : offlineShuffled.subList(0, Math.min(toRecover, offlineShuffled.size()))) {
            goOnline(d);
        }

        log.info("CHURN", "pass complete: dropped " + Math.min(toOffline, onlineShuffled.size())
                + ", recovered " + Math.min(toRecover, offlineShuffled.size())
                + " -- " + fleetSummary());
    }

    private void goOffline(FleetDevice d) {
        d.setSaleActive(false);
        connectExecutor.submit(d::disconnectQuiet);
        log.info("CHURN", d.serialNumber + " -> OFFLINE (sale calls paused, MQTT disconnected;"
                + " will show OFFLINE/INACTIVE in Fleet Overview once LASTACTIVE ages past the configured thresholds)");
    }

    private void goOnline(FleetDevice d) {
        d.setSaleActive(true);
        connectExecutor.submit(d::connect);
        log.info("CHURN", d.serialNumber + " -> back ONLINE (resuming sale calls + MQTT)");
    }

    // ------------------------------------------------------------------ status

    private void statusTick() {
        log.info("STATUS", fleetSummary());
    }

    private String fleetSummary() {
        long total = devices.size();
        long mqttConnected = devices.values().stream().filter(FleetDevice::isMqttConnected).count();
        long registered = devices.values().stream().filter(FleetDevice::isRegistered).count();
        long saleActive = devices.values().stream().filter(FleetDevice::isSaleActive).count();
        long echoSent = devices.values().stream().mapToLong(FleetDevice::echoSentCount).sum();
        long echoRecv = devices.values().stream().mapToLong(FleetDevice::echoReceivedCount).sum();
        return "fleet=" + total + " mqttConnected=" + mqttConnected + " registered=" + registered
                + " saleActive(=simulated ONLINE)=" + saleActive + " churnedOffline=" + (total - saleActive)
                + " echoSent=" + echoSent + " echoRecv=" + echoRecv
                + " saleOk=" + saleOk.get() + " saleFail=" + saleFail.get();
    }

    // ------------------------------------------------------------------ console

    private void consoleLoop() {
        System.out.println("Console commands: status | churn | quit");
        Scanner in = new Scanner(System.in);
        while (in.hasNextLine()) {
            String cmd = in.nextLine().trim().toLowerCase();
            switch (cmd) {
                case "status" -> log.info("STATUS", fleetSummary());
                case "churn" -> scheduler.submit(this::churnTick);
                case "quit", "exit" -> System.exit(0);
                default -> System.out.println("Unknown command: " + cmd);
            }
        }
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void shutdown() {
        log.info("INIT", "shutting down; " + fleetSummary());
        scheduler.shutdownNow();
        for (FleetDevice d : devices.values()) {
            d.disconnectQuiet();
            d.closeQuiet();
        }
        connectExecutor.shutdownNow();
        log.info("INIT", "==== Fleet simulator stopped ====");
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
