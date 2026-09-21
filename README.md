# TMS POS MQTT Simulator

Java simulator of a POS terminal for the TMS push channel (`PushDeviceMqttListener`).

## Run

```bash
mvn package
java -jar target/pos-simulator.jar                 # uses ./config.properties
java -jar target/pos-simulator.jar other.properties
java -Dterminal.serial-number=SN0002 -jar target/pos-simulator.jar   # override one key
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

## Notes

- `merchantId`/`terminalId` are sent as `mid`/`tid` in the DIN confirm (what the server stores) and are also added to
  the register/echo payloads; the server currently ignores them there.
- `topic.prefix` must equal the TMS `tms.push.mqtt.topic-prefix` (default `tms/push`); `broker.url` defaults to
  `tcp://localhost:1883`, matching the TMS default.
