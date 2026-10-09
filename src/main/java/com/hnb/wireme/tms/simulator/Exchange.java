package com.hnb.wireme.tms.simulator;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One request and its response, as shown in the desktop UI: an HTTP call, an MQTT publish (the
 * "response" being the TMS's reply on the matching ack/response topic), an unsolicited MQTT message
 * (response only), or a DB statement. Created pending and completed exactly once.
 */
final class Exchange {

    enum Kind { HTTP, MQTT, DB }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    final Kind kind;
    final String method;
    final String url;
    final Map<String, String> requestHeaders;
    final String requestBody;
    final LocalDateTime at = LocalDateTime.now();
    private final long startNanos = System.nanoTime();

    private volatile boolean done;
    private volatile boolean ok;
    private volatile String status = "pending...";
    private volatile Map<String, String> responseHeaders = Map.of();
    private volatile String responseBody = "";
    private volatile long durationMs = -1;
    private volatile Runnable onChange = () -> { };

    Exchange(Kind kind, String method, String url, Map<String, String> requestHeaders, String requestBody) {
        this.kind = kind;
        this.method = method;
        this.url = url;
        this.requestHeaders = requestHeaders == null ? Map.of() : new LinkedHashMap<>(requestHeaders);
        this.requestBody = requestBody == null ? "" : requestBody;
    }

    /** Something that arrived without being asked for (e.g. a pushed MQTT command): response side only. */
    static Exchange inbound(Kind kind, String url, boolean ok, String status, Map<String, String> headers, String body) {
        Exchange e = new Exchange(kind, "RECEIVE", url, Map.of(), "");
        e.complete(ok, status, headers, body);
        e.durationMs = -1;
        return e;
    }

    /** First call wins; later ones (e.g. a reply racing its timeout) are ignored. */
    synchronized boolean complete(boolean ok, String status, Map<String, String> headers, String body) {
        if (done) {
            return false;
        }
        this.ok = ok;
        this.status = status;
        this.responseHeaders = headers == null ? Map.of() : new LinkedHashMap<>(headers);
        this.responseBody = body == null ? "" : body;
        this.durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        this.done = true;
        onChange.run();
        return true;
    }

    /** Interim status while still pending (e.g. "published, waiting for reply"). */
    synchronized void progress(String status) {
        if (!done) {
            this.status = status;
            onChange.run();
        }
    }

    void onChange(Runnable r) {
        this.onChange = r;
    }

    boolean done() {
        return done;
    }

    boolean ok() {
        return ok;
    }

    String status() {
        return status;
    }

    Map<String, String> responseHeaders() {
        return responseHeaders;
    }

    String responseBody() {
        return responseBody;
    }

    long durationMs() {
        return durationMs;
    }

    String time() {
        return TIME.format(at);
    }

    /** The one-line form used in the history list. */
    String summary() {
        String mark = !done ? "…" : ok ? "✔" : "✖";
        return time() + "  " + mark + "  " + method + "  " + shortUrl() + "   " + status
                + (durationMs >= 0 && !"RECEIVE".equals(method) ? "  (" + durationMs + " ms)" : "");
    }

    private String shortUrl() {
        if (kind == Kind.HTTP) {
            int scheme = url.indexOf("://");
            int path = scheme < 0 ? -1 : url.indexOf('/', scheme + 3);
            String p = path < 0 ? url : url.substring(path);
            int tms = p.indexOf("/advanced-insights/tms/");
            return tms >= 0 ? "…" + p.substring(tms + "/advanced-insights/tms".length()) : p;
        }
        if (kind == Kind.MQTT) {
            int idx = url.indexOf("/push-devices/");
            return idx >= 0 ? "…" + url.substring(idx + "/push-devices".length()) : url;
        }
        return url;
    }

    @Override
    public String toString() {
        return summary();
    }
}
