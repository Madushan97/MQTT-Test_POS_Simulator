package com.hnb.wireme.tms.simulator;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Bootstraps a DEVICE_STATUS row for a device that doesn't have one yet, so
 * it takes the safe UPDATE path in PushMessageManagementServiceImpl.markOnline()
 * on its first real ECHO instead of the currently-broken INSERT path (see
 * DeviceDbClient's class doc for the full ORA-01400 story).
 *
 * Deliberately does NOT reference the 'ONLINE'/'OFFLINE' status codes that are
 * actually missing — it reuses whatever STATUS_CODE already exists in STATUS
 * (any row satisfies the DEVICE_STATUS.DEVICE_STATUS NOT NULL FK), so this
 * works without needing the missing rows fixed and without guessing at a
 * value the live push code doesn't already use somewhere.
 *
 * A write to a shared/live table — used only because the user explicitly
 * asked for this over fixing the push side directly (see conversation).
 * Idempotent: the INSERT is guarded by "WHERE NOT EXISTS", so re-running this
 * against an already-seeded device is a no-op.
 */
final class DeviceStatusSeeder {

    private static final String ANY_STATUS_CODE_SQL = "SELECT STATUS_CODE FROM STATUS FETCH FIRST 1 ROWS ONLY";

    private static final String SEED_SQL = """
            INSERT INTO DEVICE_STATUS (DEVICE_ID, DEVICE_STATUS, LAST_UPDATED_TIME, CREATEDAT, UPDATEDAT)
            SELECT ?, ?, ?, ?, ? FROM DUAL
            WHERE NOT EXISTS (SELECT 1 FROM DEVICE_STATUS WHERE DEVICE_ID = ?)
            """;

    /**
     * Seeds a DEVICE_STATUS row for every device in {@code deviceIds} that doesn't already have
     * one. Returns the number of rows actually inserted. Does nothing (returns 0, logs a warning)
     * if the STATUS table itself has no rows to borrow a code from.
     */
    int seedMissing(String url, String username, String password, List<Long> deviceIds, TerminalLog log) throws SQLException {
        if (deviceIds.isEmpty()) {
            return 0;
        }
        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            Optional<String> anyStatusCode = fetchAnyStatusCode(conn);
            if (anyStatusCode.isEmpty()) {
                log.warn("SEED", "STATUS table has no rows at all — cannot bootstrap DEVICE_STATUS, "
                        + "falling back to only devices that already have one");
                return 0;
            }
            String statusCode = anyStatusCode.get();
            int seeded = 0;
            try (PreparedStatement ps = conn.prepareStatement(SEED_SQL)) {
                for (Long deviceId : deviceIds) {
                    Timestamp now = Timestamp.valueOf(LocalDateTime.now());
                    ps.setLong(1, deviceId);
                    ps.setString(2, statusCode);
                    ps.setTimestamp(3, now);
                    ps.setTimestamp(4, now);
                    ps.setTimestamp(5, now);
                    ps.setLong(6, deviceId);
                    seeded += ps.executeUpdate();
                }
            }
            log.info("SEED", "bootstrapped DEVICE_STATUS for " + seeded + "/" + deviceIds.size()
                    + " device(s) using STATUS_CODE='" + statusCode + "'");
            return seeded;
        }
    }

    private Optional<String> fetchAnyStatusCode(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(ANY_STATUS_CODE_SQL);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
        }
    }
}
