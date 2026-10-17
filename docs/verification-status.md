# Verification status

Last updated: 2026-10-15, Asia/Seoul.

## 2026-10-18 app/release changes

- Added public GitHub stable-release lookup, HTTPS bounded download, manifest
  identity/size/hash validation, numeric version comparison, and existing BLE
  OTA handoff. Repository selection is saved; saved sources are checked at launch.
- Replaced release workflow with signed BLE firmware build, Android checks,
  required stable signing secrets, slot checks, manifest generation, and draft
  publication. Cloud workflow execution is unverified; no Git remote exists.
- Android test/lint/APK build attempted: blocked by managed-environment Gradle
  cache access and daemon connection failure, before source compilation.
- Python profile/partition/release-manifest checks: 4/4 passed with workspace
  temporary directory. Android numeric version tests added but not executed.
- New end-to-end app/GitHub/OTA and all elevator control: HARDWARE_TEST_PENDING.
- See `docs/mobile-ota.md` for setup and computer-off limitations.

## Automated

- ESP-IDF v5.4.2 signed firmware 0.4.1 build: passed (Matter disabled).
- ESP-Matter 1.4.2 configuration: prepared. Native Windows configuration is
  `HARDWARE_TEST_PENDING`: its generated CMake include paths are POSIX-only
  and fail with Windows path escapes, so no Matter image has been produced or
  offered for OTA. Build it in the supported Linux/WSL environment before
  commissioning.
- Signed image: 724,992 bytes; each OTA slot is 1,966,080 bytes; free after the
  required 4 KiB reserve: 1,241,088 bytes.
- Portable BLE frame, analyzer, and safety-policy sources: warning-clean C6
  cross-compilation passed; native host execution is delegated to Linux CI.
- Android codec, analyzer, learning, export, profile, and OTA validator JUnit:
  passed; debug APK build passed.
- Android lint: passed with no errors.
- Python profile/partition tests: 3/3 passed.

## Hardware passed

- XIAO ESP32-C6 boots the ESP-IDF 5.4.2 image and initializes the RS485 board
  on TX D4/GPIO22, RX D5/GPIO23, DE+/RE D2/GPIO2 in receive-only mode.
- Samsung SM-S931N scans, pairs with BLE Secure Connections, negotiates the
  custom GATT service, reads device information/diagnostics, and displays an
  explicit online state.
- A complete 724,992-byte update was transferred from Android over BLE using
  peripheral offset ACKs. The ESP verified SHA-256 and the embedded ECDSA
  signature, selected `ota_1`, rebooted, passed the NVS/passive-UART self-test,
  and reported firmware 0.4.1 after automatic reconnect.
- The Android app declares OTA success only after reconnecting and reading the
  expected installed version.

## HARDWARE_TEST_PENDING

- Electrical continuity and logic-level verification of the exact installed
  RS485 board revision, plus a sustained real wallpad receive capture.
- BLE sustained packet-stream/backpressure soak testing and OTA power-loss
  injection at multiple offsets.
- ESP-IDF/esp-matter complete firmware binary size and signed OTA-slot fit on
  the 4 MB target.
- SmartThings Station Thread commissioning, bridge discovery, light control,
  subscription/state synchronization, fabric persistence, and UI rendering.
- Deliberate invalid-signature, power-loss, watchdog-failure, and automatic
  rollback fault-injection tests. The valid-image/self-test path is passed.
- All real KOCOM framing, baud/parity, addresses, checksums, state meanings,
  response timing, collision behavior, and every command.

## Deliberately disabled

- Gas-valve remote opening.
- Gas-valve close/lock until protocol and mechanical fail-safe validation.
- Elevator frame transmission until approved interface validation.
- Any RS485 TX when booting, updating, unhealthy, unauthenticated, or using an
  unverified profile.
