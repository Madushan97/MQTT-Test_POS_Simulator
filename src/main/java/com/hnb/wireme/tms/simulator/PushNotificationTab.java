package com.hnb.wireme.tms.simulator;

import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

/**
 * The TMS push-notification round trip, both ends in one place:
 *
 *  1. Admin (REST): POST push/push-operations/create (one device) or /create/bulk (by device, terminal,
 *     merchant, MCC or partner ids) queues an operation as PEND. Header user-id is required.
 *  2. Delivery (MQTT): the TMS hands the oldest PEND operation to the device in its reply to the next
 *     ECHO on push-devices/message. (PushMessageManagementServiceImpl also has a /commands publisher,
 *     but nothing in the TMS calls it, so polling is the only delivery path today.)
 *  3. Device confirm (MQTT): update/confirm with status SUCCESS -> COMP, anything else -> FAIL. The TMS
 *     marks the device's first PEND operation, whatever operationCode the confirm carries.
 *  4. Check (REST): GET push/push-operations?key=serialNo&value=... and /jobs show the status.
 *
 * The device side uses the Single terminal tab's connection.
 */
final class PushNotificationTab extends JPanel {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** One delivered operation; repeat deliveries of the same unconfirmed op only bump the count. */
    private static final class Received {
        final String op;
        final Map<String, Object> params;
        final String via;
        final String firstAt = LocalTime.now().format(TIME);
        int deliveries = 1;
        String confirmed = "";

        Received(String op, Map<String, Object> params, String via) {
            this.op = op;
            this.params = params;
            this.via = via;
        }

        @Override
        public String toString() {
            return firstAt + "  " + op + "  " + params + "  via " + via
                    + (deliveries > 1 ? "  (delivered x" + deliveries + ")" : "")
                    + (confirmed.isEmpty() ? "" : "  -> " + confirmed);
        }
    }

    private final ScheduledExecutorService exec;
    private final SingleTerminalTab single;
    private final ExchangeLog log = new ExchangeLog(400);
    private final ExchangeConsole console = new ExchangeConsole(log, "POST", "GET", "PUBLISH");

    private final JTextField baseUrl = new JTextField();
    private final JTextField userId = new JTextField("simulator");
    private final JRadioButton oneDevice = new JRadioButton("One device (create)", true);
    private final JRadioButton bulk = new JRadioButton("Bulk (create/bulk)");
    private final JTextField serial = new JTextField();
    private final JTextField imei = new JTextField();
    private final JTextField tid = new JTextField();
    private final JTextField mid = new JTextField();
    private final JComboBox<String> bulkTarget = new JComboBox<>(new String[]{"terminalIds", "deviceIds", "merchantIds", "mccIds", "partnerIds"});
    private final JTextField bulkIds = new JTextField();
    private final JComboBox<String> opCode = new JComboBox<>(new String[]{"MSG", "DIN", "LOG", "BNR", "SET", "ACTV", "INT"});
    private final JTextArea params = new JTextArea(6, 20);
    private final JCheckBox manualConfirm = new JCheckBox("Confirm manually here (turns off auto-confirm)");
    private final DefaultListModel<Received> inbox = new DefaultListModel<>();
    private final JList<Received> inboxList = new JList<>(inbox);
    private final JSpinner pageSize = new JSpinner(new SpinnerNumberModel(10, 1, 100, 1));
    private final JLabel deviceState = new JLabel(" ");

