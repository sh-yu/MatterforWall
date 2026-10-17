# KOCOM RS485 → Matter over Thread Bridge — Codex Handoff v0.4

## Goal
Build a standalone KOCOM wallpad RS485 bridge using Seeed Studio XIAO ESP32-C6 + XIAO RS485 Expansion Board. Expose validated devices to Samsung SmartThings using Matter over Thread. Prioritize safe passive discovery and an elevator *call* action. The wallpad model is unknown; bus is 2-wire half duplex; access to the wallpad interior and floor display is available.

## Fixed decisions
- Hardware: XIAO ESP32-C6, XIAO RS485 Expansion Board (verify exact revision and schematic).
- Power: planned via HAT, but verify supply source, input range, regulator and ground; do not assume RS485 A/B supply power.
- Network: Matter over Thread; BLE for commissioning. Matter-only SmartThings integration; no proprietary SmartThings API dependency.
- ESP-IDF + esp-matter; OTA with space-optimized partition plan and rollback if feasible.
- Local management web UI requested. Since Wi-Fi web serving and Thread radio coexistence on ESP32-C6 can be constrained, validate resource and radio scheduling feasibility; allow commissioning/maintenance-only Wi-Fi AP and otherwise Thread mode.
- No unverified automatic transmissions, especially elevator controls.

## Sources for source-code verification
- https://polylog.tistory.com/231
- https://mscg.kr/65
- https://blog.oriang.net/24
- https://deepwiki.com/zooil/kocomRS485
- https://github.com/zooil/kocomRS485
- https://deepwiki.com/lunDreame/kocom-wallpad
- https://github.com/lunDreame/kocom-wallpad
- https://homenet2mqtt-docs.vercel.app/
- https://deepwiki.com/wooooooooooook/RS485-HomeNet-to-MQTT-bridge/11.2-entity-management-api
- https://github.com/wooooooooooook/RS485-HomeNet-to-MQTT-bridge
- https://github.com/iquix/kocom.py
NOTE: Some packet values below are transcribed from earlier discussion and have NOT been independently re-verified in this handoff. Cross-check original repositories, captures and documentation before coding behavior.

## Candidate protocol A: 21-byte frame
Example elevator call:
`AA5530BC0001004400010000000000000000320D0D`

Candidate byte layout (zero-indexed):
- 0..1: header AA55
- 2..3: type, e.g. 30BC
- 4..5: address A, e.g. 0001
- 6..7: address B, e.g. 0044
- 8..9: command, e.g. 0001
- 10..17: 8 data bytes
- 18: checksum
- 19..20: footer 0D0D

Candidate checksum: `sum(frame[2:18]) & 0xff` (Python exclusive end), which yields 0x32 for the example. Do not assume this applies to all KOCOM variants.

Candidate arrival-related examples from prior research (validate against sources and live logs):
```
AA5530BC0044000100000300000000000000340D0D
AA5530BD0044000100000300000000000000350D0D
AA5530BE0044000100000300000000000000360D0D
AA5530BC0044000100010300000000000000350D0D
AA5530BD0001004400010300000000000000360D0D
AA5530BE0001004400010300000000000000370D0D
```
Important: Do NOT treat all these as distinct semantic states, nor assume every one is an arrival indication, until correlated with timestamped physical events. Address direction and meaning of type B/C/D/E require validation.

## Candidate protocol B: shorter frame
Candidate elevator call:
`AA550B010D013101417C0D0D`
This is **12 bytes**; header AA55 and footer 0D0D. Internal layout, checksum coverage and semantics are unverified here. Parse as an independent variant; never transmit solely on this example without verifying against repository code and live captures. A single captured example is insufficient to derive a general checksum algorithm.

## Protocol detection
- Frame extraction must tolerate arbitrary stream boundaries, noise, repeated headers, partial packets, and 0D bytes in payload.
- Candidate A: 21-byte length, header/footer and checksum validation, then type/address validation.
- Candidate B: 12-byte length and source-confirmed checksum validation (not a guess).
- Do not decide variant solely on AA55. Provide configurable explicit protocol selection; auto-detection only after multiple validated frames, with confidence and logging.
- No automatic fallback from one transmit format to the other.

