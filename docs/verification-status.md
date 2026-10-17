# Verification status

Last updated: 2026-10-15, Asia/Seoul.

## 2026-10-18 app/release changes

- Published GitHub release v0.4.2 with signed firmware 0.4.2, manager APK 0.4.0,
  firmware-update.json and android-update.json. Public API metadata lookup and
  downloaded binary/APK size and SHA-256 verification passed. Main source pushed.
- Existing device signing key and stable Android development keystore registered
  as encrypted Actions secrets. Values were not printed or committed. Future
  cloud release execution itself remains unverified.

- Manager 0.4.0 adds GitHub app metadata/APK download, package/version/hash/signer
  checks and Android installer handoff. Android tests/lint/build passed and
  wireless ADB replacement installation succeeded. GitHub installer end-to-end
  approval/upgrade and malicious APK rejection remain HARDWARE_TEST_PENDING.

- Elevator candidate inspection added: app 0.3.0, option 1 (21 bytes/candidate
  SUM8) and option 2 (12 bytes/checksum unknown), separate offline samples and
  passive real-RX comparison. Android unit tests/lint/build passed; replacement
  installation on SM-S931N succeeded. Firmware 0.4.2 signed build passed and
  fits the unchanged A/B OTA slots. Device installation remains pending BLE OTA.
- Native Linux C++17 warning-clean core build/CTest passed, including candidate
  framing/noise/checksum rejection. Live bus matching and elevator semantics
  remain HARDWARE_TEST_PENDING. Elevator transmission remains disabled.
- AGENTS.md now explicitly permits passive option selection/offline replay while
  retaining reviewed-interface and protocol requirements for physical control.

- Public repository `sh-yu/MatterforWall` verified accessible; latest-release API
  returns 404 because no release is published. Added explanatory empty-release
  message, passed Android unit tests/lint/APK build, and reinstalled over wireless
  ADB successfully. No firmware release was created by this check.

- Added public GitHub stable-release lookup, HTTPS bounded download, manifest
  identity/size/hash validation, numeric version comparison, and existing BLE
  OTA handoff. Repository selection is saved; saved sources are checked at launch.
- Replaced release workflow with signed BLE firmware build, Android checks,
  required stable signing secrets, slot checks, manifest generation, and draft
  publication. Cloud workflow execution is unverified; no Git remote exists.
- Android test/lint/APK build attempted: blocked by managed-environment Gradle
  cache access and daemon connection failure, before source compilation.
- Retried with full execution access on 2026-10-18: `testDebugUnitTest lintDebug
  assembleDebug` passed. Updated manager APK generated; installation is pending
  a reachable phone wireless-debugging endpoint (ADB/mDNS currently finds none).
- Subsequently installed manager 0.2.0 (versionCode 2) over wireless ADB on
  Samsung SM-S931N: package replacement returned Success, installed version and
  application process launch confirmed. GitHub-download-to-ESP OTA remains
  HARDWARE_TEST_PENDING.
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
