#include "wmb/ble_frame.hpp"

#include <algorithm>

namespace wmb {
namespace {

void append_le16(Bytes& output, std::uint16_t value) {
    output.push_back(static_cast<std::uint8_t>(value & 0xffU));
    output.push_back(static_cast<std::uint8_t>((value >> 8U) & 0xffU));
}

std::uint16_t read_le16(const Bytes& input, std::size_t offset) {
    return static_cast<std::uint16_t>(input[offset]) |
           static_cast<std::uint16_t>(input[offset + 1U] << 8U);
}

}  // namespace

std::uint16_t crc16_ccitt(const std::uint8_t* data, std::size_t length) {
    std::uint16_t crc = 0xffffU;
    for (std::size_t i = 0; i < length; ++i) {
        crc ^= static_cast<std::uint16_t>(data[i]) << 8U;
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc & 0x8000U) != 0U
                      ? static_cast<std::uint16_t>((crc << 1U) ^ 0x1021U)
                      : static_cast<std::uint16_t>(crc << 1U);
        }
    }
    return crc;
}

Bytes encode_ble_frame(const BleFrame& frame) {
    if (frame.payload.size() > kBleMaxPayload || frame.payload.size() > 0xffffU) {
        return {};
    }
    Bytes output;
    output.reserve(kBleHeaderSize + frame.payload.size() + kBleCrcSize);
    output.push_back(frame.version);
    output.push_back(static_cast<std::uint8_t>(frame.type));
    output.push_back(frame.flags);
    append_le16(output, frame.sequence);
    append_le16(output, static_cast<std::uint16_t>(frame.payload.size()));
    output.insert(output.end(), frame.payload.begin(), frame.payload.end());
    append_le16(output, crc16_ccitt(output.data(), output.size()));
    return output;
}

std::optional<BleFrame> decode_ble_frame(const Bytes& encoded) {
    if (encoded.size() < kBleHeaderSize + kBleCrcSize) {
        return std::nullopt;
    }
    const auto payload_length = read_le16(encoded, 5U);
    if (payload_length > kBleMaxPayload ||
        encoded.size() != kBleHeaderSize + payload_length + kBleCrcSize) {
        return std::nullopt;
    }
    const auto expected_crc = read_le16(encoded, encoded.size() - kBleCrcSize);
    if (crc16_ccitt(encoded.data(), encoded.size() - kBleCrcSize) != expected_crc) {
        return std::nullopt;
    }
    BleFrame frame;
    frame.version = encoded[0];
    if (frame.version != kBleProtocolVersion) {
        return std::nullopt;
    }
    frame.type = static_cast<BleMessageType>(encoded[1]);
    frame.flags = encoded[2];
    frame.sequence = read_le16(encoded, 3U);
    frame.payload.assign(encoded.begin() + static_cast<std::ptrdiff_t>(kBleHeaderSize),
                         encoded.end() - static_cast<std::ptrdiff_t>(kBleCrcSize));
    return frame;
}

std::vector<BleFrame> fragment_ble_frame(const BleFrame& frame,
                                         std::size_t mtu_payload) {
    if (mtu_payload == 0U) {
        return {};
    }
    if (frame.payload.size() <= mtu_payload) {
        return {frame};
    }
    std::vector<BleFrame> fragments;
    for (std::size_t offset = 0; offset < frame.payload.size(); offset += mtu_payload) {
        const auto end = std::min(offset + mtu_payload, frame.payload.size());
        BleFrame fragment = frame;
        fragment.flags = static_cast<std::uint8_t>(frame.flags | Fragmented);
        if (offset == 0U) {
            fragment.flags = static_cast<std::uint8_t>(fragment.flags | FirstFragment);
        }
        if (end == frame.payload.size()) {
            fragment.flags = static_cast<std::uint8_t>(fragment.flags | LastFragment);
        }
        fragment.payload.assign(frame.payload.begin() + static_cast<std::ptrdiff_t>(offset),
                                frame.payload.begin() + static_cast<std::ptrdiff_t>(end));
        fragments.push_back(std::move(fragment));
    }
    return fragments;
}

BleReassembler::BleReassembler(std::size_t max_payload) : max_payload_(max_payload) {}

std::optional<BleFrame> BleReassembler::accept(const BleFrame& fragment) {
    if ((fragment.flags & Fragmented) == 0U) {
        return fragment.payload.size() <= max_payload_ ? std::optional(fragment) : std::nullopt;
    }
    const bool first = (fragment.flags & FirstFragment) != 0U;
    const bool last = (fragment.flags & LastFragment) != 0U;
    if (first) {
        pending_ = fragment;
        pending_->payload.clear();
        pending_->flags = static_cast<std::uint8_t>(fragment.flags &
                                                    ~(Fragmented | FirstFragment | LastFragment));
    } else if (!pending_ || pending_->sequence != fragment.sequence ||
               pending_->type != fragment.type) {
        reset();
        return std::nullopt;
    }
    if (!pending_ || pending_->payload.size() + fragment.payload.size() > max_payload_) {
        reset();
        return std::nullopt;
    }
    pending_->payload.insert(pending_->payload.end(), fragment.payload.begin(),
                             fragment.payload.end());
    if (!last) {
        return std::nullopt;
    }
    auto complete = pending_;
    reset();
    return complete;
}

void BleReassembler::reset() { pending_.reset(); }

}  // namespace wmb
