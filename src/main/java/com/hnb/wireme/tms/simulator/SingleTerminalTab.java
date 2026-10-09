package com.hnb.wireme.tms.simulator;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;

/**
 * One terminal on the MQTT push channel: connect/disconnect, register, ECHO, confirm. Every publish
 * shows the TMS's reply as its response; pushed commands appear as RECEIVE entries.
 */
final class SingleTerminalTab extends JPanel {

    private final ScheduledExecutorService exec;
    private final ExchangeLog log = new ExchangeLog(500);
    private final ExchangeConsole console = new ExchangeConsole(log, "PUBLISH");

    private final JTextField broker = new JTextField();
    private final JTextField username = new JTextField();
    private final JPasswordField password = new JPasswordField();
    private final JTextField prefix = new JTextField();
    private final JSpinner qos = new JSpinner(new SpinnerNumberModel(1, 0, 2, 1));
    private final JSpinner keepAlive = new JSpinner(new SpinnerNumberModel(30, 5, 600, 5));
    private final JTextField serial = new JTextField();
    private final JTextField imei = new JTextField();
    private final JTextField tid = new JTextField();
    private final JTextField mid = new JTextField();
    private final JTextField model = new JTextField();
    private final JTextField firmware = new JTextField();
    private final JCheckBox autoRegister = new JCheckBox("Register automatically on connect", true);
    private final JCheckBox autoConfirm = new JCheckBox("Auto-confirm pushed commands with status", true);
    private final JComboBox<String> confirmStatus = new JComboBox<>(new String[]{"SUCCESS", "FAIL"});
    private final JCheckBox autoEcho = new JCheckBox("Auto ECHO every (s)", false);
    private final JSpinner echoInterval = new JSpinner(new SpinnerNumberModel(10, 2, 3600, 1));
    private final JComboBox<String> confirmOp = new JComboBox<>(new String[]{"DIN", "REBOOT", "UPDATE", "LOCK"});
    private final JLabel state = new JLabel("not connected");

    private volatile MqttTerminal terminal;
    /** Re-attached to each new terminal created by Connect (the Push tab listens here). */
    private final List<MqttTerminal.OperationListener> operationListeners = new CopyOnWriteArrayList<>();

