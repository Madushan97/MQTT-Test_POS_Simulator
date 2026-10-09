package com.hnb.wireme.tms.simulator;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A transaction from a terminal to WireMe core ({@code POST core.sale-url}), with the variable parts
 * (type, amount, card label, payment/entry mode, approved/declined) set from the form.
 *
 * Unverified against a core API spec (none was available), so the payload stays editable before Send:
 *  - transType values are the ones the TMS code names (sale, preauth, void-sale, void-installment,
 *    precomp, offline); the field takes any text.
 *  - "Declined" adds respCode — FAILEDTRANSACTIONS has a RESPCODE column, but whether core files a
 *    declined request there is not confirmed.
 *  - For void/precomp the ori* fields are named after the TRANSACTIONS ORI* columns.
 */
final class SaleTab extends JPanel {

    /** A sale accepted earlier in this session, offered as the original for a void/precomp. */
    private record Original(String transType, Object traceNo, Object batchNo, Object dateTime, Object rrn,
                            Object authCode, Object amount, Object invoiceNo) {
        @Override
        public String toString() {
            return transType + " " + amount + "  trace " + traceNo + "  rrn " + rrn + "  @" + dateTime;
        }
    }

    private static final String NONE = "(none)";

    private final ExchangeLog log = new ExchangeLog(300);
    private final ExchangeConsole console = new ExchangeConsole(log, "POST", "PUT", "GET");
    private final SecureRandom random = new SecureRandom();

    private final JTextField url = new JTextField();
    private final JTextField serial = new JTextField();
    private final JTextField tid = new JTextField();
    private final JTextField mid = new JTextField();
    private final JTextField lat = new JTextField("6.9271");
    private final JTextField lng = new JTextField("79.8612");
    private final JComboBox<String> transType = editable("sale", "void-sale", "preauth", "precomp", "void-installment", "offline");
    private final JTextField amount = new JTextField("1500");
    private final JTextField tip = new JTextField("0");
    private final JTextField currency = new JTextField();
    private final JComboBox<String> cardLabel = editable("master", "visa", "amex", "unionpay", "jcb", "diners", "discover");
    private final JComboBox<String> paymentMode = editable("card", "qr", "cash");
    private final JComboBox<String> entryMode = editable("tap", "chip", "swipe", "manual", "fallback");
    private final JTextField pan = new JTextField("532016XXXXXX1166");
    private final JTextField expDate = new JTextField("2701");
    private final JComboBox<String> outcome = new JComboBox<>(new String[]{"Approved", "Declined"});
    private final JTextField respCode = new JTextField("05");
    private final DefaultComboBoxModel<Object> originals = new DefaultComboBoxModel<>(new Object[]{NONE});
    private final JComboBox<Object> original = new JComboBox<>(originals);

