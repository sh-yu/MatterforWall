# Remaining engineering work

This bootstrap intentionally distinguishes compilable foundations from finished
hardware integrations.

## Milestone 2 — Communication

- Validate D4/D5/D2 electrically and run long-duration receive-only capture.
- Add a separately provisioned administrator challenge/response credential for
  future control/profile mutation. BLE bonding alone authorizes diagnostics and
  OTA, not actuator writes.

## Milestone 3 — Analysis and profiles

- Add automatic frame-boundary scoring, fixed/dynamic field confidence,
  address/command/state candidate ranking, session comparisons, filtering, and
  CSV/JSON export UI.
- Add signed/approved execution-profile provisioning to NVS. Importing or user
  approval alone must never change a profile to `CONTROL_VERIFIED`.

## Milestone 4 — Matter

- Complete and build the esp-matter dynamic bridged On/Off Light endpoint with
  Bridged Device Basic Information, aggregator hierarchy, naming, persistence,
  command rejection/forwarding, and physical-state subscriptions.
- Commission against a SmartThings Station and record actual endpoint/UI
  behavior. Keep gas/elevator/floor Android-only unless a correct standard type
  and ecosystem behavior are demonstrated.

## Milestone 5 — OTA and security

- Signed BLE OTA, offset ACKs, SHA-256/ECDSA verification, version floor,
  reboot, self-test, and Android post-reconnect version confirmation are done.
- Add automated invalid-signature and power-loss fault injection, then verify
  bootloader rollback under a deliberately failed application self-test.
- Implement secure administrator credential provisioning/storage, rate limits,
  redacted audit logs, and a physical-presence safe factory reset.

## Milestone 6 — Integration

- Capture and independently verify the exact KOCOM model protocol.
- Add replay and response-timing tests for every approved light command.
- Do not enable gas close or elevator transmission without site authorization,
  protocol verification, collision analysis, and documented fail-safe review.
