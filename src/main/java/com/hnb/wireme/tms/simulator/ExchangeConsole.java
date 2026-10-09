package com.hnb.wireme.tms.simulator;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The request/response view every tab shares.
 *
 * Top: a compose editor — method, URL (or MQTT topic), headers ("Key: Value" per line) and payload,
 * all editable — and Send. Tabs fill it from their forms; whatever is in it when Send is pressed is
 * exactly what goes out.
 *
 * Bottom: the history (newest first) and, for the selected entry, the request as sent and the
 * response as received: status, headers and body.
 */
final class ExchangeConsole extends JPanel {

    record Draft(String method, String url, Map<String, String> headers, String body) {
    }

    /** Sends a draft and returns the recorded exchange; runs off the UI thread. */
    interface Sender {
        Exchange send(Draft draft) throws Exception;
    }

    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    private static final Color OK = new Color(0, 128, 0);
    private static final Color FAIL = new Color(190, 0, 0);
    private static final Color PENDING = new Color(160, 110, 0);

    private final JComboBox<String> methodBox = new JComboBox<>();
    private final JTextField urlField = new JTextField();
    private final JTextArea composeHeaders = area(true);
    private final JTextArea composeBody = area(true);
    private final JButton sendButton = new JButton("Send");

    private final DefaultListModel<Exchange> model = new DefaultListModel<>();
    private final JList<Exchange> list = new JList<>(model);
    private final JCheckBox follow = new JCheckBox("Follow latest", true);

    private final JLabel reqLine = new JLabel(" ");
    private final JTextArea reqHeaders = area(false);
    private final JTextArea reqBody = area(false);
    private final JPanel responseActions = new JPanel(new GridLayout(1, 0, 4, 0));
    private final JLabel respLine = new JLabel(" ");
    private final JTextArea respHeaders = area(false);
    private final JTextArea respBody = area(false);