    PushNotificationTab(SimulatorConfig cfg, ScheduledExecutorService exec, SingleTerminalTab single) {
        super(new BorderLayout());
        this.exec = exec;
        this.single = single;
        baseUrl.setText(cfg.get("tms.base-url", "http://localhost:8081/api/v1"));
        copyFromSingle();
        ButtonGroup g = new ButtonGroup();
        g.add(oneDevice);
        g.add(bulk);
        bulkIds.setToolTipText("Comma-separated. terminalIds are TID strings; the others are numeric DB ids.");
        params.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        opCode.addActionListener(e -> params.setText(template(String.valueOf(opCode.getSelectedItem()))));
        params.setText(template("MSG"));
        inboxList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        inboxList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JButton useSingle = new JButton("Use Single-terminal device");
        useSingle.addActionListener(e -> copyFromSingle());
        JButton build = new JButton("Build request");
        build.addActionListener(e -> withDraft(false));
        JButton send = new JButton("Send ▸");
        send.addActionListener(e -> withDraft(true));
        JPanel createRow = new JPanel(new GridLayout(1, 2, 4, 0));
        createRow.add(build);
        createRow.add(send);

        JButton poll = new JButton("Poll now (ECHO) ▸");
        poll.setToolTipText("The TMS only delivers a queued operation in the reply to the device's next ECHO");
        poll.addActionListener(e -> exec.execute(this::poll));
        JButton ok = new JButton("Confirm SUCCESS ▸");
        JButton fail = new JButton("Confirm FAIL ▸");
        ok.addActionListener(e -> confirmSelected("SUCCESS"));
        fail.addActionListener(e -> confirmSelected("FAIL"));
        JPanel confirmRow = new JPanel(new GridLayout(1, 2, 4, 0));
        confirmRow.add(ok);
        confirmRow.add(fail);
        manualConfirm.addActionListener(e -> single.setAutoConfirm(!manualConfirm.isSelected()));

        JButton listOps = new JButton("Operations for serial (GET) ▸");
        listOps.addActionListener(e -> console.sendDraft(getDraft("/push-operations?page=0&pageSize=" + pageSize.getValue()
                + "&key=serialNo&value=" + enc(serial.getText().trim()))));
        JButton listJobs = new JButton("Jobs (GET) ▸");
        listJobs.addActionListener(e -> console.sendDraft(getDraft("/push-operations/jobs?page=0&pageSize=" + pageSize.getValue())));
        JPanel checkRow = new JPanel(new GridLayout(1, 2, 4, 0));
        checkRow.add(listOps);
        checkRow.add(listJobs);

        JScrollPane paramsScroll = new JScrollPane(params);
        paramsScroll.setPreferredSize(new Dimension(100, 110));
        JScrollPane inboxScroll = new JScrollPane(inboxList);
        inboxScroll.setPreferredSize(new Dimension(100, 110));

        FormPanel form = new FormPanel()
                .section("1 · Queue an operation (admin REST)")
                .row("TMS base URL", baseUrl)
                .row("user-id header", userId)
                .full(oneDevice)
                .full(useSingle)
                .row("Serial no", serial)
                .row("IMEI", imei)
                .row("Terminal ID", tid)
                .row("Merchant ID", mid)
                .full(bulk)
                .row("Target", bulkTarget)
                .row("Ids", bulkIds)
                .row("Operation", opCode)
                .full(new JLabel("params (JSON object)"))
                .full(paramsScroll)
                .full(createRow)
                .section("2 · Device receives (MQTT, Single terminal)")
                .full(deviceState)
                .full(poll)
                .full(manualConfirm)
                .full(new JLabel("Delivered operations:"))
                .full(inboxScroll)
                .full(confirmRow)
                .section("3 · Check status (REST)")
                .row("Page size", pageSize)
                .full(checkRow)
                .note("Serial + IMEI must be an active device, and the device must have registered over MQTT at least "
                        + "once, otherwise the operation is stored NOT_REGISTERED and never delivered. One-device create "
                        + "takes string params only. MSG/BNR/SET param keys are examples: the TMS stores and relays "
                        + "params as-is. Confirms mark the device's oldest PEND operation. The ✔ / ✖ buttons under the "
                        + "response do the device's part for you: ECHO, then confirm SUCCESS or FAIL.")
                .end();

        single.addOperationListener((t, op, p, via) -> SwingUtilities.invokeLater(() -> onReceived(op, p, via)));
        console.setSender(d -> HttpExchanger.send(log, d.method(), d.url(), d.headers(), d.body()));
        console.addResponseAction("✔ Complete pending (SUCCESS → COMP)",
                "Act as the POS: ECHO to pull the oldest PEND operation, confirm it SUCCESS, then re-read the status",
                ex -> resolvePending(ex, "SUCCESS"));
        console.addResponseAction("✖ Ignore pending (FAIL → FAIL)",
                "Act as the POS: ECHO to pull the oldest PEND operation, confirm it FAIL so it stops being re-delivered",
                ex -> resolvePending(ex, "FAIL"));
        console.loadDraft(createDraft());

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(form), console);
        split.setDividerLocation(440);
        add(split, BorderLayout.CENTER);