    SingleTerminalTab(SimulatorConfig cfg, ScheduledExecutorService exec) {
        super(new BorderLayout());
        this.exec = exec;
        broker.setText(cfg.get("broker.url", "tcp://localhost:1883"));
        username.setText(cfg.get("broker.username", ""));
        password.setText(cfg.get("broker.password", ""));
        prefix.setText(cfg.get("topic.prefix", "tms/push"));
        qos.setValue(cfg.getInt("qos", 1));
        keepAlive.setValue(cfg.getInt("keepalive.seconds", 30));
        serial.setText(cfg.get("terminal.serial-number", ""));
        imei.setText(cfg.get("terminal.imei", ""));
        tid.setText(cfg.get("terminal.terminal-id", ""));
        mid.setText(cfg.get("terminal.merchant-id", ""));
        model.setText(cfg.get("terminal.model", "SIM-POS-X1"));
        firmware.setText(cfg.get("terminal.firmware", "1.0.0"));
        echoInterval.setValue(cfg.getInt("echo.interval.seconds", 10));
        confirmOp.setEditable(true);

        JButton connect = new JButton("Connect");
        JButton disconnect = new JButton("Disconnect");
        connect.addActionListener(e -> exec.execute(this::connect));
        disconnect.addActionListener(e -> exec.execute(() -> {
            MqttTerminal t = terminal;
            if (t != null) {
                t.disconnect();
            }
        }));
        JPanel connRow = new JPanel(new GridLayout(1, 2, 4, 0));
        connRow.add(connect);
        connRow.add(disconnect);

        JPanel fillRow = new JPanel(new GridLayout(1, 3, 4, 0));
        JPanel sendRow = new JPanel(new GridLayout(1, 3, 4, 0));
        for (String action : new String[]{"Register", "Echo", "Confirm"}) {
            JButton fill = new JButton(action);
            fill.setToolTipText("Put the " + action + " request in the editor without sending it");
            fill.addActionListener(e -> console.loadDraft(draft(action)));
            fillRow.add(fill);
            JButton send = new JButton(action + " ▸");
            send.setToolTipText("Send the " + action + " request now");
            send.addActionListener(e -> console.sendDraft(draft(action)));
            sendRow.add(send);
        }

        autoEcho.addActionListener(e -> applyAutoEcho());
        echoInterval.addChangeListener(e -> applyAutoEcho());
        autoRegister.addActionListener(e -> applyFlags());
        autoConfirm.addActionListener(e -> applyFlags());
        confirmStatus.addActionListener(e -> applyFlags());

        JPanel echoRow = new JPanel(new BorderLayout(4, 0));
        echoRow.add(autoEcho, BorderLayout.WEST);
        echoRow.add(echoInterval, BorderLayout.CENTER);
        JPanel confirmRow = new JPanel(new BorderLayout(4, 0));
        confirmRow.add(autoConfirm, BorderLayout.WEST);
        confirmRow.add(confirmStatus, BorderLayout.CENTER);

        FormPanel form = new FormPanel()
                .section("Broker")
                .row("Broker URL", broker)
                .row("Username", username)
                .row("Password", password)
                .row("Topic prefix", prefix)
                .row("QoS", qos)
                .row("Keep-alive (s)", keepAlive)
                .section("Terminal")
                .row("Serial no", serial)
                .row("IMEI", imei)
                .row("Terminal ID", tid)
                .row("Merchant ID", mid)
                .row("Model", model)
                .row("Firmware", firmware)
                .section("Connection")
                .full(connRow)
                .full(state)
                .full(autoRegister)
                .full(confirmRow)
                .full(echoRow)
                .section("Requests")
                .row("Confirm operation", confirmOp)
                .row("Edit first:", fillRow)
                .row("Send now:", sendRow)
                .note("Serial + IMEI must be an ACTIVE device in the TMS DB, otherwise register answers 101. "
                        + "Changes to broker/terminal fields apply on the next Connect.")
                .end();

        console.setSender(d -> {
            MqttTerminal t = terminal;
            if (t == null) {
                throw new IllegalStateException("Press Connect first.");
            }
            int q = parseInt(d.headers().get("QoS"), t.qos());
            boolean retained = Boolean.parseBoolean(d.headers().getOrDefault("Retained", "false"));
            return t.publish(d.url(), d.body(), q, retained);
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(form), console);
        split.setDividerLocation(440);
        add(split, BorderLayout.CENTER);

        new Timer(1000, e -> refreshState()).start();
    }

    /** Current form values (also used by the Sale and Hardware tabs' "use Single terminal" buttons). */
    MqttTerminal.Identity identity() {
        return new MqttTerminal.Identity(serial.getText().trim(), imei.getText().trim(), tid.getText().trim(),
                mid.getText().trim(), model.getText().trim(), firmware.getText().trim(), 6.9271, 79.8612);
    }

    /** A new, unconnected terminal for {@code id} on this tab's broker settings (used by the Push tab). */
    MqttTerminal newTerminal(MqttTerminal.Identity id, String clientIdPrefix, ExchangeLog into) throws Exception {
        return new MqttTerminal(id, broker.getText().trim(), username.getText().trim(), new String(password.getPassword()),
                prefix.getText().trim(), (Integer) qos.getValue(), (Integer) keepAlive.getValue(), 10, clientIdPrefix, into, exec);
    }

    /** The live terminal, or null before the first Connect. */
    MqttTerminal terminal() {
        return terminal;
    }

    void addOperationListener(MqttTerminal.OperationListener l) {
        operationListeners.add(l);
        MqttTerminal t = terminal;
        if (t != null) {
            t.addOperationListener(l);
        }
    }

    /** Lets the Push tab switch the device to manual confirms; keeps this tab's checkbox in step. */
    void setAutoConfirm(boolean on) {
        SwingUtilities.invokeLater(() -> {
            autoConfirm.setSelected(on);
            applyFlags();
        });
    }

    boolean isAutoConfirm() {
        return autoConfirm.isSelected();
    }

    private void connect() {
        MqttTerminal old = terminal;
        if (old != null) {
            old.close();
        }
        try {
            MqttTerminal t = new MqttTerminal(identity(), broker.getText().trim(), username.getText().trim(),
                    new String(password.getPassword()), prefix.getText().trim(), (Integer) qos.getValue(),
                    (Integer) keepAlive.getValue(), 10, "ui-pos-", log, exec);
            operationListeners.forEach(t::addOperationListener);
            terminal = t;
            applyFlags();
            t.connect();
            SwingUtilities.invokeLater(this::applyAutoEcho);
        } catch (Exception e) {
            log.add(Exchange.inbound(Exchange.Kind.MQTT, broker.getText(), false, "CANNOT CREATE CLIENT", Map.of(),
                    String.valueOf(e)));
        }
    }

    private ExchangeConsole.Draft draft(String action) {
        // Connected: the live terminal's identity/topics. Otherwise the form's, so a payload can be previewed.
        MqttTerminal t = terminal;
        MqttTerminal.Identity id = t != null ? t.id : identity();
        String base = t != null ? t.topic("") : prefix.getText().trim() + "/push-devices/";
        Map<String, String> headers = Map.of("QoS", String.valueOf(t != null ? t.qos() : qos.getValue()), "Retained", "false");
        String op = String.valueOf(confirmOp.getSelectedItem()).trim();
        return switch (action) {
            case "Register" -> new ExchangeConsole.Draft("PUBLISH", base + "create", headers, MqttTerminal.registerPayload(id));
            case "Echo" -> new ExchangeConsole.Draft("PUBLISH", base + "message", headers, MqttTerminal.echoPayload(id));
            default -> new ExchangeConsole.Draft("PUBLISH", base + "update/confirm", headers,
                    MqttTerminal.confirmPayload(id, op, String.valueOf(confirmStatus.getSelectedItem())));
        };
    }

    private void applyFlags() {
        MqttTerminal t = terminal;
        if (t != null) {
            t.autoRegister = autoRegister.isSelected();
            t.autoConfirm = autoConfirm.isSelected();
            t.confirmStatus = String.valueOf(confirmStatus.getSelectedItem());
        }
    }

    private void applyAutoEcho() {
        MqttTerminal t = terminal;
        if (t == null) {
            return;
        }
        if (autoEcho.isSelected()) {
            t.startAutoEcho((Integer) echoInterval.getValue());
        } else {
            t.stopAutoEcho();
        }
    }

    private void refreshState() {
        MqttTerminal t = terminal;
        if (t == null) {
            state.setText("not connected");
            return;
        }
        state.setText("<html>" + (t.isConnected() ? "<b style='color:green'>CONNECTED</b>" : "<b style='color:#b00'>DISCONNECTED</b>")
                + " &nbsp; registered=" + t.isRegistered() + " &nbsp; echo " + t.echoReceived() + "/" + t.echoSent()
                + "<br><small>" + escape(t.lastEvent()) + "</small></html>");
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