## Elevator state model
`UNKNOWN -> IDLE -> CALL_REQUESTED -> WAITING -> ARRIVED -> IDLE`
- Distinguish command queued, physical UART transmitted, ACK observed, and actual arrival confirmed.
- Timeouts yield UNKNOWN/ERROR, not fabricated success.
- Elevator call is a momentary action, not a persistent ON/OFF switch.
- Floor, direction, door state, cancel, ACK, and service state are **unknown** until evidence from bus capture.
- Expose via Matter only if an appropriate standard cluster/device type supports the intended semantics. Do not misrepresent elevator movement or call state as a standard switch without an explicit product decision and UX review. If Matter cannot model it adequately, keep local UI only.

## Engineering architecture
1. HAL: UART RX/TX, RS485 DE/RE direction control, collision-aware bus scheduling, timestamps, ring buffer.
2. Frame parser: independent `kocom_21` and `kocom_short` modules, no heap allocations in hot path.
3. Semantic decoder: source-specific device mappings, configurable address table, checksum and command interpretation.
4. Event store: current state, confidence, last-seen, error counters, duplicate suppression.
5. Command scheduler: disabled by default, allowlisted actions, rate limiting, bus-idle check, bounded retries only after explicit protocol confirmation.
6. Matter adapter: dynamic endpoints within supported memory budget; preserve endpoint identity across reboot.
7. Management: web-based diagnostics, packet capture, filters, import/export profiles, manual pairing controls, firmware version, safe OTA.
8. Persistent config: NVS, migration/versioning, redaction of sensitive network and fabric data.

## Development milestones
M0. Inspect board pinout, HAT schematics, power input and RS485 transceiver voltage; map UART TX/RX/DE/RE and boot-strap conflicts. Produce wiring diagram and bill of materials.
M1. Compile firmware; serial logs, watchdog, NVS, OTA partition feasibility, memory budget.
M2. Passive-only RS485 sniffer; capture binary+hex+timestamp with no bus driving.
M3. Unit-tested dual parser using positive/negative test vectors, fuzzing, noisy partial frames, checksum rejection.
M4. Protocol fingerprinting, event correlation from real wallpad calls/arrivals; annotate observed vs inferred fields.
M5. Manual gated TX of confirmed call only, on an isolated bench bus first; then controlled on-site test with permission. Enforce DE timing, UART flush, idle check and retry limits.
M6. Matter over Thread commissioning, SmartThings endpoint mapping, reconnect and fabric persistence.
M7. Lightweight maintenance web UI, secure configuration, signed/validated OTA and rollback where partition space permits.
M8. 72-hour soak, memory/stack checks, bus noise/reboot tests, multi-device coexistence, recovery and release notes.

## Acceptance tests
- Sniffer never transmits in passive mode (verify on scope/logic analyzer).
- Both parser variants reject malformed header/footer/checksum and recover from truncated/interleaved frames.
- No false arrival state from a single uncorrelated packet.
- TX disabled unless a confirmed profile is selected and the user explicitly enables it.
- Loss of Thread, reboot or OTA never triggers a new elevator call.
- Matter commissioning and state recovery work across power cycles.
- Capture exports redact secrets and contain no unnecessary personal data.

## Immediate Codex task
1. Clone/read all referenced public repositories and docs; generate a **source-verified evidence matrix** with exact URLs, file paths, line numbers or commit hashes for each packet and checksum claim. Separate VERIFIED / INFERRED / UNKNOWN.
2. Correct any mistaken packet length, semantic field mapping, checksum or state assumption in this handoff.
3. Inspect Seeed board and HAT schematics and confirm pin map, power, UART, DE/RE and voltage compatibility.
4. Propose an ESP-IDF component tree and implement passive sniffing + parser unit tests first. No live transmit code enabled by default.
5. Document resource tradeoffs for ESP32-C6 (Thread + BLE commissioning + optional Wi-Fi maintenance UI + Matter + OTA), with concrete flash/RAM partition budget once flash size is verified.
6. Ask for physical captures before claiming elevator ACK, arrival, floor or direction semantics.
