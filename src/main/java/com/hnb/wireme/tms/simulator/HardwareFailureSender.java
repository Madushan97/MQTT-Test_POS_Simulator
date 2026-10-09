package com.hnb.wireme.tms.simulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

/**
 * One-shot test of the TMS hardware-failure endpoints, no MQTT broker needed:
 *
 *   1. GET  hardware-failure-error-codes?category=...   load the catalogue
 *   2. POST hardware-failure-transactions  x N           random catalogue codes for this terminal
 *   3. GET  hardware-failure-transactions?deviceSerial=  read them back and print the breakdown
 *
 * Terminal identity and tms.base-url come from the same config.properties PosSimulator uses.
 *
 *   java -cp target/pos-simulator.jar com.hnb.wireme.tms.simulator.HardwareFailureSender [config] [count]
 *
 * Exits non-zero if the catalogue can't be loaded or any POST fails.
 */
public final class HardwareFailureSender {

    public static void main(String[] args) throws Exception {
        Path cfgPath = Path.of(args.length > 0 ? args[0] : "config.properties");
        if (!Files.exists(cfgPath)) {
            System.err.println("Config file not found: " + cfgPath.toAbsolutePath());
            System.exit(1);
        }
        SimulatorConfig cfg = new SimulatorConfig(cfgPath);
        int count = args.length > 1 ? Integer.parseInt(args[1]) : cfg.getInt("hwfail.count", 5);

        String serial = cfg.require("terminal.serial-number");
        // Usual POS request is serial + code only; tid/mid then stay null on the server.
        boolean sendTidMid = cfg.getBool("hwfail.send-tid-mid", false);
        String terminalId = sendTidMid ? cfg.get("terminal.terminal-id", "") : null;
        String merchantId = sendTidMid ? cfg.get("terminal.merchant-id", "") : null;
        String category = cfg.get("hwfail.category", "HARDWARE_FAILURES");
        HardwareFailureClient client = new HardwareFailureClient(cfg.get("tms.base-url", "http://localhost:8081/api/v1"));
        TerminalLog log = new TerminalLog(Path.of(cfg.get("log.dir", "logs")), serial);

        log.info("HWFAIL", "TMS " + client.base() + " serial=" + serial + " tid=" + terminalId + " mid=" + merchantId);

        List<HardwareFailureClient.ErrorCode> codes;
        try {
            codes = client.fetchErrorCodes(category);
        } catch (Exception e) {
            log.error("HWFAIL", "cannot load error codes: " + e.getMessage());
            System.exit(2);
            return;
        }
        if (codes.isEmpty()) {
            log.error("HWFAIL", "TMS returned no error codes for category=" + category + " - nothing to send");
            System.exit(2);
        }
        log.info("HWFAIL", "loaded " + codes.size() + " error codes (category=" + category + ")");

        Random random = new Random();
        int failed = 0;
        for (int i = 0; i < count; i++) {
            String code = codes.get(random.nextInt(codes.size())).code();
            HardwareFailureClient.Result r = client.report(serial, code, terminalId, merchantId);
            if (r.ok()) {
                log.info("HWFAIL", "TX " + r.requestJson() + " -> HTTP " + r.httpStatus() + " " + r.body());
            } else {
                failed++;
                log.error("HWFAIL", "TX " + r.requestJson() + " -> " + HardwareFailureClient.describe(r));
            }
        }

        log.info("HWFAIL", "stored for serial=" + serial + ": " + client.summarizeList(client.list(serial, 10)));
        log.info("HWFAIL", "sent " + (count - failed) + "/" + count + " ok");
        System.exit(failed == 0 ? 0 : 3);
    }
}
