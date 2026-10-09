package com.hnb.wireme.tms.simulator;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to the TMS hardware-failure REST API on behalf of a simulated terminal:
 *
 *   GET  {base}/hardware-failure-error-codes?category=HARDWARE_FAILURES   the catalogue
 *        (ADVANCED_INSIGHTS_FAILURE_ERROR_CODES) — failures are only ever sent with a code from here,
 *        never an invented one
 *   POST {base}/hardware-failure-transactions                             report one failure
 *   GET  {base}/hardware-failure-transactions?deviceSerial=...            read them back
 *
 * where base = tms.base-url + "/advanced-insights/tms". This is plain HTTP, not MQTT: the TMS exposes
 * hardware failures as a REST endpoint only — there is no push-device topic for them.
 */
final class HardwareFailureClient {

    record ErrorCode(String code, String description, String subCategory, String stage) {
    }

    record Result(boolean ok, int httpStatus, String requestJson, String body, String error) {
    }

    private final Gson gson = new Gson();
    private final String base;

    HardwareFailureClient(String tmsBaseUrl) {
        this.base = stripSlash(tmsBaseUrl) + "/advanced-insights/tms";
    }

    String base() {
        return base;
    }

    /** The hardware codes from ADVANCED_INSIGHTS_FAILURE_ERROR_CODES via the TMS list endpoint. */
    List<ErrorCode> fetchErrorCodes(String category) throws IOException {
        String url = base + "/hardware-failure-error-codes"
                + (category == null || category.isBlank() ? "" : "?category=" + enc(category));
        Result r = send(HttpRequest.newBuilder(URI.create(url)).GET(), null);
        if (!r.ok()) {
            throw new IOException("GET " + url + " -> " + describe(r));
        }
        return parseCodes(r.body());
    }

    /** The JSON array the error-code list endpoint returns, reduced to the fields a sender needs. */
    static List<ErrorCode> parseCodes(String json) {
        List<ErrorCode> codes = new ArrayList<>();
        for (JsonElement e : JsonParser.parseString(json).getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            codes.add(new ErrorCode(str(o, "code"), str(o, "description"), str(o, "subCategory"), str(o, "stage")));
        }
        return codes;
    }

    /** The POST body; terminalId / merchantId are optional on the server and included only when known. */
    static Map<String, Object> reportBody(String deviceSerial, String errorCode, String terminalId, String merchantId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deviceSerial", deviceSerial);
        body.put("errorCode", errorCode);
        if (terminalId != null && !terminalId.isBlank()) body.put("terminalId", terminalId);
        if (merchantId != null && !merchantId.isBlank()) body.put("merchantId", merchantId);
        return body;
    }

    /** POST one failure. */
    Result report(String deviceSerial, String errorCode, String terminalId, String merchantId) {
        String json = gson.toJson(reportBody(deviceSerial, errorCode, terminalId, merchantId));
        return send(HttpRequest.newBuilder(URI.create(base + "/hardware-failure-transactions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)), json);
    }

    /** Newest-first page of stored failures for one device, with the server's per-code breakdown. */
    Result list(String deviceSerial, int size) {
        String url = base + "/hardware-failure-transactions?size=" + size
                + (deviceSerial == null || deviceSerial.isBlank() ? "" : "&deviceSerial=" + enc(deviceSerial));
        return send(HttpRequest.newBuilder(URI.create(url)).GET(), null);
    }

    /** One-line summary of a list() response: totals, per-code counts, latest rows. */
    String summarizeList(Result r) {
        if (!r.ok()) {
            return describe(r);
        }
        JsonObject page = JsonParser.parseString(r.body()).getAsJsonObject();
        StringBuilder sb = new StringBuilder("total=").append(page.get("totalElements").getAsLong());
        JsonArray counts = page.getAsJsonArray("errorCodeCounts");
        if (counts != null && !counts.isEmpty()) {
            sb.append(" counts=[");
            for (int i = 0; i < counts.size(); i++) {
                JsonObject c = counts.get(i).getAsJsonObject();
                sb.append(i == 0 ? "" : ", ").append(str(c, "errorCode")).append('=').append(c.get("count").getAsLong())
                        .append(" (").append(c.get("percentage").getAsDouble()).append("%)");
            }
            sb.append(']');
        }
        JsonArray items = page.getAsJsonArray("items");
        for (JsonElement e : items) {
            JsonObject o = e.getAsJsonObject();
            sb.append(System.lineSeparator()).append("    #").append(o.get("id").getAsLong())
                    .append(' ').append(str(o, "errorCode"))
                    .append(" serial=").append(str(o, "deviceSerial"))
                    .append(" tid=").append(str(o, "terminalId"))
                    .append(" mid=").append(str(o, "merchantId"))
                    .append(" at=").append(str(o, "createdAt"));
        }
        return sb.toString();
    }

    static String describe(Result r) {
        return r.error() != null ? "ERROR " + r.error() : "HTTP " + r.httpStatus() + " " + truncate(r.body());
    }

    private Result send(HttpRequest.Builder builder, String json) {
        HttpRequest request = builder.timeout(Duration.ofSeconds(10)).build();
        try {
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            return new Result(response.statusCode() / 100 == 2, response.statusCode(), json, response.body(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, -1, json, null, String.valueOf(e));
        } catch (IOException e) {
            return new Result(false, -1, json, null, String.valueOf(e));
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
