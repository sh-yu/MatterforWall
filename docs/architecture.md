# Architecture

```text
RS485 bus -> ESP UART RX/framer -> BLE packet stream -> Android capture/analyzer
                 |                       |
          verified decoder          profile approval
                 |                       |
                 +-> safe state <- signed execution profile
                          |
                    Matter endpoint
```

The ESP32 does bounded framing, transport, diagnostics, minimum state decoding,
and safety enforcement. Android performs expensive grouping, temporal analysis,
learning-session comparison, import/export, and operator approval.

The core interfaces are transport-neutral. Host tests use mocks; platform code
adapts ESP-IDF UART/NVS/OTA, NimBLE, esp-matter, Android BLE, Room, and DataStore.

## Safety state machine

`PASSIVE_SNIFFER` is the reset state. `ACTIVE_CONTROL` is reachable only when a
profile is verified, an administrator session is authenticated, explicit TX is
enabled, the bus is healthy, and OTA is idle. Any failed condition immediately
returns to passive receive. Gas-open and unverified elevator operations are
unconditionally denied.