    private final ExecutorService sendExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "console-send");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private final Runnable logListener = this::queueRefresh;

    private ExchangeLog log;
    private Sender sender;

    ExchangeConsole(ExchangeLog log, String... methods) {
        super(new BorderLayout());
        for (String m : methods) {
            methodBox.addItem(m);
        }
        methodBox.setEditable(true);
        methodBox.setPreferredSize(new Dimension(110, methodBox.getPreferredSize().height));
        urlField.setFont(MONO);
        reqLine.setFont(MONO);
        respLine.setFont(MONO.deriveFont(Font.BOLD));

        JSplitPane main = new JSplitPane(JSplitPane.VERTICAL_SPLIT, buildCompose(), buildHistory());
        main.setResizeWeight(0.35);
        main.setDividerLocation(260);
        add(main, BorderLayout.CENTER);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFont(MONO);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean sel, boolean focus) {
                super.getListCellRendererComponent(l, value, index, sel, focus);
                if (!sel && value instanceof Exchange e) {
                    setForeground(!e.done() ? PENDING : e.ok() ? OK : FAIL);
                }
                return this;
            }
        });
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                show(list.getSelectedValue());
            }
        });
        sendButton.addActionListener(e -> sendCompose());
        setLog(log);
    }

    private JComponent buildCompose() {
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.add(methodBox, BorderLayout.WEST);
        top.add(urlField, BorderLayout.CENTER);
        sendButton.setFont(sendButton.getFont().deriveFont(Font.BOLD));
        top.add(sendButton, BorderLayout.EAST);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                titled("Headers", new JScrollPane(composeHeaders)), titled("Payload", new JScrollPane(composeBody)));
        split.setResizeWeight(0.3);

        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createTitledBorder("Request to send  (edit anything, then Send)"));
        p.add(top, BorderLayout.NORTH);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    private JComponent buildHistory() {
        JButton copy = new JButton("Copy to compose");
        copy.setToolTipText("Load the selected request into the editor above to edit and resend it");
        copy.addActionListener(e -> {
            Exchange x = list.getSelectedValue();
            if (x != null && !"RECEIVE".equals(x.method)) {
                loadDraft(new Draft(x.method, x.url, x.requestHeaders, x.requestBody));
            }
        });
        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> log.clear());

        JPanel listBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        listBar.add(follow);
        listBar.add(copy);
        listBar.add(clear);
        JPanel listPanel = new JPanel(new BorderLayout());
        listPanel.setBorder(BorderFactory.createTitledBorder("History (newest first)"));
        listPanel.add(new JScrollPane(list), BorderLayout.CENTER);
        listPanel.add(listBar, BorderLayout.SOUTH);

        JPanel respSide = new JPanel(new BorderLayout(0, 4));
        respSide.add(side("Response received", respLine, respHeaders, respBody, "Body"), BorderLayout.CENTER);
        responseActions.setVisible(false);
        respSide.add(responseActions, BorderLayout.SOUTH);

        JPanel details = new JPanel(new GridLayout(1, 2, 6, 0));
        details.add(side("Request sent", reqLine, reqHeaders, reqBody, "Payload"));
        details.add(respSide);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listPanel, details);
        split.setResizeWeight(0.35);
        split.setDividerLocation(430);
        return split;
    }

    private static JComponent side(String title, JLabel line, JTextArea headers, JTextArea body, String bodyTitle) {
        JScrollPane hs = new JScrollPane(headers);
        hs.setPreferredSize(new Dimension(100, 95));
        JPanel north = new JPanel(new BorderLayout(0, 2));
        north.add(line, BorderLayout.NORTH);
        north.add(titled("Headers", hs), BorderLayout.CENTER);
        JPanel p = new JPanel(new BorderLayout(0, 2));
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.add(north, BorderLayout.NORTH);
        p.add(titled(bodyTitle, new JScrollPane(body)), BorderLayout.CENTER);
        return p;
    }

    // ------------------------------------------------------------------ API for tabs

    void setSender(Sender sender) {
        this.sender = sender;
    }

    /** A button under the response panel; the action gets the selected history entry (possibly null). */
    void addResponseAction(String label, String tooltip, java.util.function.Consumer<Exchange> action) {
        JButton b = new JButton(label);
        b.setToolTipText(tooltip);
        b.addActionListener(e -> action.accept(list.getSelectedValue()));
        responseActions.add(b);
        responseActions.setVisible(true);
    }

    /** Switch which history is shown (the bulk tab shows one device at a time). */
    void setLog(ExchangeLog newLog) {
        if (log != null) {
            log.removeListener(logListener);
        }
        log = newLog;
        log.addListener(logListener);
        model.clear();
        refresh();
    }

    void loadDraft(Draft d) {
        methodBox.setSelectedItem(d.method());
        urlField.setText(d.url());
        composeHeaders.setText(HttpExchanger.formatHeaders(d.headers()));
        composeBody.setText(d.body());
        composeBody.setCaretPosition(0);
    }

    /** Load into the editor and send straight away. */
    void sendDraft(Draft d) {
        loadDraft(d);
        sendCompose();
    }

    private void sendCompose() {
        if (sender == null) {
            return;
        }
        Draft d = new Draft(String.valueOf(methodBox.getSelectedItem()).trim(), urlField.getText().trim(),
                HttpExchanger.parseHeaders(composeHeaders.getText()), composeBody.getText());
        if (d.url().isEmpty()) {
            JOptionPane.showMessageDialog(this, "URL / topic is empty.", "Nothing to send", JOptionPane.WARNING_MESSAGE);
            return;
        }
        sendButton.setEnabled(false);
        sendExec.submit(() -> {
            try {
                Exchange ex = sender.send(d);
                SwingUtilities.invokeLater(() -> select(ex));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, String.valueOf(e.getMessage()),
                        "Cannot send", JOptionPane.ERROR_MESSAGE));
            } finally {
                SwingUtilities.invokeLater(() -> sendButton.setEnabled(true));
            }
        });
    }

    // ------------------------------------------------------------------ rendering

    private void queueRefresh() {
        if (refreshQueued.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(() -> {
                refreshQueued.set(false);
                refresh();
            });
        }
    }

    private void refresh() {
        Exchange selected = list.getSelectedValue();
        List<Exchange> items = log.snapshot();
        Exchange newestBefore = model.isEmpty() ? null : model.get(0);
        model.clear();
        model.addAll(items);
        if (follow.isSelected() && !items.isEmpty() && items.get(0) != newestBefore) {
            list.setSelectedIndex(0);
        } else if (selected != null && items.contains(selected)) {
            list.setSelectedValue(selected, false);
            show(selected); // it may have just completed
        } else if (selected != null) {
            show(null);
        }
    }

    private void select(Exchange ex) {
        if (model.contains(ex)) {
            list.setSelectedValue(ex, true);
        }
    }

    private void show(Exchange e) {
        if (e == null) {
            reqLine.setText(" ");
            respLine.setText(" ");
            for (JTextArea a : new JTextArea[]{reqHeaders, reqBody, respHeaders, respBody}) {
                a.setText("");
            }
            return;
        }
        reqLine.setText(e.kind + "  " + e.method + "  " + e.url + "    @ " + e.time());
        reqHeaders.setText(HttpExchanger.formatHeaders(e.requestHeaders));
        reqBody.setText(Json.pretty(e.requestBody));
        respLine.setText(e.status() + (e.done() && e.durationMs() >= 0 ? "   (" + e.durationMs() + " ms)" : ""));
        respLine.setForeground(!e.done() ? PENDING : e.ok() ? OK : FAIL);
        respHeaders.setText(HttpExchanger.formatHeaders(e.responseHeaders()));
        respBody.setText(e.responseBody());
        for (JTextArea a : new JTextArea[]{reqHeaders, reqBody, respHeaders, respBody}) {
            a.setCaretPosition(0);
        }
    }

    private static JTextArea area(boolean editable) {
        JTextArea a = new JTextArea();
        a.setFont(MONO);
        a.setEditable(editable);
        a.setTabSize(2);
        return a;
    }

    private static JComponent titled(String title, JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        JLabel l = new JLabel(title);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 11f));
        p.add(l, BorderLayout.NORTH);
        p.add(c, BorderLayout.CENTER);
        return p;
    }
}
