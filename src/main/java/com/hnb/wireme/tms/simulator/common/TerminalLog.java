package com.hnb.wireme.tms.simulator.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Plain-text log: every line goes to the console and to a file. */
public final class TerminalLog {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Path file;

    /** One log per simulated terminal: logs/terminal-&lt;serial&gt;.log (used by PosSimulator). */
    public TerminalLog(Path dir, String serial) throws IOException {
        this(dir.resolve("terminal-" + serial + ".log"));
    }

    private TerminalLog(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        this.file = file;
    }

    /** One shared log for a whole fleet run, e.g. logs/fleet.log (used by FleetSimulator). */
    public static TerminalLog forFileName(Path dir, String fileName) throws IOException {
        return new TerminalLog(dir.resolve(fileName));
    }

    public Path file() {
        return file;
    }

    public void info(String category, String msg) {
        write("INFO ", category, msg);
    }

    public void warn(String category, String msg) {
        write("WARN ", category, msg);
    }

    public void error(String category, String msg) {
        write("ERROR", category, msg);
    }

    private synchronized void write(String level, String category, String msg) {
        String line = LocalDateTime.now().format(TS) + " " + level + " [" + String.format("%-6s", category) + "] " + msg;
        System.out.println(line);
        try {
            Files.createDirectories(file.getParent()); // survives the log dir being deleted while running
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Cannot write log file " + file + ": " + e.getMessage());
        }
    }
}
