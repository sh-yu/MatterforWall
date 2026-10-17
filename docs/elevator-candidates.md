# Elevator candidate inspection

Both candidates are HARDWARE_TEST_PENDING for this installation. Selection in
the Android elevator screen affects local receive analysis only. It does not
change UART configuration, administrator authorization, or transmit policy.

- Option 1: 21-byte AA55 / 0D0D framing, candidate SUM8 over bytes [2,18),
  compared with byte 18. This validates a candidate frame structure, not the
  identity of an elevator, a call, a floor, or arrival.
- Option 2: 12-byte AA55 / 0D0D framing. Checksum algorithm remains unknown;
  structural matches explicitly display checksum unverified.
- Firmware 0.4.2 scans both candidate structures using bounded 21-byte storage;
  it does not construct or transmit elevator commands. Matches are diagnostic
  logs only. BLE continues forwarding raw RX packets for phone-side selection.
- App 0.3.0 offers an offline example test and displays up to 30 recent structural
  matches from the retained real RX capture. Samples stay separate from capture
  history and are labelled SYNTHETIC_TEST_DATA. Capture retention remains 500
  packets, so frames spanning the retention boundary cannot be reconstructed.

## Evidence reviewed 2026-10-18

- https://polylog.tistory.com/231 contains the option-1 example and arrival-related
  observations for its author's installation. The article explicitly describes
  differing arrival handling between drivers and does not establish compatibility
  with this building. Source observation is not local hardware verification.
- https://mscg.kr/65 and https://blog.oriang.net/24 are reference links supplied
  in the handoff and were retrieved for review. No validated local interface,
  short-frame checksum rule, or permission to send was established from them.
- CODEX_KOCOM_BRIDGE_HANDOFF.md records both examples and explicitly marks the
  short format's layout/checksum/semantics unverified.

Host and Android tests cover noise, split/coalesced frames, candidate checksum
corruption, short-format uncertainty and malformed headers. Hardware capture,
arrival interpretation, floor/direction and live transmission remain pending.
