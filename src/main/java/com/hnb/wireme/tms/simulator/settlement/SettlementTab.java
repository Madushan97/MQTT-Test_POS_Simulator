package com.hnb.wireme.tms.simulator.settlement;

import com.hnb.wireme.tms.simulator.common.Exchange;
import com.hnb.wireme.tms.simulator.common.ExchangeLog;
import com.hnb.wireme.tms.simulator.common.HttpExchanger;
import com.hnb.wireme.tms.simulator.common.Json;
import com.hnb.wireme.tms.simulator.config.SimulatorConfig;
import com.hnb.wireme.tms.simulator.mqtt.MqttTerminal;
import com.hnb.wireme.tms.simulator.sale.CoreSaleClient;
import com.hnb.wireme.tms.simulator.ui.ExchangeConsole;
import com.hnb.wireme.tms.simulator.ui.FormPanel;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.HierarchyEvent;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Settles a terminal's open batches at WireMe core ({@code POST core.settle-url}).
 *
 * Type or change the TID / MID and the tab looks the terminal up in the DB by itself: its open
 * batches (BATCHES.ISSETTLED = '0', newest first) with their transactions totalled per type, and the
 * latest transactions of the highlighted batch. Each ticked batch becomes one entry of
 * settleAcquirers, its counts and amounts taken from those totals:
 *
 *   sale -> sale*,  void-sale -> saleVoid*,  offline -> offline*,  void-offline -> offlineVoid*,
 *   precomp -> precomp*   (other types, e.g. preauth or installment, have no field in the request)
 */
public final class SettlementTab extends JPanel {

    private static final DateTimeFormatter CORE_DATETIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int TXN_LIMIT = 15;

    private final ExecutorService exec;
    private final ExchangeLog log = new ExchangeLog(300);
    private final ExchangeConsole console = new ExchangeConsole(log, "POST", "PUT");
    private final SecureRandom random = new SecureRandom();

    private final JTextField url = new JTextField();
    private final JTextField dbHost = new JTextField();
    private final JTextField dbPort = new JTextField();
    private final JTextField dbSid = new JTextField();
    private final JTextField dbUser = new JTextField();
    private final JPasswordField dbPassword = new JPasswordField();
    private final JTextField serial = new JTextField();
    private final JTextField tid = new JTextField();
    private final JTextField mid = new JTextField();
    private final JComboBox<String> settledMethod = new JComboBox<>(new String[]{"1", "0", "2"});
    private final JLabel status = new JLabel(" ");
    private final JLabel txnTitle = new JLabel(" ");

    private final BatchModel batchModel = new BatchModel();
    private final JTable batchTable = new JTable(batchModel);
    private final TxnModel txnModel = new TxnModel();
    private final JTable txnTable = new JTable(txnModel);

    /** Bumped on every lookup so a slow, older result never overwrites a newer one. */
    private final AtomicInteger lookupGen = new AtomicInteger();
    private final AtomicInteger txnGen = new AtomicInteger();
    private final Timer autoLookup = new Timer(700, e -> lookup(true));
    private boolean lookedUpOnce;
    private Consumer<Set<String>> onSettled = keys -> { };