        new Timer(1000, e -> refreshDeviceState()).start();
    }

    // ------------------------------------------------------------------ admin side

    private void withDraft(boolean send) {
        ExchangeConsole.Draft d;
        try {
            d = createDraft();
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Invalid input", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (send) {
            console.sendDraft(d);
        } else {
            console.loadDraft(d);
        }
    }

    private ExchangeConsole.Draft createDraft() {
        String op = String.valueOf(opCode.getSelectedItem());
        Map<String, Object> p = parseParams();
        Map<String, Object> body = new LinkedHashMap<>();
        String path;
        if (oneDevice.isSelected()) {
            Map<String, Object> bean = new LinkedHashMap<>();
            bean.put("serialNumber", serial.getText().trim());
            bean.put("imeiNumber", imei.getText().trim());
            bean.put("terminalId", tid.getText().trim());
            String m = mid.getText().trim();
            if (!m.isEmpty()) {
                bean.put("merchantId", m.matches("\\d+") ? (Object) new java.math.BigDecimal(m) : m);
            }
            bean.put("operationCode", op);
            if (p != null) {
                // PushOperationMessageBean.params is Map<String, String>
                Map<String, String> strings = new LinkedHashMap<>();
                p.forEach((k, v) -> strings.put(k, v == null ? null : v instanceof String s ? s : Json.pretty(v)));
                bean.put("params", strings);
            }
            body.put("pushOperationMessageBeans", List.of(bean));
            path = "/push-operations/create";
        } else {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("operationCode", op);
            message.put("params", p == null ? Map.of() : p);
            body.put("bulkPushOperationMessageBeans", message);
            body.put(String.valueOf(bulkTarget.getSelectedItem()), parseIds());
            path = "/push-operations/create/bulk";
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json");
        h.put("user-id", userId.getText().trim());
        return new ExchangeConsole.Draft("POST", api() + path, h, Json.pretty(body));
    }

    private Map<String, Object> parseParams() {
        String text = params.getText().trim();
        if (text.isEmpty()) {
            return null;
        }
        Map<String, Object> p = Json.object(text);
        if (p == null) {
            throw new IllegalArgumentException("params must be a JSON object, e.g. {\"title\": \"Hi\"}, or empty.");
        }
        return p;
    }

    private List<Object> parseIds() {
        boolean strings = "terminalIds".equals(bulkTarget.getSelectedItem());
        List<Object> ids = new ArrayList<>();
        for (String s : bulkIds.getText().split(",")) {
            String v = s.trim();
            if (v.isEmpty()) {
                continue;
            }
            if (!strings && !v.matches("\\d+")) {
                throw new IllegalArgumentException(bulkTarget.getSelectedItem() + " are numeric DB ids; got '" + v + "'.");
            }
            ids.add(strings ? v : (Object) Integer.parseInt(v));
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("Enter at least one id for " + bulkTarget.getSelectedItem() + ".");
        }
        return ids;
    }

    private ExchangeConsole.Draft getDraft(String path) {
        return new ExchangeConsole.Draft("GET", api() + path, Map.of("Accept", "application/json"), "");
    }

    /** Starting points only: ACTV/INT carry no params; DIN/LOG need none; the rest are free-form. */
    private static String template(String op) {
        return switch (op) {
            case "MSG" -> "{\n  \"title\": \"Simulator test\",\n  \"message\": \"Hello from the POS simulator\"\n}";
            case "BNR" -> "{\n  \"fileName\": \"banner.png\"\n}";
            case "ACTV", "INT" -> "";
            default -> "{}";
        };
    }

    // ------------------------------------------------------------------ device side

    private void poll() {
        MqttTerminal t = single.terminal();
        if (t == null || !t.isConnected()) {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                    "Connect the device on the Single terminal tab first.", "Not connected", JOptionPane.WARNING_MESSAGE));
            return;
        }
        t.publish(t.topic("message"), MqttTerminal.echoPayload(t.id), t.qos(), false, log);
    }

    private void onReceived(String op, Map<String, Object> p, String via) {
        for (int i = 0; i < inbox.size(); i++) {
            Received r = inbox.get(i);
            if (r.confirmed.isEmpty() && r.op.equals(op) && r.params.equals(p)) {
                r.deliveries++;
                inboxList.repaint();
                return;
            }
        }
        inbox.add(0, new Received(op, p, via));
        if (inboxList.getSelectedIndex() < 0) {
            inboxList.setSelectedIndex(0);
        }
    }

    private void confirmSelected(String status) {
        Received r = inboxList.getSelectedValue();
        MqttTerminal t = single.terminal();
        if (r == null || t == null) {
            JOptionPane.showMessageDialog(this, r == null ? "Select a delivered operation first." : "Device not connected.",
                    "Cannot confirm", JOptionPane.WARNING_MESSAGE);
            return;
        }
        r.confirmed = status + " sent";
        inboxList.repaint();
        exec.execute(() -> t.publish(t.topic("update/confirm"), MqttTerminal.confirmPayload(t.id, r.op, status),
                t.qos(), false, log));
    }

    // ------------------------------------------------------------------ complete / ignore (response-side buttons)

    /**
     * Does what the real POS would do: pull the oldest PEND operation with an ECHO, then confirm it.
     * The device is taken from the selected create request (its first pushOperationMessageBean), else from
     * the form. The TMS always resolves the device's oldest PEND operation, which is not necessarily the
     * one just created.
     */
    private void resolvePending(Exchange selected, String status) {
        MqttTerminal.Identity form = new MqttTerminal.Identity(serial.getText().trim(), imei.getText().trim(),
                tid.getText().trim(), mid.getText().trim(), "SIM-POS-X1", "1.0.0", 6.9271, 79.8612);
        MqttTerminal.Identity id = identityFrom(selected, form);
        if (id.serial().isEmpty() || id.imei().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Need a serial number and IMEI (select a create request, or fill the form).",
                    "Which device?", JOptionPane.WARNING_MESSAGE);
            return;
        }
        exec.execute(() -> runResolve(id, status));
    }

    @SuppressWarnings("unchecked")
    private static MqttTerminal.Identity identityFrom(Exchange ex, MqttTerminal.Identity fallback) {
        Map<String, Object> body = ex == null ? null : Json.object(ex.requestBody);
        Object beans = body == null ? null : body.get("pushOperationMessageBeans");
        if (beans instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m) {
            Map<String, Object> b = (Map<String, Object>) m;
            return new MqttTerminal.Identity(str(b.get("serialNumber")), str(b.get("imeiNumber")), str(b.get("terminalId")),
                    fallback.merchantId(), fallback.model(), fallback.firmware(), fallback.lat(), fallback.lng());
        }
        return fallback;
    }

    private void runResolve(MqttTerminal.Identity id, String status) {
        MqttTerminal t = deviceFor(id);
        if (t == null) {
            return;
        }
        boolean wasAuto = t.autoConfirm;
        t.autoConfirm = false; // this flow confirms with the chosen status; an auto SUCCESS must not race it
        try {
            Exchange echo = await(t.publish(t.topic("message"), MqttTerminal.echoPayload(t.id), t.qos(), false, log));
            if (!echo.ok()) {
                return; // NO REPLY / NOT CONNECTED is already in the history
            }
            Map<String, Object> reply = Json.object(echo.responseBody());
            String op = reply == null ? "" : str(reply.get("operationCode"));
            if (op.isEmpty() || MqttTerminal.ECHO.equals(op)) {
                log.add(Exchange.inbound(Exchange.Kind.MQTT, t.topic("message"), false, "NOTHING PENDING for " + id.serial(),
                        Map.of(), "The TMS's ECHO reply carried no operation: this device has no PEND push operation "
                                + "(or it was created NOT_REGISTERED and will never be delivered)."));
                return;
            }
            await(t.publish(t.topic("update/confirm"), MqttTerminal.confirmPayload(t.id, op, status), t.qos(), false, log));
            HttpExchanger.send(log, "GET", api() + "/push-operations?page=0&pageSize=" + pageSize.getValue()
                    + "&key=serialNo&value=" + enc(id.serial()), Map.of("Accept", "application/json"), "");
        } finally {
            t.autoConfirm = wasAuto;
        }
    }

    private MqttTerminal pushDevice;

    /** The Single tab's connection when it is this device; otherwise a dedicated one (kept for reuse). */
    private synchronized MqttTerminal deviceFor(MqttTerminal.Identity id) {
        MqttTerminal s = single.terminal();
        if (s != null && s.isConnected() && s.id.serial().equals(id.serial())) {
            return s;
        }
        try {
            if (pushDevice == null || !pushDevice.id.serial().equals(id.serial()) || !pushDevice.id.imei().equals(id.imei())) {
                if (pushDevice != null) {
                    pushDevice.close();
                }
                pushDevice = single.newTerminal(id, "ui-push-", log);
                pushDevice.autoConfirm = false;
            }
            if (!pushDevice.isConnected() && !pushDevice.connect().ok()) {
                return null; // CONNECT FAILED is in the history
            }
            // subscriptions (and the auto-register) complete asynchronously after CONNACK
            for (int i = 0; i < 50 && !pushDevice.isSubscribed(); i++) {
                Thread.sleep(100);
            }
            for (int i = 0; i < 30 && !pushDevice.isRegistered(); i++) {
                Thread.sleep(100);
            }
            return pushDevice;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.add(Exchange.inbound(Exchange.Kind.MQTT, id.serial(), false, "CANNOT CREATE DEVICE CLIENT", Map.of(), String.valueOf(e)));
            return null;
        }
    }

    /** Blocks (off the UI thread) until the exchange completes; MQTT replies time out on their own after 15 s. */
    private static Exchange await(Exchange ex) {
        for (int i = 0; i < 200 && !ex.done(); i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return ex;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private void refreshDeviceState() {
        MqttTerminal t = single.terminal();
        if (manualConfirm.isSelected() == single.isAutoConfirm()) {
            manualConfirm.setSelected(!single.isAutoConfirm()); // changed on the Single tab
        }
        deviceState.setText(t == null ? "Single terminal: not connected"
                : "<html><body style='width:300px'>Single terminal " + t.id.serial() + ": " + (t.isConnected() ? "<b style='color:green'>connected</b>"
                : "<b style='color:#b00'>disconnected</b>") + ", registered=" + t.isRegistered()
                + (single.isAutoConfirm() ? ", auto-confirms" : ", manual confirm") + "</html>");
    }

    private void copyFromSingle() {
        MqttTerminal.Identity id = single.identity();
        serial.setText(id.serial());
        imei.setText(id.imei());
        tid.setText(id.terminalId());
        mid.setText(id.merchantId());
    }

    private String api() {
        String b = baseUrl.getText().trim();
        return (b.endsWith("/") ? b.substring(0, b.length() - 1) : b) + "/advanced-insights/tms/push";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
