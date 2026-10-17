# Signed BLE OTA

The installed 0.4.0 bootstrap accepts only application images signed by the
same ECDSA key. On 2026-10-15, Android transferred the full signed 0.4.1 image,
the ESP verified it, booted `ota_1`, and the app confirmed version 0.4.1 after
reconnecting.

## Normal release flow

1. Increase `PROJECT_VER` in `firmware/CMakeLists.txt`. Versions use numeric
   `major.minor.patch` and must not be lower than the installed version floor.
2. Keep the private PEM outside version control. The configured local path is
   `.secrets/wmb_ota_signing_key.pem`; back it up offline. Losing it removes the
   ability to produce accepted OTA images.
3. Build with ESP-IDF 5.4.2 and run `scripts/check_ota_size.py` against the
   signed `wallpad-matter-bridge.bin`.
4. Copy only the signed binary to the phone. In Wallpad Bridge Manager, connect
   to the ESP, open **업데이트**, select the binary, and start OTA.
5. Do not remove power. Completion is reported only after the ESP reconnects
   and its Device Info characteristic returns the candidate version.

The OTA session forces RS485 receive-only mode. Every data block includes its
expected offset, and Android advances only after the ESP acknowledges the next
committed offset. The ESP checks image identity, semantic version, SHA-256,
embedded ECDSA signature, and boot-partition selection before restarting.

## Recovery and key policy

- A rejected or interrupted transfer leaves the current boot partition intact.
- A newly booted image is pending verification until NVS, BLE initialization,
  and passive RS485 initialization succeed; otherwise the ESP-IDF rollback
  mechanism can restore the prior slot.
- Hardware Secure Boot/eFuse anti-rollback is intentionally not provisioned by
  this development build because it is irreversible. Signed-on-update checking
  and the NVS semantic version floor are active.
- Physical recovery remains possible through the USB serial/JTAG bootloader.
  Once installation is complete, routine releases use BLE OTA only.
