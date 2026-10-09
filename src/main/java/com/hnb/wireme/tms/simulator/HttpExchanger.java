package com.hnb.wireme.tms.simulator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Sends one HTTP request exactly as composed in the UI and records it, with the full response, as an Exchange. */
final class HttpExchanger {

    /** Headers java.net.http sets itself and refuses from callers. */
    private static final Set<String> RESTRICTED = Set.of("connection", "content-length", "expect", "host", "upgrade");

    private HttpExchanger() {
    }

    static Exchange send(ExchangeLog log, String method, String url, Map<String, String> headers, String body) {
        Exchange ex = log.add(new Exchange(Exchange.Kind.HTTP, method.toUpperCase(), url, headers, body));
        HttpRequest request;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url.trim())).timeout(Duration.ofSeconds(15));
            for (Map.Entry<String, String> h : headers.entrySet()) {
                if (!RESTRICTED.contains(h.getKey().toLowerCase())) {
                    b.header(h.getKey(), h.getValue());
                }
            }
            boolean hasBody = body != null && !body.isBlank() && !"GET".equalsIgnoreCase(method);
            b.method(method.toUpperCase(), hasBody ? HttpRequest.BodyPublishers.ofString(body)
                    : HttpRequest.BodyPublishers.noBody());
            request = b.build();
        } catch (RuntimeException e) {
            ex.complete(false, "INVALID REQUEST", Map.of(), String.valueOf(e));
            return ex;
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                // Fresh client per call, as CoreSaleClient does: avoids reusing a keep-alive
                // connection the server already closed.
                HttpResponse<String> r = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
                Map<String, String> rh = new LinkedHashMap<>();
                for (Map.Entry<String, List<String>> h : r.headers().map().entrySet()) {
                    if (!h.getKey().startsWith(":")) {
                        rh.put(h.getKey(), String.join(", ", h.getValue()));
                    }
                }
                ex.complete(r.statusCode() / 100 == 2, "HTTP " + r.statusCode(), rh, Json.pretty(r.body()));
                return ex;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ex.complete(false, "INTERRUPTED", Map.of(), String.valueOf(e));
                return ex;
            } catch (IOException e) {
                if (attempt == 2) {
                    ex.complete(false, "NO RESPONSE", Map.of(), e + "\n\nIs the server at " + url + " running?");
                    return ex;
                }
                ex.progress("retrying after " + e.getClass().getSimpleName());
            }
        }
        return ex;
    }

    /** "Key: Value" lines → ordered map (blank and malformed lines skipped). */
    static Map<String, String> parseHeaders(String text) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            int idx = line.indexOf(':');
            if (idx > 0) {
                headers.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }
        }
        return headers;
    }

    static String formatHeaders(Map<String, String> headers) {
        StringBuilder sb = new StringBuilder();
        headers.forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
        return sb.toString();
    }
}
