package com.hnb.wireme.tms.simulator;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Many terminals at once on the MQTT push channel (the FleetSimulator's connection side, without its
 * sales/churn). Devices come from the DB (random active DEVICES joined to TERMINALS/MERCHANTS, as
 * FleetSimulator does) or from a pasted list. Each device keeps its own history; select a row to see
 * its requests and responses. The fleet log holds the DB statements and their results.
 */
final class BulkFleetTab extends JPanel {

    private static final int PER_DEVICE_HISTORY = 100;

    private final ScheduledExecutorService exec;
    private final ExchangeLog fleetLog = new ExchangeLog(200);
    private final ExchangeConsole console = new ExchangeConsole(fleetLog, "PUBLISH");
    private final List<MqttTerminal> devices = new CopyOnWriteArrayList<>();
    private final DeviceTableModel tableModel = new DeviceTableModel();
    private final JTable table = new JTable(tableModel);
    private final AtomicBoolean starting = new AtomicBoolean();
    private final Random random = new Random();

    private final JRadioButton fromDb = new JRadioButton("Random active devices from DB", true);
    private final JRadioButton fromList = new JRadioButton("Pasted list");
    private final JTextField dbHost = new JTextField();
    private final JTextField dbPort = new JTextField();
    private final JTextField dbSid = new JTextField();
    private final JTextField dbUser = new JTextField();
    private final JPasswordField dbPassword = new JPasswordField();
    private final JSpinner fleetSize = new JSpinner(new SpinnerNumberModel(20, 1, 500, 1));
    private final JCheckBox seed = new JCheckBox("Seed missing DEVICE_STATUS rows (WRITES to DB)", true);
    private final JTextArea pasted = new JTextArea(5, 20);
    private final JTextField broker = new JTextField();
    private final JTextField prefix = new JTextField();
    private final JSpinner qos = new JSpinner(new SpinnerNumberModel(1, 0, 2, 1));
    private final JSpinner rampMs = new JSpinner(new SpinnerNumberModel(150, 0, 10000, 50));
    private final JCheckBox autoEcho = new JCheckBox("Auto ECHO every (s)", true);
    private final JSpinner echoInterval = new JSpinner(new SpinnerNumberModel(20, 2, 3600, 1));
    private final JCheckBox autoConfirm = new JCheckBox("Auto-confirm pushed commands (SUCCESS)", true);
    private final JLabel summary = new JLabel(" ");
    private final JLabel showing = new JLabel("Showing: fleet log");

