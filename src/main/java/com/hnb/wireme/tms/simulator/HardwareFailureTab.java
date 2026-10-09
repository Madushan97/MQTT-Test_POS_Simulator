package com.hnb.wireme.tms.simulator;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/**
 * Hardware failures over the TMS REST API (the TMS has no MQTT topic for them):
 * load the code catalogue (GET hardware-failure-error-codes), report a failure (POST
 * hardware-failure-transactions) and read stored ones back (GET hardware-failure-transactions).
 * Codes offered come only from the catalogue; a code typed by hand is sent as typed.
 */
final class HardwareFailureTab extends JPanel {

    private record Code(HardwareFailureClient.ErrorCode c) {
        @Override
        public String toString() {
            return c.code() + "  " + c.subCategory() + "  " + c.description();
        }
    }

    private final ScheduledExecutorService exec;
    private final ExchangeLog log = new ExchangeLog(300);
    private final ExchangeConsole console = new ExchangeConsole(log, "POST", "GET", "PUT", "DELETE");
    private final Random random = new Random();

    private final JTextField baseUrl = new JTextField();
    private final JTextField category = new JTextField();
    private final JTextField serial = new JTextField();
    private final JTextField tid = new JTextField();
    private final JTextField mid = new JTextField();
    private final JCheckBox sendTidMid = new JCheckBox("Also send terminalId / merchantId");
    private final DefaultComboBoxModel<Object> codes = new DefaultComboBoxModel<>();
    private final JComboBox<Object> code = new JComboBox<>(codes);
    private final JCheckBox randomCode = new JCheckBox("Random code from catalogue each send", false);
    private final JSpinner count = new JSpinner(new SpinnerNumberModel(1, 1, 500, 1));
    private final JSpinner pageSize = new JSpinner(new SpinnerNumberModel(10, 1, 100, 1));
    private final JLabel catalogueState = new JLabel("catalogue not loaded");

    HardwareFailureTab(SimulatorConfig cfg, ScheduledExecutorService exec, Supplier<MqttTerminal.Identity> singleIdentity) {
        super(new BorderLayout());
        this.exec = exec;
        baseUrl.setText(cfg.get("tms.base-url", "http://localhost:8081/api/v1"));
        category.setText(cfg.get("hwfail.category", "HARDWARE_FAILURES"));
        serial.setText(cfg.get("terminal.serial-number", ""));
        tid.setText(cfg.get("terminal.terminal-id", ""));
        mid.setText(cfg.get("terminal.merchant-id", ""));
        sendTidMid.setSelected(cfg.getBool("hwfail.send-tid-mid", false));
        code.setEditable(true);

        JButton useSingle = new JButton("Use Single-terminal device");
        useSingle.addActionListener(e -> {
            MqttTerminal.Identity id = singleIdentity.get();
            serial.setText(id.serial());
            tid.setText(id.terminalId());
            mid.setText(id.merchantId());
        });
        JButton load = new JButton("Load codes (GET)");
        load.addActionListener(e -> exec.execute(this::loadCodes));
        JButton build = new JButton("Build POST");
        build.addActionListener(e -> console.loadDraft(postDraft()));
        JButton send = new JButton("Send POST ▸");
        send.setToolTipText("Sends 'Count' requests; with Random ticked, each gets its own code");
        send.addActionListener(e -> sendMany());
        JButton list = new JButton("List stored (GET)");
        list.addActionListener(e -> console.sendDraft(listDraft()));

        JPanel postRow = new JPanel(new GridLayout(1, 2, 4, 0));
        postRow.add(build);
        postRow.add(send);

        FormPanel form = new FormPanel()
                .section("Endpoint")
                .row("TMS base URL", baseUrl)
                .section("Error code catalogue")
                .row("Category", category)
                .full(load)
                .full(catalogueState)
                .section("Terminal")
                .full(useSingle)
                .row("Device serial", serial)
                .row("Terminal ID", tid)
                .row("Merchant ID", mid)
                .full(sendTidMid)
                .section("Report failure")
                .row("Error code", code)
                .full(randomCode)
                .row("Count", count)
                .full(postRow)
                .section("Read back")
                .row("Page size", pageSize)
                .full(list)
                .note("A real POS sends only deviceSerial + errorCode, so terminalId / merchantId are stored as null. "
                        + "Tick the box above to send them as well.")
                .end();

        console.loadDraft(postDraft());
        console.setSender(d -> HttpExchanger.send(log, d.method(), d.url(), d.headers(), d.body()));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(form), console);
        split.setDividerLocation(440);
        add(split, BorderLayout.CENTER);
    }

    private String api() {
        String b = baseUrl.getText().trim();
        return (b.endsWith("/") ? b.substring(0, b.length() - 1) : b) + "/advanced-insights/tms";
    }

    private void loadCodes() {
        String cat = category.getText().trim();
        String url = api() + "/hardware-failure-error-codes" + (cat.isEmpty() ? "" : "?category=" + enc(cat));
        Exchange ex = HttpExchanger.send(log, "GET", url, Map.of("Accept", "application/json"), "");
        List<HardwareFailureClient.ErrorCode> list;
        try {
            list = ex.ok() ? HardwareFailureClient.parseCodes(ex.responseBody()) : List.of();
        } catch (RuntimeException e) {
            list = List.of();
        }
        List<HardwareFailureClient.ErrorCode> loaded = list;
        boolean ok = ex.ok();
        SwingUtilities.invokeLater(() -> {
            codes.removeAllElements();
            loaded.forEach(c -> codes.addElement(new Code(c)));
            catalogueState.setText(!ok ? "load failed: " + ex.status()
                    : loaded.isEmpty() ? "no codes for category " + cat + ": nothing to pick from"
                    : loaded.size() + " codes loaded");
        });
    }

    private void sendMany() {
        int n = (Integer) count.getValue();
        if (n == 1 && !randomCode.isSelected()) {
            console.sendDraft(postDraft());
            return;
        }
        exec.execute(() -> {
            for (int i = 0; i < n; i++) {
                ExchangeConsole.Draft d = postDraft();
                HttpExchanger.send(log, d.method(), d.url(), d.headers(), d.body());
            }
        });
    }

    private ExchangeConsole.Draft postDraft() {
        boolean withIds = sendTidMid.isSelected();
        Map<String, Object> body = HardwareFailureClient.reportBody(serial.getText().trim(), pickCode(),
                withIds ? tid.getText().trim() : null, withIds ? mid.getText().trim() : null);
        return new ExchangeConsole.Draft("POST", api() + "/hardware-failure-transactions", jsonHeaders(), Json.pretty(body));
    }

    private ExchangeConsole.Draft listDraft() {
        String s = serial.getText().trim();
        String url = api() + "/hardware-failure-transactions?page=0&size=" + pageSize.getValue()
                + (s.isEmpty() ? "" : "&deviceSerial=" + enc(s));
        return new ExchangeConsole.Draft("GET", url, Map.of("Accept", "application/json"), "");
    }

    private String pickCode() {
        if (randomCode.isSelected() && codes.getSize() > 0) {
            return ((Code) codes.getElementAt(random.nextInt(codes.getSize()))).c().code();
        }
        Object v = code.getEditor().getItem();
        if (v instanceof Code c) {
            return c.c().code();
        }
        // typed by hand, or the display text of a catalogue entry: the code is the first token
        String text = v == null ? "" : v.toString().trim();
        int space = text.indexOf(' ');
        return space > 0 ? text.substring(0, space) : text;
    }

    private static Map<String, String> jsonHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json");
        return h;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
