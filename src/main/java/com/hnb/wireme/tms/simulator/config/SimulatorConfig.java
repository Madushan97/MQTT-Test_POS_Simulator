package com.hnb.wireme.tms.simulator.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Loads config.properties; any key can be overridden with -Dkey=value. */
public final class SimulatorConfig {

    private final Properties props = new Properties();

    public SimulatorConfig(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        }
    }

    private SimulatorConfig() {
    }

    /** The file's settings, or only -D overrides and defaults when it is missing or unreadable (desktop UI). */
    public static SimulatorConfig loadOrEmpty(Path file) {
        try {
            return Files.exists(file) ? new SimulatorConfig(file) : new SimulatorConfig();
        } catch (IOException e) {
            return new SimulatorConfig();
        }
    }

    public String get(String key, String defaultValue) {
        String v = System.getProperty(key, props.getProperty(key));
        return v == null || v.isBlank() ? defaultValue : v.trim();
    }

    public String require(String key) {
        String v = get(key, null);
        if (v == null) {
            throw new IllegalStateException("Missing required config: " + key);
        }
        return v;
    }

    public int getInt(String key, int defaultValue) {
        return Integer.parseInt(get(key, String.valueOf(defaultValue)));
    }

    public boolean getBool(String key, boolean defaultValue) {
        return Boolean.parseBoolean(get(key, String.valueOf(defaultValue)));
    }

    public double getDouble(String key, double defaultValue) {
        return Double.parseDouble(get(key, String.valueOf(defaultValue)));
    }
}
