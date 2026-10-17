# Official-source research notes

Reviewed 2026-10-14. These links are inputs to the design, not proof that a
specific apartment installation is electrically or functionally compatible.

## Hardware

- [XIAO ESP32-C6 pin map](https://wiki.seeedstudio.com/xiao_esp32c6_getting_started/):
  D2/GPIO2, D4/GPIO22, D5/GPIO23.
- [XIAO RS485 expansion guide](https://wiki.seeedstudio.com/XIAO-RS485-Expansion-Board/):
  D4/D5 UART connection, D2 direction enable, selectable 120 ohm termination,
  and 5 V IN/OUT switch. Its prose/comments conflict on RX/TX direction.
- [Expansion-board schematic](https://files.seeedstudio.com/wiki/rs485_ExpansionBoard/Seeed_Studio_XIAO_RS485_Expansion_Board.pdf):
  source for transceiver nets and TPS54302 power-stage review. Continuity on
  the purchased PCB remains mandatory.

Resolved working assignment: D4/GPIO22 is TX to transceiver DI, D5/GPIO23 is
RX from RO, and D2/GPIO2 drives tied DE+/RE. Manual direction GPIO is used; UART
RTS is not assumed compatible with the board.

## SDK and radio

- [esp-matter repository](https://github.com/espressif/esp-matter) documents the
  active branches and its current ESP-IDF recommendation.
- [ESP-IDF chip compatibility](https://github.com/espressif/esp-idf/blob/master/COMPATIBILITY.md)
  lists ESP32-C6 support from ESP-IDF 5.1 and revision-specific minimums.
- [ESP32-C6 RF coexistence](https://docs.espressif.com/projects/esp-idf/en/stable/esp32c6/api-guides/coexist.html)
  documents the shared 2.4 GHz radio and Thread/BLE concurrency limitations.

The project pins ESP-IDF v5.4.2 with esp-matter release/v1.4.2 as a conservative
baseline. Upstream main has moved to ESP-IDF 6.0.2; migration is intentionally
not mixed into the first hardware-validation baseline.

## Matter

- [Espressif Matter guide](https://docs.espressif.com/projects/esp-matter/en/release-v1.4.2/esp32c6/developing.html)
  documents endpoint creation and bridge commands for the pinned branch.
- [SmartThings connected devices](https://developer.smartthings.com/devices)
  confirms Matter devices use a compatible SmartThings Hub.
- [Matter data model primer](https://developers.home.google.com/matter/primer/device-data-model)
  provides a public explanation of endpoints, device types, and clusters.

On/Off Light is standards-aligned. No verified standard SmartThings-facing
device type was found that accurately represents KOCOM gas-valve status,
elevator call, or current-floor display. Those remain Android-only rather than
being disguised as unrelated device types.

