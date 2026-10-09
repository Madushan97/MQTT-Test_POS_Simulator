package com.hnb.wireme.tms.simulator;

import javax.swing.JFrame;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Desktop UI for the POS simulator. One tab per feature; each shows every request (URL or MQTT
 * topic, headers, payload) and the response it got.
 *
 *   Single terminal    one terminal on the MQTT push channel
 *   Bulk fleet         many terminals at once (from the DB or a pasted list)
 *   Sale               transactions to WireMe core (sale, void, preauth, ...)
 *   Hardware failures  TMS hardware-failure REST endpoints
 *   Push notifications queue a push operation (REST), receive and confirm it (MQTT), check its status
 *
 * Defaults come from config.properties and fleet-config.properties in the working directory (or the
 * two paths given as arguments).
 *
 *   java -jar target/pos-simulator.jar [config.properties] [fleet-config.properties]
 */
public final class SimulatorApp {

    public static void main(String[] args) {
        SimulatorConfig single = SimulatorConfig.loadOrEmpty(Path.of(args.length > 0 ? args[0] : "config.properties"));
        SimulatorConfig fleet = SimulatorConfig.loadOrEmpty(Path.of(args.length > 1 ? args[1] : "fleet-config.properties"));
        ScheduledExecutorService exec = Executors.newScheduledThreadPool(24, r -> {
            Thread t = new Thread(r, "sim-worker");
            t.setDaemon(true);
            return t;
        });

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // default look and feel is fine
            }
            SingleTerminalTab singleTab = new SingleTerminalTab(single, exec);
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Single terminal", singleTab);
            tabs.addTab("Bulk fleet", new BulkFleetTab(fleet, exec));
            tabs.addTab("Sale", new SaleTab(single, fleet, singleTab::identity));
            tabs.addTab("Hardware failures", new HardwareFailureTab(single, exec, singleTab::identity));
            tabs.addTab("Push notifications", new PushNotificationTab(single, exec, singleTab));

            JFrame frame = new JFrame("TMS POS Simulator");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(tabs);
            frame.setSize(1400, 880);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
