# BLE management protocol v1

Service UUID: `7bd10000-5f7b-4f4d-9c25-94c1e8f38a10`

| Characteristic | UUID suffix | Properties |
|---|---:|---|
| Device Info | `0001` | read, notify |
| Configuration | `0002` | read, write, indicate |
| Packet Stream | `0003` | notify |
| Command | `0004` | write |
| Response | `0005` | indicate |
| Diagnostics | `0006` | read, notify |
| OTA Control | `0007` | write, indicate |
| OTA Data | `0008` | write without response |

All UUIDs replace the first four hex digits of the service UUID. Access beyond
non-sensitive device identification requires LE Secure Connections, encrypted
GATT, bonded peer verification, and an application administrator challenge.

## Frame

```text
version:u8 | type:u8 | flags:u8 | sequence:u16le | length:u16le |
payload:length | crc16_ccitt:u16le
```

CRC-16/CCITT-FALSE uses polynomial `0x1021`, initial value `0xFFFF`, no
reflection, and no final XOR. The CRC covers header and payload.

Flags: bit 0 ACK required, bit 1 fragmented, bit 2 first fragment, bit 3 last
fragment. Every fragment is independently framed and CRC-protected. Fragments
must keep type and sequence. A new first fragment cancels an older assembly;
out-of-order, oversized, timed-out, or mixed-sequence assemblies are rejected.

The receiver advertises a window in its response. Senders stop at a zero window
and retransmit only ACK-required frames after timeout. Duplicate sequences are
ACKed without repeating side effects. Error responses contain a stable code,
the rejected sequence, and no credential material.

## OTA

`BEGIN` declares image size, SHA-256, image signature metadata, and monotonically
increasing firmware version. `DATA` carries offset plus bytes. The device accepts
only the exact next offset. `FINISH` hashes the slot and lets the ESP-IDF signed
application validator verify the image before selecting the boot partition.
During OTA, RS485 TX is forced off. The new image must pass a boot self-test and
mark itself valid; otherwise the bootloader rolls back. NVS and Matter fabric
partitions are outside both OTA slots.

