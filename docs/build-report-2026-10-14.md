# Local verification report — 2026-10-14

Environment: Windows, Android SDK platforms 33–36.1, Android Studio JBR 21,
Gradle 8.14, ESP-IDF v5.4.2, and Espressif RISC-V GCC 14.2.0. A complete native
Windows C++ runtime toolchain was not installed.

## Passed

- `gradlew testDebugUnitTest lintDebug assembleDebug`: passed.
- Android JUnit: 3 tests, 0 failures, 0 errors, 0 skipped.
- Android lint: 0 errors, 16 advisory warnings (principally intentionally pinned
  versions, target SDK 35, and packaging metadata).
- Debug APK: 18,292,312 bytes.
- `python -m unittest discover -s tests/python -v`: 3 tests passed.
- Profile validator: Generic and KOCOM Unknown Model profiles passed.
- Portable C++ core and its test translation unit: compiled warning-clean to
  ESP32-C6 RISC-V objects with `-Wall -Wextra -Wpedantic -Werror`.
- ESP-IDF v5.4.2 safe-base firmware (Matter disabled): compiled and linked for
  ESP32-C6 after replacing exception-based codec errors with fail-closed returns.
- Firmware binary: 235,472 bytes (`0x397D0`). SHA-256:
  `4A74F305A12B265113AFA2326ACBD2103D355D2A30A562F70F3D5D21584BD403`.
- OTA slot: 1,966,080 bytes; safe-base free space: 1,730,608 bytes (88%).
- Bootloader: `0x5690` bytes, with `0x2970` bytes reported free.

## Not run / pending

- Native execution of C++ host tests: blocked by missing Windows C++ system
  linker libraries. CI runs these tests on Ubuntu.
- Matter-enabled link and size: the Windows connectedhomeip bootstrap could not
  activate its Python environment after a 7.3 GB partial recursive checkout.
  The pinned Linux CI workflow performs this build. Matter-image OTA free space
  remains unknown; the safe-base number above must not be reused for it.
- BLE, RS485 electrical, Thread, Matter/SmartThings, OTA reboot/rollback, and
  long-duration tests: `HARDWARE_TEST_PENDING`.
