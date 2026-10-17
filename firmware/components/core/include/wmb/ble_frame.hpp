#pragma once

#include "wmb/interfaces.hpp"

#include <cstddef>
#include <cstdint>
#include <optional>
#include <unordered_map>
#include <vector>

namespace wmb {

constexpr std::uint8_t kBleProtocolVersion = 1;
constexpr std::size_t kBleHeaderSize = 7;
constexpr std::size_t kBleCrcSize = 2;
constexpr std::size_t kBleMaxPayload = 4096;

enum class BleMessageType : std::uint8_t {
    DeviceInfo = 0x01,
    Configuration = 0x02,
    PacketStream = 0x03,
    Command = 0x04,
    Response = 0x05,
    Diagnostics = 0x06,
    OtaControl = 0x07,
    OtaData = 0x08,
    Error = 0x7f,
};

enum BleFlags : std::uint8_t {
    AckRequired = 1U << 0U,
    Fragmented = 1U << 1U,
    FirstFragment = 1U << 2U,
    LastFragment = 1U << 3U,
};

struct BleFrame {
    std::uint8_t version{kBleProtocolVersion};
    BleMessageType type{BleMessageType::Error};
    std::uint8_t flags{};
    std::uint16_t sequence{};
    Bytes payload;
};

std::uint16_t crc16_ccitt(const std::uint8_t* data, std::size_t length);
Bytes encode_ble_frame(const BleFrame& frame);
std::optional<BleFrame> decode_ble_frame(const Bytes& encoded);
std::vector<BleFrame> fragment_ble_frame(const BleFrame& frame,
                                         std::size_t mtu_payload);

class BleReassembler {
public:
    explicit BleReassembler(std::size_t max_payload = kBleMaxPayload);
    std::optional<BleFrame> accept(const BleFrame& fragment);
    void reset();

private:
    std::size_t max_payload_;
    std::optional<BleFrame> pending_;
};

}  // namespace wmb

