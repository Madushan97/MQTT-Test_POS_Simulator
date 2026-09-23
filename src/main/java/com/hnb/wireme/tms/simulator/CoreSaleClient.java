package com.hnb.wireme.tms.simulator;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Posts a synthetic "sale" transaction to the WireMe core UAT endpoint —
 * {@code POST /api/core/v1/transactions}. This is the real reason
 * DEVICES.LASTACTIVE moves: FleetOverviewRollupJob's own comment says that
 * column is written by "the upstream WireMe core system", not by anything in
 * the TMS's MQTT push path. So bringing a simulated terminal "online" for
 * Fleet Overview / Dormancy / Merchant Feedback purposes means hitting this
 * core endpoint, not the MQTT broker (that's PosSimulator/FleetDevice's job,
 * a separate, complementary check that the push channel itself works).
 *
 * Field layout and sample values (pan/expDate/nii/authCode/etc.) are taken
 * from a real UAT request the user supplied; originId's exact format (64 hex
 * chars here) is inferred from that sample and not documented anywhere, so if
 * core validates it more strictly this will need adjusting.
 */
final class CoreSaleClient {

    private static final DateTimeFormatter CORE_DATETIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final Gson gson = new Gson();
    private final SecureRandom random = new SecureRandom();

    private final String url;
    private final long amountMin;
    private final long amountMax;
    private final String currency;

    CoreSaleClient(String url, long amountMin, long amountMax, String currency) {
        this.url = url;
        this.amountMin = amountMin;
        this.amountMax = amountMax;
        this.currency = currency;
    }

    record SaleResult(boolean ok, int httpStatus, String requestJson, String body, String error) {
    }

    SaleResult sendSale(String serialNo, String terminalId, String merchantId, double lat, double lng) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("originId", hex64());
        body.put("uniqueId", UUID.randomUUID().toString());
        body.put("paymentMode", "card");
        body.put("transType", "sale");
        body.put("deviceSerialNo", serialNo);
        body.put("terminalId", terminalId);
        body.put("merchantId", merchantId);
        body.put("amount", amountMin + random.nextLong(Math.max(1, amountMax - amountMin + 1)));
        body.put("tipAmount", 0);
        body.put("currency", currency);
        body.put("pan", "532016XXXXXX1166"); // synthetic masked test PAN — not a real card
        body.put("expDate", "2701");
        body.put("nii", "004");
        body.put("entryMode", "tap");
        body.put("isDccTransaction", false);
        body.put("cardLabel", "master");
        body.put("traceNo", 1 + random.nextInt(9000));
        body.put("invoiceNo", 1 + random.nextInt(9000));
        int batchNo = 1 + random.nextInt(99);
        body.put("batchNo", batchNo);
        LocalDateTime now = LocalDateTime.now();
        String coreDateTime = now.format(CORE_DATETIME);
        body.put("batchKey", batchNo + "_" + LocalDate.now() + "-" + coreDateTime);
        body.put("dateTime", coreDateTime);
        body.put("rrn", String.valueOf(100000000000L + random.nextLong(900000000L)));
        body.put("authCode", "AB" + (1000 + random.nextInt(9000)));
        body.put("signData", null);
        body.put("custMobile", null);
        body.put("email", null);
        body.put("contactNo", null);
        body.put("lat", lat);
        body.put("lng", lng);

        String json = gson.toJson(body);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                // A fresh HttpClient (and so a fresh connection) per call: cheap at this call rate,
                // and it sidesteps "header parser received no bytes" from reusing a pooled keep-alive
                // connection the UAT server had already closed on its end.
                HttpResponse<String> response = HttpClient.newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.ofString());
                boolean ok = response.statusCode() / 100 == 2;
                return new SaleResult(ok, response.statusCode(), json, truncate(response.body()), null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new SaleResult(false, -1, json, null, String.valueOf(e));
            } catch (IOException e) {
                if (attempt == 2) {
                    return new SaleResult(false, -1, json, null, String.valueOf(e));
                }
                // one retry — a stale-connection reset is transient, not worth counting as a real failure
            }
        }
        return new SaleResult(false, -1, json, null, "unreachable");
    }

    private String truncate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    private String hex64() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
