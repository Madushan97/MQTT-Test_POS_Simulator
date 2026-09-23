package com.hnb.wireme.tms.simulator;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a random sample of real, active devices straight from the same
 * Oracle DB the TMS uses — DEVICES joined to TERMINALS and MERCHANTS, mirroring
 * DeviceRepository#findAllDirectoryRows (the query behind the TMS's own
 * GET .../push/devices) but pulling DEVICES.EMINO too, which that endpoint
 * doesn't expose. Real serialNo+IMEI pairs mean MQTT register actually
 * succeeds (status 000) instead of always coming back 101.
 *
 * Also carries each device's internal DEVICES.ID — needed by
 * DeviceStatusSeeder to bootstrap a DEVICE_STATUS row for a device that
 * doesn't have one yet, working around PushMessageManagementServiceImpl
 * .markOnline()'s currently-broken first-time INSERT (ORA-01400, see that
 * class's doc). This query itself doesn't filter on DEVICE_STATUS anymore —
 * FleetSimulator seeds whatever's missing instead of narrowing the pool.
 *
 * Deduplicated to exactly one row per device (ROW_NUMBER()/rn=1 below): a
 * device can have more than one active TERMINALS row (reassigned terminals,
 * old rows never cleaned up), and without this the plain join returns the
 * same DEVICES.SERIALNO twice with two different terminalIds — two
 * FleetDevice instances with the identical MQTT client id
 * ("fleet-sim-<serial>"), which then perpetually kick each other off the
 * broker ("session taken over" / repeated connectionLost).
 *
 * Read-only: one SELECT, no writes, no TMS backend involved.
 */
final class DeviceDbClient {

    record DbDevice(long deviceId, String serialNo, String emiNo, String terminalId, String merchantId,
                     Double lat, Double lng) {
    }

    private static final String SQL = """
            SELECT DEVICE_ID, SERIALNO, EMINO, TERMINALID, MERCHANTID, LAT, LNG FROM (
                SELECT d.ID AS DEVICE_ID, d.SERIALNO, d.EMINO, t.TERMINALID, m.MERCHANTID, m.LAT, m.LNG,
                       ROW_NUMBER() OVER (PARTITION BY d.ID ORDER BY t.ID) AS RN
                FROM DEVICES d
                JOIN TERMINALS t ON t.DEVICEID = d.ID AND (t.DELETEDREC IS NULL OR t.DELETEDREC = 0)
                JOIN MERCHANTS m ON m.ID = t.MERCHANTID AND (m.DELETEDREC IS NULL OR m.DELETEDREC = 0)
                WHERE d.ACTIVE = '1'
                  AND (d.DELETEDREC IS NULL OR d.DELETEDREC = 0)
                  AND d.SERIALNO IS NOT NULL AND d.EMINO IS NOT NULL
                  AND t.TERMINALID IS NOT NULL AND m.MERCHANTID IS NOT NULL
            ) WHERE RN = 1
            ORDER BY DBMS_RANDOM.VALUE
            FETCH FIRST ? ROWS ONLY
            """;

    List<DbDevice> fetchRandomActiveDevices(String url, String username, String password, int limit) throws SQLException {
        List<DbDevice> devices = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, username, password);
             PreparedStatement ps = conn.prepareStatement(SQL)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // getObject() can come back as Float depending on the column's Oracle type
                    // (BINARY_FLOAT/NUMBER precision) — getDouble()+wasNull() avoids the mismatch.
                    double latVal = rs.getDouble("LAT");
                    Double lat = rs.wasNull() ? null : latVal;
                    double lngVal = rs.getDouble("LNG");
                    Double lng = rs.wasNull() ? null : lngVal;
                    devices.add(new DbDevice(rs.getLong("DEVICE_ID"), rs.getString("SERIALNO"), rs.getString("EMINO"),
                            rs.getString("TERMINALID"), rs.getString("MERCHANTID"), lat, lng));
                }
            }
        }
        return devices;
    }
}
