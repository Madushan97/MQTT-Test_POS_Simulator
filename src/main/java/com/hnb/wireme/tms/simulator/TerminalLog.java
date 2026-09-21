package com.hnb.wireme.tms.simulator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Terminal log: every line goes to the console and to logs/terminal-&lt;serial&gt;.log. */
final class TerminalLog {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Path file;

    TerminalLog(Path dir, String serial) throws IOException {
        Files.createDirectories(dir);
        this.file = dir.resolve("terminal-" + serial + ".log");
    }

    Path file() {
        return file;
    }

    void info(String category, String msg) {
        write("INFO ", category, msg);
    }

    void warn(String category, String msg) {
        write("WARN ", category, msg);
    }

    void error(String category, String msg) {
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
