#include "wmb/packet_analyzer.hpp"
#include "wmb/ble_frame.hpp"

#include <algorithm>
#include <cstdlib>

namespace wmb {

std::vector<ByteDifference> byte_differences(const Bytes& before, const Bytes& after) {
    std::vector<ByteDifference> output;
    const auto max_size = std::max(before.size(), after.size());
    for (std::size_t i = 0; i < max_size; ++i) {
        const auto left = i < before.size() ? std::optional(before[i]) : std::nullopt;
        const auto right = i < after.size() ? std::optional(after[i]) : std::nullopt;
        if (left != right) {
            output.push_back({i, left, right});
        }
    }
    return output;
}

std::vector<ChecksumCandidate> checksum_candidates(const Bytes& frame) {
    std::vector<ChecksumCandidate> result;
    if (frame.size() >= 2U) {
        std::uint8_t xor8{};
        std::uint8_t sum8{};
        for (std::size_t i = 0; i + 1U < frame.size(); ++i) {
            xor8 ^= frame[i];
            sum8 = static_cast<std::uint8_t>(sum8 + frame[i]);
        }
        if (xor8 == frame.back()) {
            result.push_back(ChecksumCandidate::Xor8);
        }
        if (sum8 == frame.back()) {
            result.push_back(ChecksumCandidate::Sum8);
        }
    }
    if (frame.size() >= 3U) {
        const auto expected = static_cast<std::uint16_t>(frame[frame.size() - 2U]) |
                              static_cast<std::uint16_t>(frame.back() << 8U);
        if (crc16_ccitt(frame.data(), frame.size() - 2U) == expected) {
            result.push_back(ChecksumCandidate::Crc16Ccitt);
        }
    }
    return result;
}

void PacketAnalyzer::observe(const Packet& packet) {
    // Noisy/sequence-heavy buses must not exhaust the MCU heap.
    if (packet.data.empty() || packet.data.size() > kMaxFrameBytes) return;
    if (groups_.size() >= kMaxGroups && groups_.find(packet.data) == groups_.end()) return;
    auto [it, inserted] = groups_.try_emplace(packet.data);
    auto& group = it->second;
    if (inserted) {
        group.exemplar = packet.data;
        group.first_seen_us = packet.timestamp_us;
    } else if (group.last_seen_us > 0U && packet.timestamp_us >= group.last_seen_us) {
        const auto interval = packet.timestamp_us - group.last_seen_us;
        group.estimated_period_us = group.estimated_period_us
                                        ? ((*group.estimated_period_us * 3U) + interval) / 4U
                                        : interval;
    }
    ++group.count;
    group.last_seen_us = packet.timestamp_us;
}

std::size_t PacketAnalyzer::unique_group_count() const { return groups_.size(); }

std::vector<PacketGroup> PacketAnalyzer::groups() const {
    std::vector<PacketGroup> output;
    output.reserve(groups_.size());
    for (const auto& entry : groups_) {
        output.push_back(entry.second);
    }
    std::sort(output.begin(), output.end(), [](const PacketGroup& a, const PacketGroup& b) {
        return a.count > b.count;
    });
    return output;
}

}  // namespace wmb

