# Wallpad Matter Bridge

Safety-first firmware and Android tooling for observing an unknown KOCOM
wallpad RS485 bus with a Seeed Studio XIAO ESP32-C6 and exposing only verified,
standard functions through Matter over Thread.

This repository is an implementation foundation, not a claim of compatibility
with any KOCOM model. All included packets are labelled
`SYNTHETIC_TEST_DATA`. RS485 transmit, gas control, and elevator calls remain
disabled until protocol and site safety checks are complete. "Safe mode" is
therefore not a global switch: receive, analysis, diagnostics, and signed OTA
are operational, while unverified actuator writes fail closed.

## Implemented foundation

- Portable C++17 interfaces, BLE frame codec, fragmentation/reassembly,
  packet grouping/diff/checksum candidates, and fail-closed transmit policy.
- ESP-IDF UART half-duplex adapter that initializes the transceiver in receive
  mode, captures timestamps/statistics, and gates every transmission.
- A 4 MB dual-slot signed BLE OTA implementation with offset ACKs, SHA-256,
  ECDSA verification, semantic downgrade rejection, self-test, and rollback.
- Android Material 3 dashboard, live BLE monitor/analyzer, signed-image picker,
  OTA progress/reconnect/version verification, packet codec and analysis logic,
  profile import validation, Room capture storage, and DataStore settings.
- Versioned JSON Schema and Generic/KOCOM-unknown profiles.
- Host and Android unit tests and GitHub Actions workflows.

## Pin assignment

| Signal | XIAO pin | ESP32-C6 GPIO | Direction |
|---|---:|---:|---|
| RS485 DI / UART TX | D4 | 22 | MCU to transceiver |
| RS485 RO / UART RX | D5 | 23 | Transceiver to MCU |
| DE + /RE | D2 | 2 | High transmit, low receive |

See [hardware/README.md](hardware/README.md) before connecting anything.
The Seeed wiki contains contradictory example comments for D4/D5; the table
above follows the board schematic/net direction and reported C6 wiring. Verify
continuity on the exact board revision before enabling transmit.

## Quick start: portable tests

```powershell
cmake -S . -B build -DWMB_BUILD_TESTS=ON
cmake --build build
ctest --test-dir build --output-on-failure
```

## Android

Open `android/` in Android Studio, or run:

```powershell
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug app scans for the ESP32 custom GATT service and clearly reports
offline, connecting, authenticated, and online states.

## Firmware SDK pins

- ESP-IDF `v5.4.2`
- esp-matter `release/v1.4.2`
- target `esp32c6`

These conservative pins match the Matter 1.4.2 branch era and ESP32-C6 support.
The upstream default branch now recommends ESP-IDF 6.0.2, so upgrading must be a
separate tested change. Build setup is in [docs/build.md](docs/build.md).

The signed 0.4.1 hardware build (Matter disabled) is 724,992 bytes and leaves
1,241,088 bytes in each 1,966,080-byte OTA slot after the 4 KiB reserve. This is
**not** evidence that the Matter-enabled image fits; CI and a complete
esp-matter environment must measure that image independently. See
[docs/ota.md](docs/ota.md) for the release and recovery procedure.

## Validation status

See [docs/verification-status.md](docs/verification-status.md). BLE Secure
Connections and signed OTA are verified on the named ESP32-C6 and Android
phone. Live SmartThings and KOCOM interoperability remain
`HARDWARE_TEST_PENDING`.

Remaining implementation work is tracked in [docs/roadmap.md](docs/roadmap.md).
