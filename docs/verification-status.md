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
- Native Windows Matter configuration encountered POSIX path incompatibility.
  This was resolved by building the signed 0.4.3 Matter image in Linux/WSL;
  physical commissioning remains HARDWARE_TEST_PENDING.
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

- Firmware 0.4.4: explicit valid BEGIN aborts an unfinished OTA transfer and
  restarts at offset zero; malformed or oversized metadata does not abort it.
  BEGIN errors distinguish metadata, signature configuration, slot size,
  unconfirmed running image and flash-start failure. Finish/reboot excludes
  conflicting BLE OTA requests. Upload suppresses analysis/packet streaming,
  not UART reception/statistics. Per-packet INFO logging reduced to DEBUG.
  Analyzer retains at most 128 unique groups of up to 256 bytes; existing
  groups continue updating at capacity. Host tests including analysis bounds
  passed under C++17 with -Wall -Wextra -Wpedantic -Werror on 2026-10-18.
  OTA recovery, throughput, power-loss and rollback behavior remain
  HARDWARE_TEST_PENDING. No lighting TX or installation enrollment added.
  ESP-IDF 5.4.1 Matter signed build passed: 1,576,960 bytes, 389,120 bytes
  free in the existing 1,966,080-byte OTA slot (4 KiB reserve satisfied).
  Release manifest version/project/size/signature-sector checks passed;
  Python repository/release tests passed 4/4. Physical 0.4.4 OTA is pending.

- Android 0.5.2 adds a pure installation-qualification policy requiring a
  reviewed protocol, administrator authentication, explicit TX enable, healthy
  bus, no OTA and learned ON/OFF evidence before testing; qualification then
  requires three successful correlated ON and OFF attempts. Echo, synthetic,
  duplicate, timed-out and failed attempts cannot qualify. Identity includes
  bridge/profile version/address/channel. This policy is NOT connected to a
  real protocol adapter, UI test runner or firmware enrollment; there are zero
  reviewed lighting profiles, so no real TX/automatic approval is available.
  UI and release notes disclose this. Firmware remains unchanged at 0.4.3.
  Four qualification-policy unit tests passed (minimum counts, live safety
  gates, invalid evidence, identity changes/duplicates). Full Android unit
  tests, lintDebug and assembleDebug passed on 2026-10-18.

- Android 0.5.2 OTA optimization source: request MTU 517 and high connection
  priority, derive chunk size from ATT and 512-byte attribute limits (7/231/499
  bytes at MTU 23/247/517), remove the extra 12 ms post-ACK delay. Stop-and-wait
  offset ACKs and image verification remain unchanged. Added sizing unit test.
  Build/test attempt was initially blocked by the external cache permissions;
  after access was restored, unit tests, lint and assembleDebug passed on
  2026-10-18. Not published or hardware-throughput-tested. Peer MTU may remain
  247 or smaller.
- Named per-bridge light learning drafts now persist on Android with separate
  on/off actions, baseline/action timestamped evidence and explicit pending
  verification status. Atomic file writes protect existing drafts. Saving or
  reloading a draft cannot grant CONTROL_VERIFIED. Persistence unit tests,
  Android lint and debug build passed; real capture/restore remains
  HARDWARE_TEST_PENDING. Firmware authenticated enrollment, verified protocol
  adapters, command dispatch and Matter light state binding remain unimplemented.

- Guided-learning Android unit tests, lintDebug and assembleDebug passed on
  2026-10-18. Tests cover the three-round minimum, background exclusion,
  synthetic exclusion and per-round duplicate suppression. Hardware behavior
  is not established by these host-side tests.
- Guided Android light learning: 5-second baseline, 10-second action windows,
  three-to-five repeated captures, exact-match candidate comparison and checksum
  hints. Background and synthetic frames are excluded; captures are bounded to
  500 frames per window and kept in memory only. Candidates do not establish
  command direction, ACK semantics or control approval. Physical TX remains
  disabled; verified-profile enrollment and authenticated control are not yet
  implemented. Real learning and command/response correlation remain pending.

- Electrical continuity and logic-level verification of the exact installed
  RS485 board revision, plus a sustained real wallpad receive capture.
- BLE sustained packet-stream/backpressure soak testing and OTA power-loss
  injection at multiple offsets.
- Matter firmware 0.4.3 built with ESP-IDF 5.4.1: signed binary 1,576,960
  bytes, fits the existing 0x1e0000-byte OTA slot. Shared BLE host behavior,
  Matter OTA boot and hub commissioning still require hardware verification.
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
