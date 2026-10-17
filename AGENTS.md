# Engineering rules

## Priorities

1. Preserve physical safety and keep RS485 transmit disabled by default.
2. Keep host-side core tests deterministic and independent of hardware.
3. Preserve OTA rollback, Matter fabric data, and user configuration.
4. Prefer standard Matter device types; never disguise unsupported functions.
5. Mark unverified packets `SYNTHETIC_TEST_DATA` or `HARDWARE_TEST_PENDING`.

## Safety invariants

- Boot in `PASSIVE_SNIFFER`. `ACTIVE_CONTROL` requires a verified profile,
  authenticated administrator session, explicit transmit enable, healthy bus,
  and no OTA operation.
- Never implement remote gas-valve opening. Closing/locking remains disabled
  until the physical protocol and fail-safe behavior are verified.
- Never transmit elevator frames until an approved building interface and the
  exact protocol are documented and reviewed.
- Direction GPIO must be driven to receive before the UART driver starts.
- Do not connect the board to an occupied bus until voltage, polarity, common
  ground, termination, idle bias, and baud/parity are measured.
- Secrets, passcodes, firmware keys, raw credentials, and full access tokens
  must never be logged or committed.

## Development

- C++ core code is C++17, warning-clean under `-Wall -Wextra -Wpedantic`.
- Kotlin follows official formatting and uses immutable UI state with Flow.
- External systems sit behind the interfaces in `firmware/components/core` or
  the corresponding Android transport/repository abstractions.
- Every real KOCOM rule needs captured evidence, profile versioning, review,
  replay tests, and a safe command allow-list.
- Keep public wire fields little-endian. Reject malformed length, CRC, sequence,
  fragment, and offset values before allocating or mutating state.

## Test commands

```powershell
cmake -S . -B build -DWMB_BUILD_TESTS=ON
cmake --build build
ctest --test-dir build --output-on-failure

cd android
./gradlew testDebugUnitTest lintDebug assembleDebug

cd ../firmware
idf.py set-target esp32c6
idf.py build
```

Hardware tests must report `HARDWARE_TEST_PENDING`; do not silently skip them
or convert them into passing tests.

## Commit hygiene

- Keep generated output, signing material, and local SDK paths out of Git.
- Prefix synthetic captures and fixtures with `SYNTHETIC_TEST_DATA`.
- Update `docs/verification-status.md` when validation state changes.

