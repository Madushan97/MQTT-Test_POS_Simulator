package com.hnb.wireme.tms.simulator.settlement;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only lookups behind the Settlement tab, against the same Oracle DB core writes to.
 *
 * Core keeps one BATCHES row per terminal + batchKey (ISSETTLED = '0' while open), and every
 * transaction points at its batch through TRANSACTIONS.BATCHID. Both tables store TERMINALID and
 * MERCHANTID as internal ids, so the TID / MID typed in the form are matched through TERMINALS and
 * MERCHANTS.
 */
final class SettlementDb {

    /** An open batch of the terminal, with its transactions totalled by type. */
    record OpenBatch(long id, long batchNo, String batchKey, String currency, Timestamp createdAt, String tid,
                     String mid, Map<String, Total> byType) {

        long txnCount() {
            return byType.values().stream().mapToLong(Total::count).sum();
        }

        Total total(String transType) {
            return byType.getOrDefault(transType, Total.ZERO);
        }
    }

    record Total(long count, BigDecimal amount) {
        static final Total ZERO = new Total(0, BigDecimal.ZERO);
    }

    record Txn(long id, String transType, BigDecimal amount, String traceNo, String invoiceNo, String rrn,
               Timestamp dateTime, String isVoided) {
    }

    /** Newest open batches for the TID (and MID, when given). Binds: :1 TID, :2 MID, :3 MID. */
    static final String BATCHES_SQL = """
            SELECT b.ID, b.BATCHNO, b.BATCHKEY, b.CURRENCYCODE, b.CREATEDAT, t.TERMINALID AS TID, m.MERCHANTID AS MID
            FROM BATCHES b
            JOIN TERMINALS t ON t.ID = b.TERMINALID
            LEFT JOIN MERCHANTS m ON m.ID = b.MERCHANTID
            WHERE t.TERMINALID = ?
              AND (? IS NULL OR m.MERCHANTID = ?)
              AND b.ISSETTLED = '0'
            ORDER BY b.CREATEDAT DESC
            FETCH FIRST 50 ROWS ONLY
            """;

    /** Per-batch, per-type counts and amounts for the batches above (same binds). */
    static final String TOTALS_SQL = """
            SELECT x.BATCHID, LOWER(x.TRANSTYPE) AS TRANSTYPE, COUNT(*) AS CNT, SUM(x.AMOUNT) AS AMT
            FROM TRANSACTIONS x
            WHERE x.BATCHID IN (
                SELECT b.ID FROM BATCHES b
                JOIN TERMINALS t ON t.ID = b.TERMINALID
                LEFT JOIN MERCHANTS m ON m.ID = b.MERCHANTID
                WHERE t.TERMINALID = ? AND (? IS NULL OR m.MERCHANTID = ?) AND b.ISSETTLED = '0'
            )
            GROUP BY x.BATCHID, LOWER(x.TRANSTYPE)
            """;

    /** Latest transactions of one batch. Binds: :1 BATCHID, :2 row limit. */
    static final String TXNS_SQL = """
            SELECT ID, TRANSTYPE, AMOUNT, TRACENO, INVOICENO, RRN, DATETIME, ISVOIDED
            FROM TRANSACTIONS
            WHERE BATCHID = ?
            ORDER BY DATETIME DESC, ID DESC
            FETCH FIRST ? ROWS ONLY
            """;

    private final String url;
    private final String user;
    private final String password;

    SettlementDb(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    List<OpenBatch> openBatches(String tid, String mid) throws SQLException {
        String midOrNull = mid == null || mid.isBlank() ? null : mid.trim();
        try (Connection c = connect()) {
            Map<Long, Map<String, Total>> totals = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(TOTALS_SQL)) {
                bindTerminal(ps, tid, midOrNull);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        BigDecimal amt = rs.getBigDecimal("AMT");
                        totals.computeIfAbsent(rs.getLong("BATCHID"), k -> new LinkedHashMap<>())
                                .put(String.valueOf(rs.getString("TRANSTYPE")).toLowerCase(Locale.ROOT),
                                        new Total(rs.getLong("CNT"), amt == null ? BigDecimal.ZERO : amt));
                    }
                }
            }
            List<OpenBatch> batches = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(BATCHES_SQL)) {
                bindTerminal(ps, tid, midOrNull);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong("ID");
                        batches.add(new OpenBatch(id, rs.getLong("BATCHNO"), rs.getString("BATCHKEY"),
                                rs.getString("CURRENCYCODE"), rs.getTimestamp("CREATEDAT"), rs.getString("TID"),
                                rs.getString("MID"), totals.getOrDefault(id, Map.of())));
                    }
                }
            }
            return batches;
        }
    }

    List<Txn> transactions(long batchId, int limit) throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(TXNS_SQL)) {
            ps.setLong(1, batchId);
            ps.setInt(2, limit);
            List<Txn> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new Txn(rs.getLong("ID"), rs.getString("TRANSTYPE"), rs.getBigDecimal("AMOUNT"),
                            rs.getString("TRACENO"), rs.getString("INVOICENO"), rs.getString("RRN"),
                            rs.getTimestamp("DATETIME"), rs.getString("ISVOIDED")));
                }
            }
            return list;
        }
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(url, user, password);
        c.setReadOnly(true);
        return c;
    }

    private static void bindTerminal(PreparedStatement ps, String tid, String midOrNull) throws SQLException {
        ps.setString(1, tid.trim());
        ps.setString(2, midOrNull);
        ps.setString(3, midOrNull);
    }
}
