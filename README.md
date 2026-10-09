# TMS POS MQTT Simulator

Java simulator of a POS terminal for the TMS push channel (`PushDeviceMqttListener`).
The tools here:

- **`SimulatorApp`**: desktop UI and the jar's default entry point (see [Desktop UI](#desktop-ui)).
- **`PosSimulator`**: one terminal, console only, for debugging a single device (below).
- **`FleetSimulator`**: ~50-100 real devices at once, with churn, for exercising Fleet
  Overview / Dormancy / Merchant Feedback together (see [Fleet Simulator](#fleet-simulator)).

## Desktop UI

```bash
mvn package
java -jar target/pos-simulator.jar        # defaults from ./config.properties + ./fleet-config.properties
java -jar target/pos-simulator.jar my-config.properties my-fleet-config.properties
```

There is one tab per feature. Each tab has a form on the left, which fills the request editor, and
the same request/response view on the right:

- **Request to send**: method, URL (or MQTT topic), headers (`Key: Value` per line) and payload,
  all editable. Whatever is in the editor when you press **Send** is exactly what goes out.
- **History**: newest first. Green means OK, red means failed, amber means still waiting. For the
  selected entry it shows the request as sent and the response as received: status, time, headers
  and body. **Copy to compose** loads an old request back into the editor so you can resend it.

| Tab | What it does |
|---|---|
| Single terminal | MQTT connect / register / ECHO / confirm for one device. Each publish's "response" is the TMS's reply on the matching topic (`create/ack`, `message/response`, `update/confirm/ack`). If no reply arrives within 15 s, the publish is marked `NO REPLY`. Pushed commands appear as `RECEIVE` entries and are auto-confirmed (SUCCESS/FAIL selectable). |
| Bulk fleet | Many devices at once, either random active devices from the DB (same query as `FleetSimulator`) or a pasted `serial,imei,tid,mid` list. One row per device; select a row to see that device's history. **Show fleet log** shows the DB statements (SELECT, and the optional DEVICE_STATUS seed INSERT) with their results. |
| Sale | `POST core.sale-url` with editable type, amount, card label, payment and entry mode, PAN/expiry, and approved/declined. Accepted sales are offered as the **Original** for a void/precomp. |
| Hardware failures | Load the code catalogue (GET), report failures (POST: one, or N random codes), and list stored failures (GET). Only serial + code are sent unless you tick the tid/mid box. |
| Push notifications | The whole push round trip. **1.** Queue an operation (`MSG`, `DIN`, `LOG`, `BNR`, `SET`, `ACTV`, `INT`) with `POST push/push-operations/create`, or `/create/bulk` by terminal/device/merchant/MCC/partner ids. **2.** **Poll now** sends an ECHO; the TMS delivers the oldest PEND operation in the reply. Delivered operations are listed, and with **Confirm manually** ticked you can answer each one with SUCCESS (→ COMP) or FAIL (→ FAIL). **3.** List the device's operations and the jobs to check the status. The device side uses the Single terminal tab's connection. |

Push notes, from the TMS code:
- **Delivery is by ECHO reply only.** `PushMessageManagementServiceImpl.sendMessageToSpecificDevice` would publish on `/commands`, but nothing calls it.
- **A confirm marks the device's oldest PEND operation**, whatever `operationCode` the confirm carries.
- **The device must be registered first.** A device that never registered over MQTT gets the operation stored as `NOT_REGISTERED`, and it is never delivered.
- **Complete / Ignore buttons:** under the response panel. They act as the POS: ECHO to pull the oldest PEND operation, confirm it `SUCCESS` (→ `COMP`) or `FAIL` (→ `FAIL`), then re-read the device's operations. If the Single terminal tab isn't connected as that device, the buttons open their own connection for it.
- **TMS bugs this tab found, both fixed in the TMS repo (need a TMS restart):**
  - `create` returned HTTP 500 "Query did not return a unique result" for a device with several TERMINALS rows (`TerminalRepository` lookups now skip deleted rows and take the newest).
  - Operations queued for a device whose `PUSH_DEVICE.PUSH_ID` is not its serial (e.g. rows left by the old push service) stayed `PEND` forever: the queue used DEVICES.ID, but delivery and confirm used `PUSH_ID`. All three now go through `PushDeviceResolver`.

**Sale-tab assumptions, unverified because no WireMe core API spec was available.** Check the
payload before sending:
- `transType` choices are the values the TMS code names (`sale`, `void-sale`, `preauth`,
  `precomp`, `void-installment`, `offline`). The field also accepts any other text.
- **Declined** adds `respCode`. `FAILEDTRANSACTIONS` has a `RESPCODE` column, but it is not
  confirmed that core files a request with one there.
- Void/precomp add `oriTransType`, `oriTraceNo`, `oriBatchNo`, `oriDateTime`, `oriRrn` and
  `oriAuthCode`, named after the `TRANSACTIONS.ORI*` columns.

The sale endpoint in `fleet-config.properties` is the shared UAT core, so a sent sale creates a
real UAT transaction.

## Broker

Both tools need an MQTT broker. If you don't already have one, this folder has a Mosquitto
container:

```bash
docker compose up -d      # starts tms-mosquitto on localhost:1883
docker compose down       # stop it
```

Point your local TMS's `tms.push.mqtt.broker-url` (`PUSH_MQTT_BROKER_URL`) at the same broker —
`tcp://localhost:1883` is the default on both sides.

> **Windows gotcha:** after the machine sleeps/hibernates, Windows sometimes reserves TCP port
> 1883 for something else (`netsh interface ipv4 show excludedportrange protocol=tcp` will show
> it inside a reserved range), and Docker fails to publish the broker on that port. A reboot
> clears it; `net stop winnat && net start winnat` from an elevated PowerShell usually does too,
> without a reboot.

## Run (single terminal, console)

```bash
mvn package
java -cp target/pos-simulator.jar com.hnb.wireme.tms.simulator.PosSimulator                    # uses ./config.properties
java -cp target/pos-simulator.jar com.hnb.wireme.tms.simulator.PosSimulator other.properties
java -Dterminal.serial-number=SN0002 -cp target/pos-simulator.jar com.hnb.wireme.tms.simulator.PosSimulator   # override one key
```

Edit `config.properties` to change serial number, IMEI, merchantId, terminalId, broker, topic prefix, intervals.
Run several copies with different serials (and `log.dir`) to simulate multiple terminals.

## What it does

1. Connects to the broker (retries until up, auto-reconnects on drop).
2. Subscribes to `{prefix}/push-devices/{sn}/{create/ack, message/response, commands, update/confirm/ack}`.
3. Registers on `{prefix}/push-devices/create` (server replies `000`, or `101` if serial+IMEI is not an active device).
4. Sends an `ECHO` on `.../message` every `echo.interval.seconds`. The server's reply is the pong; RTT is logged.
   This is also what marks the terminal online and records its heartbeat in the TMS.
5. If a pending operation arrives (on `/commands` or in an ECHO reply) it "executes" it and confirms on
   `.../update/confirm` (`DIN` sends device info incl. `tid`/`mid`). Set `command.confirm-status=FAIL` to test failure.

## Logs

Console plus `logs/terminal-<serial>.log`. Categories: `CONN` `SUB` `TX` `RX` `PING` `PONG` `CMD` `STATUS`.
A `STATUS` line every `status.interval.seconds` shows liveness:

- `HEALTHY` - broker connected and TMS is answering echoes
- `STALE` - broker connected but the TMS isn't replying (TMS down / not subscribed / topic prefix mismatch)
- `DISCONNECTED` - no broker connection

MQTT-level keep-alive (PINGREQ/PINGRESP every `keepalive.seconds`) is done by the client library; a missed one
shows up as `CONNECTION LOST`.

Console commands while running: `status`, `echo`, `register`, `din`, `disconnect`, `connect`, `quit`.

## Hardware failures

The TMS takes hardware failures over **REST, not MQTT**:
`POST {tms.base-url}/advanced-insights/tms/hardware-failure-transactions`. The simulator only sends
codes it got from `GET .../hardware-failure-error-codes?category=HARDWARE_FAILURES` (the
`ADVANCED_INSIGHTS_FAILURE_ERROR_CODES` table). It never makes codes up. If that list comes back
empty, it sends nothing. By default the body is just `deviceSerial` (`terminal.serial-number`) and
`errorCode`, like a real POS request, so the server stores `TERMINAL_ID` / `MERCHANT_ID` as null.
Set `hwfail.send-tid-mid=true` to also send `terminal-id` / `merchant-id`.

Console commands in `PosSimulator` (these work while the broker is down too):

- `codes`: reload and print the catalogue
- `fail`: one random code · `fail 5`: five random codes · `fail E0201`: that exact code (a
  code that isn't in the catalogue is still sent, with a warning, to test how the server handles it)
- `fails`: `GET .../hardware-failure-transactions?deviceSerial=<serial>`. Prints the total, the
  per-code `errorCodeCounts` and the 10 newest rows

Set `hwfail.auto-interval.seconds=N` to report one random failure every N seconds.

One-shot run with no broker (load codes → POST N → GET to read them back; exits non-zero on failure):

```bash
java -cp target/pos-simulator.jar com.hnb.wireme.tms.simulator.HardwareFailureSender config.properties 5
```

## Notes

- `merchantId`/`terminalId` are sent as `mid`/`tid` in the DIN confirm (what the server stores) and are also added to
  the register/echo payloads; the server currently ignores them there.
- `topic.prefix` must equal the TMS `tms.push.mqtt.topic-prefix` (default `tms/push`); `broker.url` defaults to
  `tcp://localhost:1883`, matching the TMS default.

## Fleet Simulator

`FleetSimulator` pulls a random pool of real, active devices straight from the DB (DEVICES joined to
TERMINALS/MERCHANTS — see `DeviceDbClient`, read-only, one SELECT, no TMS backend involved), brings
them all online at once, then periodically churns some offline and some back online — so you can
watch Fleet Overview, Dormancy Analysis and Merchant Feedback move as a fleet drifts, instead of one
terminal at a time.

```bash
mvn package
java -cp target/pos-simulator.jar com.hnb.wireme.tms.simulator.FleetSimulator
java -cp target/pos-simulator.jar com.hnb.wireme.tms.simulator.FleetSimulator other-fleet.properties
```

Edit `fleet-config.properties` — DB connection, fleet size (50-100), broker, intervals, churn
fractions, WireMe core sale URL. Console commands while running: `status`, `churn` (force an
out-of-cycle churn pass), `quit`. Log: `logs/fleet.log` (one shared file, not one per device).

### Two independent signals — don't confuse them

This took a code-read of the TMS to get right, and it's the single most important thing to
understand before you read the results:

1. **MQTT register/ECHO** (this tool's `broker.*` config) proves the *push channel* itself works —
   you'll see `[MQTT-IN]`/`[MQTT-OUT]` lines in the TMS log for every simulated device. **It does
   NOT move Fleet Overview's online/offline/active/inactive counts.**
2. **The periodic "sale" call to WireMe core** (`core.*` config,
   `POST /api/core/v1/transactions`) is what *does*. `FleetOverviewRollupJob`'s own code comment
   says ONLINE/OFFLINE/ACTIVE/INACTIVE are derived purely from `DEVICES.LASTACTIVE`, and that
   column is written by "the upstream WireMe core system" — nothing in the TMS's MQTT push path
   touches it. Dormancy Analysis and Merchant Feedback are separate again: they read real
   transaction rows (`TransactionRepository`), which only a posted sale creates.

So each simulated device keeps its own MQTT session open and echoing on its own schedule (proving
push works). Sales work differently: once every `sale.interval.seconds`, a random `sale.batch-size`
subset of the currently-online fleet sells — a different subset each round, not the whole fleet at
once — closer to how a real merchant base actually transacts, and it's what moves `LASTACTIVE`
(the thing the dashboards actually read). Churning a device pauses **both** signals for it, to look
like a terminal that's genuinely gone dark.

### What churn does and doesn't get you quickly

- **Online → Offline** (`tms.fleet-overview.online-threshold-minutes` / `intermittent-threshold-minutes`,
  default 5 / 30 min): shows up in Fleet Overview within a few rollup cycles
  (`FLEET_ROLLUP_CRON`, default every 5 minutes) after a device is churned offline — no DB changes
  needed, just wait.
- **Active → Inactive**: driven by the same `LASTACTIVE`, but against `TASK_CONFIG`'s
  `INACTIVE_ALERT` period, which defaults to **24 hours**. A churned device won't flip to Inactive
  within a short test session unless you temporarily lower that `TASK_CONFIG` row's `PERIOD` for
  the test (a data change, not a code change) — otherwise it's a genuinely multi-hour wait, same as
  in production.

### A live push bug this test surfaced — worked around, not fixed

Brand-new-to-push devices currently crash the ECHO reply: `PushMessageManagementServiceImpl
.markOnline()` INSERTs a new `DEVICE_STATUS` row the first time a device echoes, which needs a
`STATUS` table row with `STATUS_CODE='ONLINE'` — missing in this environment (`ORA-01400`), and the
`try/catch` around it doesn't actually protect the rest of the transaction (Hibernate poisons the
whole session after that failure), so the ECHO never gets a reply at all. An already-deployed
terminal never hits this — it only ever UPDATEs its existing `DEVICE_STATUS` row, which doesn't
touch the broken lookup.

Since this is a live project, the fix stays entirely in this simulator: `DeviceStatusSeeder`
bootstraps a `DEVICE_STATUS` row for each selected device that doesn't already have one, before
onboarding it (called from `FleetSimulator.run()`, right after the device pool is picked). It
reuses whatever `STATUS_CODE` already exists in the `STATUS` table — it deliberately doesn't need
`'ONLINE'` to exist — so the very next real ECHO for that device takes the safe UPDATE path in
`markOnline()`, exactly like an already-deployed terminal does. The `INSERT` is guarded by
`WHERE NOT EXISTS`, so it's a no-op on devices that already have a row, and safe to run every time
the simulator starts.

This IS a write to a live, shared table (`DEVICE_STATUS`) — done because you explicitly asked for
this over fixing the push side directly. It only ever adds a row for a device that had none; it
never touches an existing row. The underlying bug (missing `STATUS_CODE='ONLINE'`/`'OFFLINE'` rows,
and `markOnline`'s exception handling not actually protecting the transaction) is still there for
any genuinely first-time device outside this simulator — fixing that for real is a TMS backend
change, deliberately not made here.

### Known gaps (by design, not oversight)

- Device identity (serialNo/IMEI/terminalId/merchantId) comes from a real DB row, so MQTT `create`
  (register) should succeed (`status=000`) for every simulated device — no more guessed/blank IMEI.
- Device lat/lng use the merchant's own `LAT`/`LNG` when present; otherwise a small random jitter
  around Colombo, generated once at startup.
- `db.*` and `core.sale-url` credentials/URLs in `fleet-config.properties` are the same shared
  dev/UAT values already checked into the TMS's own `docker-compose.yml` — override them if you're
  pointed at a different environment.
- `core.sale-url` has no auth per your instructions; if UAT starts requiring one later, add it to
  `CoreSaleClient`'s request builder.
- `originId`'s exact format (64 hex chars) is inferred from the one sample request you gave me —
  adjust `CoreSaleClient.hex64()` if WireMe core validates it more strictly than that.
