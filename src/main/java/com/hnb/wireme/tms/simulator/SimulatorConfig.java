package com.hnb.wireme.tms.simulator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Loads config.properties; any key can be overridden with -Dkey=value. */
final class SimulatorConfig {

    private final Properties props = new Properties();

    SimulatorConfig(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        }
    }

    private SimulatorConfig() {
    }

    /** The file's settings, or only -D overrides and defaults when it is missing or unreadable (desktop UI). */
    static SimulatorConfig loadOrEmpty(Path file) {
        try {
            return Files.exists(file) ? new SimulatorConfig(file) : new SimulatorConfig();
        } catch (IOException e) {
            return new SimulatorConfig();
        }
    }

    String get(String key, String defaultValue) {
        String v = System.getProperty(key, props.getProperty(key));
        return v == null || v.isBlank() ? defaultValue : v.trim();
    }

    String require(String key) {
        String v = get(key, null);
        if (v == null) {
            throw new IllegalStateException("Missing required config: " + key);
        }
        return v;
    }

    int getInt(String key, int defaultValue) {
        return Integer.parseInt(get(key, String.valueOf(defaultValue)));
    }

    boolean getBool(String key, boolean defaultValue) {
        return Boolean.parseBoolean(get(key, String.valueOf(defaultValue)));
    }

    double getDouble(String key, double defaultValue) {
        return Double.parseDouble(get(key, String.valueOf(defaultValue)));
    }
}