    SaleTab(SimulatorConfig singleCfg, SimulatorConfig fleetCfg, Supplier<MqttTerminal.Identity> singleIdentity) {
        super(new BorderLayout());
        url.setText(fleetCfg.get("core.sale-url", "http://localhost:28071/api/core/v1/transactions"));
        currency.setText(fleetCfg.get("core.currency", "LKR"));
        serial.setText(singleCfg.get("terminal.serial-number", ""));
        tid.setText(singleCfg.get("terminal.terminal-id", ""));
        mid.setText(singleCfg.get("terminal.merchant-id", ""));
        respCode.setEnabled(false);
        outcome.addActionListener(e -> respCode.setEnabled("Declined".equals(outcome.getSelectedItem())));
        original.addActionListener(e -> {
            if (original.getSelectedItem() instanceof Original o) {
                amount.setText(String.valueOf(o.amount()));
            }
        });

        JButton useSingle = new JButton("Use Single-terminal device");
        useSingle.addActionListener(e -> {
            MqttTerminal.Identity id = singleIdentity.get();
            serial.setText(id.serial());
            tid.setText(id.terminalId());
            mid.setText(id.merchantId());
        });
        JButton randomAmount = new JButton("Random");
        randomAmount.addActionListener(e -> amount.setText(String.valueOf(100 + random.nextInt(250_000))));
        JPanel amountRow = new JPanel(new BorderLayout(4, 0));
        amountRow.add(amount, BorderLayout.CENTER);
        amountRow.add(randomAmount, BorderLayout.EAST);

        JButton build = new JButton("Build request");
        build.setToolTipText("Fill the editor (fresh ids each time) without sending");
        build.addActionListener(e -> withDraft(console::loadDraft));
        JButton buildSend = new JButton("Build & send ▸");
        buildSend.addActionListener(e -> withDraft(console::sendDraft));
        JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
        buttons.add(build);
        buttons.add(buildSend);

        FormPanel form = new FormPanel()
                .section("Endpoint")
                .row("Core URL", url)
                .section("Terminal")
                .full(useSingle)
                .row("Device serial", serial)
                .row("Terminal ID", tid)
                .row("Merchant ID", mid)
                .row("Lat", lat)
                .row("Lng", lng)
                .section("Transaction")
                .row("Type (transType)", transType)
                .row("Amount", amountRow)
                .row("Tip amount", tip)
                .row("Currency", currency)
                .row("Card label", cardLabel)
                .row("Payment mode", paymentMode)
                .row("Entry mode", entryMode)
                .row("PAN (masked)", pan)
                .row("Exp date (YYMM)", expDate)
                .row("Outcome", outcome)
                .row("respCode if declined", respCode)
                .row("Original (void/precomp)", original)
                .full(buttons)
                .note("No core API spec was available: transType values come from the TMS code, 'Declined' adds "
                        + "<b>respCode</b>, and void/precomp add <b>ori*</b> fields named after the TRANSACTIONS ORI* "
                        + "columns. Check the request before sending, and edit it if core expects something else. "
                        + "Accepted sales show up under 'Original'.")
                .end();

        console.loadDraft(new ExchangeConsole.Draft("POST", url.getText(), headers(), ""));
        console.setSender(d -> {
            Exchange ex = HttpExchanger.send(log, d.method(), d.url(), d.headers(), d.body());
            if (ex.ok()) {
                rememberOriginal(d.body());
            }
            return ex;
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(form), console);
        split.setDividerLocation(440);
        add(split, BorderLayout.CENTER);
    }

    private void withDraft(java.util.function.Consumer<ExchangeConsole.Draft> action) {
        try {
            action.accept(new ExchangeConsole.Draft("POST", url.getText().trim(), headers(), Json.pretty(body())));
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Amount, tip, lat and lng must be numbers: " + e.getMessage(),
                    "Invalid value", JOptionPane.WARNING_MESSAGE);
        }
    }

    private Map<String, Object> body() {
        String type = text(transType);
        CoreSaleClient.SaleSpec spec = new CoreSaleClient.SaleSpec(type, Long.parseLong(amount.getText().trim()),
                Long.parseLong(tip.getText().trim()), currency.getText().trim(), text(cardLabel), text(paymentMode),
                text(entryMode), pan.getText().trim(), expDate.getText().trim());
        Map<String, Object> body = CoreSaleClient.saleBody(serial.getText().trim(), tid.getText().trim(),
                mid.getText().trim(), Double.parseDouble(lat.getText().trim()), Double.parseDouble(lng.getText().trim()),
                spec, random);
        if ("Declined".equals(outcome.getSelectedItem())) {
            body.put("respCode", respCode.getText().trim());
        }
        if (original.getSelectedItem() instanceof Original o && (type.startsWith("void") || type.equals("precomp"))) {
            body.put("oriTransType", o.transType());
            body.put("oriTraceNo", o.traceNo());
            body.put("oriBatchNo", o.batchNo());
            body.put("oriDateTime", o.dateTime());
            body.put("oriRrn", o.rrn());
            body.put("oriAuthCode", o.authCode());
        }
        return body;
    }

    /** Taken from the request as actually sent, so hand-edited payloads are remembered correctly too. */
    private void rememberOriginal(String requestBody) {
        Map<String, Object> b = Json.object(requestBody);
        if (b == null) {
            return;
        }
        String type = String.valueOf(b.get("transType"));
        if (type.startsWith("void")) {
            return;
        }
        Original o = new Original(type, num(b.get("traceNo")), num(b.get("batchNo")), b.get("dateTime"), b.get("rrn"),
                b.get("authCode"), num(b.get("amount")), num(b.get("invoiceNo")));
        SwingUtilities.invokeLater(() -> originals.insertElementAt(o, 1));
    }

    /** Gson reads every JSON number as a double; show whole numbers without ".0". */
    private static Object num(Object v) {
        return v instanceof Double d && d == Math.rint(d) ? (Object) d.longValue() : v;
    }

    private static Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json");
        return h;
    }

    private static JComboBox<String> editable(String... items) {
        JComboBox<String> c = new JComboBox<>(items);
        c.setEditable(true);
        return c;
    }

    private static String text(JComboBox<String> c) {
        Object v = c.isEditable() ? c.getEditor().getItem() : c.getSelectedItem();
        return v == null ? "" : v.toString().trim();
    }
}
