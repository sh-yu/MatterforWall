#pragma once

#include "wmb/interfaces.hpp"

#include <cstddef>
#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <vector>

namespace wmb {

struct PacketGroup {
    Bytes exemplar;
    std::uint64_t count{};
    std::uint64_t first_seen_us{};
    std::uint64_t last_seen_us{};
    std::optional<std::uint64_t> estimated_period_us;
};

struct ByteDifference {
    std::size_t offset{};
    std::optional<std::uint8_t> before;
    std::optional<std::uint8_t> after;
};

enum class ChecksumCandidate { Xor8, Sum8, Crc16Ccitt };

std::vector<ByteDifference> byte_differences(const Bytes& before, const Bytes& after);
std::vector<ChecksumCandidate> checksum_candidates(const Bytes& frame);

class PacketAnalyzer final : public IPacketAnalyzer {
public:
    void observe(const Packet& packet) override;
    std::size_t unique_group_count() const override;
    std::vector<PacketGroup> groups() const;

private:
    std::map<Bytes, PacketGroup> groups_;
};

}  // namespace wmb