    public SettlementTab(SimulatorConfig singleCfg, SimulatorConfig fleetCfg, ExecutorService exec,
                         Supplier<MqttTerminal.Identity> singleIdentity) {
        super(new BorderLayout());
        this.exec = exec;
        String saleUrl = fleetCfg.get("core.sale-url", "http://localhost:28071/api/core/v1/transactions");
        url.setText(fleetCfg.get("core.settle-url", saleUrl.replaceAll("/+$", "") + "/settle-state"));
        dbHost.setText(fleetCfg.get("db.host", ""));
        dbPort.setText(fleetCfg.get("db.port", "1521"));
        dbSid.setText(fleetCfg.get("db.sid", "ORCL"));
        dbUser.setText(fleetCfg.get("db.username", ""));
        dbPassword.setText(fleetCfg.get("db.password", ""));
        serial.setText(singleCfg.get("terminal.serial-number", ""));
        tid.setText(singleCfg.get("terminal.terminal-id", ""));
        mid.setText(singleCfg.get("terminal.merchant-id", ""));
        settledMethod.setEditable(true);
        // new lookup result or a tick changed: rebuild the request in the editor
        batchModel.addTableModelListener(e -> autoDraft());
        settledMethod.addActionListener(e -> autoDraft());

        autoLookup.setRepeats(false);
        DocumentListener changed = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { autoLookup.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { autoLookup.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { autoLookup.restart(); }
        };
        tid.getDocument().addDocumentListener(changed);
        mid.getDocument().addDocumentListener(changed);
        // first lookup when the tab is first shown, not at app start-up
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing() && !lookedUpOnce) {
                lookup(true);
            }
        });

        JButton useSingle = new JButton("Use Single-terminal device");
        useSingle.addActionListener(e -> {
            MqttTerminal.Identity id = singleIdentity.get();
            serial.setText(id.serial());
            tid.setText(id.terminalId());
            mid.setText(id.merchantId());
        });
        JButton reload = new JButton("Reload open batches");
        reload.addActionListener(e -> lookup(true));

        batchTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        batchTable.getColumnModel().getColumn(0).setMaxWidth(50);
        batchTable.getColumnModel().getColumn(1).setMaxWidth(60);
        batchTable.getColumnModel().getColumn(3).setMaxWidth(50);
        batchTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                loadTransactions();
            }
        });
        JScrollPane batchScroll = new JScrollPane(batchTable);
        batchScroll.setPreferredSize(new Dimension(380, 150));
        txnTable.getColumnModel().getColumn(0).setPreferredWidth(110);
        JScrollPane txnScroll = new JScrollPane(txnTable);
        txnScroll.setPreferredSize(new Dimension(380, 200));

        JButton build = new JButton("Build request");
        build.setToolTipText("Fill the editor from the ticked batches without sending");
        build.addActionListener(e -> withDraft(console::loadDraft));
        JButton buildSend = new JButton("Build & settle ▸");
        buildSend.addActionListener(e -> withDraft(console::sendDraft));
        JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
        buttons.add(build);
        buttons.add(buildSend);

        FormPanel form = new FormPanel()
                .section("Endpoint")
                .row("Settle URL", url)
                .section("Database (read-only lookup)")
                .row("Host", dbHost)
                .row("Port", dbPort)
                .row("Service / SID", dbSid)
                .row("User", dbUser)
                .row("Password", dbPassword)
                .section("Terminal")
                .full(useSingle)
                .row("Device serial", serial)
                .row("Terminal ID", tid)
                .row("Merchant ID", mid)
                .section("Open batches (tick the ones to settle)")
                .full(batchScroll)
                .full(reload)
                .full(status)
                .row("settledMethod", settledMethod)
                .full(buttons)
                .section("Transactions in the highlighted batch")
                .full(txnTitle)
                .full(txnScroll)
                .note("Changing the Terminal ID or Merchant ID looks the terminal's open batches up again. Each "
                        + "ticked batch is one <b>settleAcquirers</b> entry; counts and amounts are the batch's "
                        + "transactions totalled per type (sale, void-sale, offline, void-offline, precomp). "
                        + "<b>settledDate</b> is the time you build. Check the request before sending; it stays editable.")
                .end();

        console.loadDraft(new ExchangeConsole.Draft("POST", url.getText(), headers(), ""));
        console.setSender(d -> {
            Exchange ex = HttpExchanger.send(log, d.method(), d.url(), d.headers(), d.body());
            if (ex.ok()) {
                Set<String> keys = settledKeys(d.body());
                SwingUtilities.invokeLater(() -> {
                    onSettled.accept(keys);
                    lookup(false); // settled batches drop out; not logged, so the settle response stays in view
                });
            }
            return ex;
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(form), console);
        split.setDividerLocation(460);
        add(split, BorderLayout.CENTER);
    }

    /** Called with the batchKeys core accepted a settlement for (the Sale tab opens a new batch). */
    public void setOnSettled(Consumer<Set<String>> onSettled) {
        this.onSettled = onSettled;
    }

    // ------------------------------------------------------------------ lookups

    /** @param record whether the query shows up in the history */
    private void lookup(boolean record) {
        autoLookup.stop();
        lookedUpOnce = true;
        String t = tid.getText().trim();
        String m = mid.getText().trim();
        int gen = lookupGen.incrementAndGet();
        if (t.isEmpty()) {
            batchModel.set(List.of());
            status.setText("Enter a Terminal ID");
            return;
        }
        status.setText("Looking up open batches of TID " + t + (m.isEmpty() ? "" : " / MID " + m) + " ...");
        SettlementDb db = db();
        String jdbc = jdbcUrl();
        exec.execute(() -> {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("User", dbUser.getText().trim());
            h.put("Bind TERMINALID", t);
            h.put("Bind MERCHANTID", m.isEmpty() ? "(any)" : m);
            Exchange query = new Exchange(Exchange.Kind.DB, "SELECT", jdbc, h,
                    SettlementDb.BATCHES_SQL + "\n" + SettlementDb.TOTALS_SQL);
            Exchange ex = record ? log.add(query) : query;
            try {
                List<SettlementDb.OpenBatch> batches = db.openBatches(t, m);
                ex.complete(true, batches.size() + " open batch(es)", Map.of(), describe(batches));
                SwingUtilities.invokeLater(() -> {
                    if (gen != lookupGen.get()) {
                        return;
                    }
                    batchModel.set(batches);
                    status.setText(batches.isEmpty()
                            ? "No open batches for TID " + t + (m.isEmpty() ? "" : " / MID " + m)
                            : batches.size() + " open batch(es); newest ticked");
                    if (!batches.isEmpty()) {
                        batchTable.setRowSelectionInterval(0, 0);
                    } else {
                        txnModel.set(List.of());
                        txnTitle.setText(" ");
                    }
                });
            } catch (Exception e) {
                ex.complete(false, "QUERY FAILED", Map.of(), String.valueOf(e));
                SwingUtilities.invokeLater(() -> {
                    if (gen == lookupGen.get()) {
                        batchModel.set(List.of());
                        txnModel.set(List.of());
                        status.setText("DB lookup failed: " + e.getMessage());
                    }
                });
            }
        });
    }

    private void loadTransactions() {
        int row = batchTable.getSelectedRow();
        int gen = txnGen.incrementAndGet();
        if (row < 0) {
            txnModel.set(List.of());
            txnTitle.setText(" ");
            return;
        }
        SettlementDb.OpenBatch b = batchModel.batch(row);
        txnTitle.setText("Batch " + b.batchNo() + ": loading transactions ...");
        SettlementDb db = db();
        exec.execute(() -> {
            try {
                List<SettlementDb.Txn> txns = db.transactions(b.id(), TXN_LIMIT);
                SwingUtilities.invokeLater(() -> {
                    if (gen != txnGen.get()) {
                        return;
                    }
                    txnModel.set(txns);
                    txnTitle.setText("Batch " + b.batchNo() + ": " + (b.txnCount() > txns.size()
                            ? "latest " + txns.size() + " of " + b.txnCount() + " transactions"
                            : b.txnCount() + " transaction(s)") + "  |  " + summary(b));
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    if (gen == txnGen.get()) {
                        txnModel.set(List.of());
                        txnTitle.setText("Batch " + b.batchNo() + ": could not load transactions: " + e.getMessage());
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ request

    private void withDraft(Consumer<ExchangeConsole.Draft> action) {
        ExchangeConsole.Draft d = draft(true);
        if (d != null) {
            action.accept(d);
        }
    }

    /** Keeps the editor in step with the ticked batches, so Send never goes out with an empty payload. */
    private void autoDraft() {
        ExchangeConsole.Draft d = draft(false);
        if (d != null) {
            console.loadDraft(d);
        } else if (batchModel.ticked().isEmpty()) {
            // nothing to settle: don't leave a request for already-settled batches ready to resend
            console.loadDraft(new ExchangeConsole.Draft("POST", url.getText().trim(), headers(), ""));
        }
    }

    /** @param interactive explain with a dialog why there is no request (otherwise just return null) */
    private ExchangeConsole.Draft draft(boolean interactive) {
        List<SettlementDb.OpenBatch> ticked = batchModel.ticked();
        if (ticked.isEmpty()) {
            if (interactive) {
                JOptionPane.showMessageDialog(this, "Tick at least one open batch to settle.", "Nothing to settle",
                        JOptionPane.INFORMATION_MESSAGE);
            }
            return null;
        }
        int method;
        try {
            method = Integer.parseInt(String.valueOf(settledMethod.getEditor().getItem()).trim());
        } catch (NumberFormatException e) {
            if (interactive) {
                JOptionPane.showMessageDialog(this, "settledMethod must be a number", "Invalid value",
                        JOptionPane.WARNING_MESSAGE);
            }
            return null;
        }
        Map<String, Object> body = settleBody(CoreSaleClient.hex64(random), serial.getText().trim(), tid.getText().trim(),
                mid.getText().trim(), method, LocalDateTime.now(), ticked);
        return new ExchangeConsole.Draft("POST", url.getText().trim(), headers(), Json.pretty(body));
    }

    static Map<String, Object> settleBody(String originId, String serialNo, String terminalId, String merchantId,
                                          int settledMethod, LocalDateTime settledAt,
                                          List<SettlementDb.OpenBatch> batches) {
        List<Map<String, Object>> acquirers = new ArrayList<>();
        for (SettlementDb.OpenBatch b : batches) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("terminalId", terminalId);
            a.put("merchantId", merchantId);
            a.put("settledMethod", settledMethod);
            a.put("batchNo", b.batchNo());
            a.put("batchKey", b.batchKey());
            a.put("settledDate", settledAt.format(CORE_DATETIME));
            put(a, "sale", b.total("sale"));
            put(a, "saleVoid", b.total("void-sale"));
            put(a, "offline", b.total("offline"));
            put(a, "offlineVoid", b.total("void-offline"));
            put(a, "precomp", b.total("precomp"));
            acquirers.add(a);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("originId", originId);
        body.put("deviceSerialNo", serialNo);
        body.put("settleAcquirers", acquirers);
        return body;
    }

    private static void put(Map<String, Object> a, String prefix, SettlementDb.Total t) {
        a.put(prefix + "Count", t.count());
        a.put(prefix + "Amount", plain(t.amount()));
    }

    /** 1500 rather than 1.5E+3; a whole amount goes out without decimals. */
    private static Number plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() <= 0 ? (Number) s.toBigInteger() : s;
    }

    /** batchKeys of the request as actually sent (it may have been edited). */
    @SuppressWarnings("unchecked")
    private static Set<String> settledKeys(String requestBody) {
        Set<String> keys = new HashSet<>();
        Map<String, Object> b = Json.object(requestBody);
        if (b != null && b.get("settleAcquirers") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("batchKey") != null) {
                    keys.add(String.valueOf(m.get("batchKey")));
                }
            }
        }
        return keys;
    }

    // ------------------------------------------------------------------ helpers

    private SettlementDb db() {
        return new SettlementDb(jdbcUrl(), dbUser.getText().trim(), new String(dbPassword.getPassword()));
    }

    private String jdbcUrl() {
        return "jdbc:oracle:thin:@" + dbHost.getText().trim() + ":" + dbPort.getText().trim() + "/" + dbSid.getText().trim();
    }

    private static String summary(SettlementDb.OpenBatch b) {
        StringBuilder sb = new StringBuilder();
        b.byType().forEach((type, t) -> {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(type).append(' ').append(t.count()).append(" / ").append(plain(t.amount()));
        });
        return sb.isEmpty() ? "no transactions" : sb.toString();
    }

    private static String describe(List<SettlementDb.OpenBatch> batches) {
        StringBuilder sb = new StringBuilder("ID, BATCHNO, BATCHKEY, CREATEDAT, TXNS: per type count / amount\n");
        for (SettlementDb.OpenBatch b : batches) {
            sb.append(b.id()).append(", ").append(b.batchNo()).append(", ").append(b.batchKey()).append(", ")
                    .append(b.createdAt()).append(", ").append(b.txnCount()).append(": ").append(summary(b)).append('\n');
        }
        return sb.toString();
    }

    private static Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json");
        return h;
    }

    private static String shown(java.sql.Timestamp ts) {
        return ts == null ? "" : ts.toLocalDateTime().format(SHOWN);
    }

    // ------------------------------------------------------------------ tables

    private static final class BatchModel extends AbstractTableModel {
        private static final String[] COLS = {"Settle", "Batch", "Batch key", "Txns", "Created"};
        private List<SettlementDb.OpenBatch> rows = List.of();
        private final Set<Long> ticked = new HashSet<>();

        void set(List<SettlementDb.OpenBatch> batches) {
            rows = batches;
            ticked.clear();
            if (!batches.isEmpty()) {
                ticked.add(batches.get(0).id());
            }
            fireTableDataChanged();
        }

        SettlementDb.OpenBatch batch(int row) {
            return rows.get(row);
        }

        List<SettlementDb.OpenBatch> ticked() {
            return rows.stream().filter(b -> ticked.contains(b.id())).toList();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : c == 1 || c == 3 ? Long.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return c == 0; }

        @Override
        public Object getValueAt(int r, int c) {
            SettlementDb.OpenBatch b = rows.get(r);
            return switch (c) {
                case 0 -> ticked.contains(b.id());
                case 1 -> b.batchNo();
                case 2 -> b.batchKey();
                case 3 -> b.txnCount();
                default -> shown(b.createdAt());
            };
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            if (c == 0) {
                long id = rows.get(r).id();
                if (Boolean.TRUE.equals(v)) {
                    ticked.add(id);
                } else {
                    ticked.remove(id);
                }
                fireTableCellUpdated(r, c);
            }
        }
    }

    private static final class TxnModel extends AbstractTableModel {
        private static final String[] COLS = {"Time", "Type", "Amount", "Trace", "Invoice", "RRN", "Voided"};
        private List<SettlementDb.Txn> rows = List.of();

        void set(List<SettlementDb.Txn> txns) {
            rows = txns;
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }

        @Override
        public Object getValueAt(int r, int c) {
            SettlementDb.Txn t = rows.get(r);
            return switch (c) {
                case 0 -> shown(t.dateTime());
                case 1 -> t.transType();
                case 2 -> t.amount() == null ? "" : plain(t.amount()).toString();
                case 3 -> t.traceNo();
                case 4 -> t.invoiceNo();
                case 5 -> t.rrn();
                default -> "1".equals(t.isVoided()) ? "yes" : "";
            };
        }
    }
}