    BulkFleetTab(SimulatorConfig fleetCfg, ScheduledExecutorService exec) {
        super(new BorderLayout());
        this.exec = exec;
        dbHost.setText(fleetCfg.get("db.host", ""));
        dbPort.setText(fleetCfg.get("db.port", "1521"));
        dbSid.setText(fleetCfg.get("db.sid", "ORCL"));
        dbUser.setText(fleetCfg.get("db.username", ""));
        dbPassword.setText(fleetCfg.get("db.password", ""));
        fleetSize.setValue(Math.min(500, fleetCfg.getInt("fleet.size", 20)));
        broker.setText(fleetCfg.get("broker.url", "tcp://localhost:1883"));
        prefix.setText(fleetCfg.get("topic.prefix", "tms/push"));
        qos.setValue(fleetCfg.getInt("qos", 1));
        rampMs.setValue(fleetCfg.getInt("fleet.connect-ramp-up-ms", 150));
        echoInterval.setValue(fleetCfg.getInt("echo.interval.seconds", 20));
        pasted.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        pasted.setToolTipText("One device per line: serial,imei,terminalId,merchantId");
        pasted.setText("# serial,imei,terminalId,merchantId\n");
        ButtonGroup g = new ButtonGroup();
        g.add(fromDb);
        g.add(fromList);

        JButton start = new JButton("Load & connect all");
        JButton stop = new JButton("Disconnect & remove all");
        JButton echoAll = new JButton("Echo all");
        JButton registerAll = new JButton("Register all");
        JButton connectSel = new JButton("Connect selected");
        JButton disconnectSel = new JButton("Disconnect selected");
        JButton fleetLogBtn = new JButton("Show fleet log");
        start.addActionListener(e -> exec.execute(this::start));
        stop.addActionListener(e -> exec.execute(this::stopAll));
        echoAll.addActionListener(e -> forAll(MqttTerminal::echo));
        registerAll.addActionListener(e -> forAll(MqttTerminal::register));
        connectSel.addActionListener(e -> forSelected(t -> {
            t.connect();
            applyEcho(t);
        }));
        disconnectSel.addActionListener(e -> forSelected(MqttTerminal::disconnect));
        fleetLogBtn.addActionListener(e -> {
            table.clearSelection();
            showLog(null);
        });
        autoEcho.addActionListener(e -> devices.forEach(this::applyEcho));
        autoConfirm.addActionListener(e -> devices.forEach(t -> t.autoConfirm = autoConfirm.isSelected()));

        JPanel actions = new JPanel(new GridLayout(0, 2, 4, 4));
        for (JButton b : new JButton[]{start, stop, echoAll, registerAll, connectSel, disconnectSel}) {
            actions.add(b);
        }
        JPanel echoRow = new JPanel(new BorderLayout(4, 0));
        echoRow.add(autoEcho, BorderLayout.WEST);
        echoRow.add(echoInterval, BorderLayout.CENTER);

        JScrollPane pastedScroll = new JScrollPane(pasted);
        pastedScroll.setPreferredSize(new Dimension(100, 90));
        FormPanel form = new FormPanel()
                .section("Devices")
                .full(fromDb)
                .row("DB host", dbHost)
                .row("DB port", dbPort)
                .row("DB SID", dbSid)
                .row("DB user", dbUser)
                .row("DB password", dbPassword)
                .row("Fleet size", fleetSize)
                .full(seed)
                .full(fromList)
                .full(pastedScroll)
                .section("Broker")
                .row("Broker URL", broker)
                .row("Topic prefix", prefix)
                .row("QoS", qos)
                .row("Connect ramp-up (ms)", rampMs)
                .full(echoRow)
                .full(autoConfirm)
                .section("Fleet")
                .full(actions)
                .full(summary)
                .note("The seed step works around the TMS's first-ECHO DEVICE_STATUS bug (see README): it INSERTs "
                        + "a row only for devices that have none. Untick it to keep the run read-only. Pasted devices "
                        + "have no DEVICES.ID, so they are never seeded.")
                .end();

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = table.getSelectedRow();
                showLog(row >= 0 && row < devices.size() ? devices.get(table.convertRowIndexToModel(row)) : null);
            }
        });
        console.setSender(d -> {
            MqttTerminal t = selected();
            if (t == null) {
                throw new IllegalStateException("Select a device in the table first.");
            }
            int q;
            try {
                q = Integer.parseInt(d.headers().getOrDefault("QoS", String.valueOf(t.qos())).trim());
            } catch (NumberFormatException ex) {
                q = t.qos();
            }
            return t.publish(d.url(), d.body(), q, Boolean.parseBoolean(d.headers().getOrDefault("Retained", "false")));
        });

        JPanel tablePanel = new JPanel(new BorderLayout());
        JPanel tableBar = new JPanel(new BorderLayout());
        tableBar.add(showing, BorderLayout.WEST);
        tableBar.add(fleetLogBtn, BorderLayout.EAST);
        tablePanel.add(new JScrollPane(table), BorderLayout.CENTER);
        tablePanel.add(tableBar, BorderLayout.SOUTH);
        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tablePanel, console);
        right.setDividerLocation(230);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(form), right);
        split.setDividerLocation(440);
        add(split, BorderLayout.CENTER);

        new Timer(1000, e -> {
            if (tableModel.getRowCount() > 0) {
                tableModel.fireTableRowsUpdated(0, tableModel.getRowCount() - 1);
            }
            summary.setText(summaryText());
        }).start();
    }

    // ------------------------------------------------------------------ actions

    private void start() {
        if (!starting.compareAndSet(false, true)) {
            return;
        }
        try {
            List<DeviceDbClient.DbDevice> list = fromDb.isSelected() ? loadFromDb() : parsePasted();
            if (list.isEmpty()) {
                return;
            }
            if (fromDb.isSelected() && seed.isSelected()) {
                seedDeviceStatus(list);
            }
            long delay = 0;
            for (DeviceDbClient.DbDevice d : list) {
                if (devices.stream().anyMatch(t -> t.id.serial().equals(d.serialNo()))) {
                    continue; // same client id would kick the existing session off the broker
                }
                double lat = d.lat() != null ? d.lat() : 6.9271 + (random.nextDouble() - 0.5) * 0.6;
                double lng = d.lng() != null ? d.lng() : 79.8612 + (random.nextDouble() - 0.5) * 0.6;
                MqttTerminal.Identity id = new MqttTerminal.Identity(d.serialNo(), d.emiNo(), d.terminalId(),
                        d.merchantId(), "SIM-POS-X1", "1.0.0", lat, lng);
                try {
                    MqttTerminal t = new MqttTerminal(id, broker.getText().trim(), "", "", prefix.getText().trim(),
                            (Integer) qos.getValue(), 30, 10, "ui-fleet-", new ExchangeLog(PER_DEVICE_HISTORY), exec);
                    t.autoConfirm = autoConfirm.isSelected();
                    devices.add(t);
                    exec.schedule(() -> {
                        t.connect();
                        applyEcho(t);
                    }, delay, TimeUnit.MILLISECONDS);
                    delay += (Integer) rampMs.getValue();
                } catch (Exception e) {
                    fleetLog.add(Exchange.inbound(Exchange.Kind.MQTT, broker.getText(), false,
                            "CANNOT CREATE CLIENT for " + d.serialNo(), Map.of(), String.valueOf(e)));
                }
            }
            SwingUtilities.invokeLater(tableModel::fireTableDataChanged);
        } finally {
            starting.set(false);
        }
    }

    private List<DeviceDbClient.DbDevice> loadFromDb() {
        String url = jdbcUrl();
        int limit = (Integer) fleetSize.getValue();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User", dbUser.getText().trim());
        h.put("Bind :1 (FETCH FIRST n)", String.valueOf(limit));
        Exchange ex = fleetLog.add(new Exchange(Exchange.Kind.DB, "SELECT", url, h, DeviceDbClient.SQL));
        try {
            List<DeviceDbClient.DbDevice> list = new DeviceDbClient().fetchRandomActiveDevices(url, dbUser.getText().trim(),
                    new String(dbPassword.getPassword()), limit);
            StringBuilder sb = new StringBuilder("SERIALNO, EMINO, TERMINALID, MERCHANTID, LAT, LNG\n");
            for (DeviceDbClient.DbDevice d : list) {
                sb.append(d.serialNo()).append(", ").append(d.emiNo()).append(", ").append(d.terminalId()).append(", ")
                        .append(d.merchantId()).append(", ").append(d.lat()).append(", ").append(d.lng()).append('\n');
            }
            ex.complete(!list.isEmpty(), list.size() + " row(s)", Map.of(), sb.toString());
            return list;
        } catch (Exception e) {
            ex.complete(false, "QUERY FAILED", Map.of(), String.valueOf(e));
            return List.of();
        }
    }

    private void seedDeviceStatus(List<DeviceDbClient.DbDevice> list) {
        List<Long> ids = list.stream().map(DeviceDbClient.DbDevice::deviceId).toList();
        Exchange ex = fleetLog.add(new Exchange(Exchange.Kind.DB, "INSERT", jdbcUrl(),
                Map.of("Rows attempted", String.valueOf(ids.size())), DeviceStatusSeeder.SEED_SQL + "\n-- DEVICE_IDs: " + ids));
        try {
            TerminalLog seedLog = TerminalLog.forFileName(Path.of("logs"), "ui-fleet.log");
            int seeded = new DeviceStatusSeeder().seedMissing(jdbcUrl(), dbUser.getText().trim(),
                    new String(dbPassword.getPassword()), ids, seedLog);
            ex.complete(true, seeded + " row(s) inserted", Map.of(),
                    seeded + " of " + ids.size() + " devices had no DEVICE_STATUS row and were seeded; the rest already had one.");
        } catch (Exception e) {
            ex.complete(false, "SEED FAILED", Map.of(), String.valueOf(e));
        }
    }

    private List<DeviceDbClient.DbDevice> parsePasted() {
        List<DeviceDbClient.DbDevice> list = new ArrayList<>();
        StringBuilder skipped = new StringBuilder();
        for (String line : pasted.getText().split("\\R")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            String[] p = l.split("\\s*,\\s*");
            if (p.length < 2) {
                skipped.append(l).append('\n');
                continue;
            }
            list.add(new DeviceDbClient.DbDevice(0, p[0], p[1], p.length > 2 ? p[2] : "", p.length > 3 ? p[3] : "", null, null));
        }
        fleetLog.add(Exchange.inbound(Exchange.Kind.DB, "pasted list", !list.isEmpty(), list.size() + " device(s) parsed",
                Map.of(), skipped.isEmpty() ? "" : "Skipped (need at least serial,imei):\n" + skipped));
        return list;
    }

    private void stopAll() {
        List<MqttTerminal> all = new ArrayList<>(devices);
        devices.clear();
        SwingUtilities.invokeLater(() -> {
            tableModel.fireTableDataChanged();
            showLog(null);
        });
        for (MqttTerminal t : all) {
            exec.execute(t::close);
        }
        fleetLog.add(Exchange.inbound(Exchange.Kind.MQTT, broker.getText(), true,
                "removed " + all.size() + " device(s)", Map.of(), ""));
    }

    private void forAll(Consumer<MqttTerminal> action) {
        for (MqttTerminal t : devices) {
            exec.execute(() -> action.accept(t));
        }
    }

    private void forSelected(Consumer<MqttTerminal> action) {
        MqttTerminal t = selected();
        if (t != null) {
            exec.execute(() -> action.accept(t));
        }
    }

    private void applyEcho(MqttTerminal t) {
        if (autoEcho.isSelected()) {
            t.startAutoEcho((Integer) echoInterval.getValue());
        } else {
            t.stopAutoEcho();
        }
    }

    private MqttTerminal selected() {
        int row = table.getSelectedRow();
        return row >= 0 && row < devices.size() ? devices.get(table.convertRowIndexToModel(row)) : null;
    }

    private void showLog(MqttTerminal t) {
        console.setLog(t == null ? fleetLog : t.log);
        showing.setText(t == null ? "Showing: fleet log (DB statements)" : "Showing: " + t.id.serial());
        if (t != null) {
            String base = prefix.getText().trim() + "/push-devices/";
            console.loadDraft(new ExchangeConsole.Draft("PUBLISH", base + "message",
                    Map.of("QoS", String.valueOf(t.qos()), "Retained", "false"), MqttTerminal.echoPayload(t.id)));
        }
    }

    private String jdbcUrl() {
        return "jdbc:oracle:thin:@" + dbHost.getText().trim() + ":" + dbPort.getText().trim() + "/" + dbSid.getText().trim();
    }

    private String summaryText() {
        long connected = devices.stream().filter(MqttTerminal::isConnected).count();
        long registered = devices.stream().filter(MqttTerminal::isRegistered).count();
        long sent = devices.stream().mapToLong(MqttTerminal::echoSent).sum();
        long recv = devices.stream().mapToLong(MqttTerminal::echoReceived).sum();
        return "<html>devices=" + devices.size() + " &nbsp; connected=" + connected + " &nbsp; registered=" + registered
                + "<br>echo replies " + recv + "/" + sent + "</html>";
    }

    private final class DeviceTableModel extends AbstractTableModel {
        private final String[] cols = {"#", "Serial", "TID", "MID", "MQTT", "Registered", "Echo recv/sent", "Last event"};

        @Override
        public int getRowCount() {
            return devices.size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            if (r >= devices.size()) {
                return "";
            }
            MqttTerminal t = devices.get(r);
            return switch (c) {
                case 0 -> r + 1;
                case 1 -> t.id.serial();
                case 2 -> t.id.terminalId();
                case 3 -> t.id.merchantId();
                case 4 -> t.isConnected() ? "connected" : "disconnected";
                case 5 -> t.isRegistered() ? "yes" : "no";
                case 6 -> t.echoReceived() + "/" + t.echoSent();
                default -> t.lastEvent();
            };
        }
    }
}
