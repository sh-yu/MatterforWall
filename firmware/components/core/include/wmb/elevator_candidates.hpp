#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

namespace wmb {

// HARDWARE_TEST_PENDING: structural matches are not elevator state or TX approval.
enum class ElevatorCandidate { None, Kocom21ChecksumMatch, KocomShortUnverified };

class ElevatorCandidateScanner {
public:
    ElevatorCandidate feed(std::uint8_t byte) {
        if (size_ == bytes_.size()) {
            for (std::size_t i = 1; i < size_; ++i) bytes_[i - 1U] = bytes_[i];
            --size_;
        }
        bytes_[size_++] = byte;
        if (size_ == 21U && framed(0U, 21U)) {
            unsigned sum = 0;
            for (std::size_t i = 2; i < 18U; ++i) sum += bytes_[i];
            if ((sum & 0xffU) == bytes_[18]) return ElevatorCandidate::Kocom21ChecksumMatch;
        }
        if (size_ >= 12U && framed(size_ - 12U, 12U)) {
            return ElevatorCandidate::KocomShortUnverified;
        }
        return ElevatorCandidate::None;
    }

    void reset() { size_ = 0; }

private:
    bool framed(std::size_t start, std::size_t length) const {
        return bytes_[start] == 0xaaU && bytes_[start + 1U] == 0x55U &&
               bytes_[start + length - 2U] == 0x0dU && bytes_[start + length - 1U] == 0x0dU;
    }
    std::array<std::uint8_t, 21> bytes_{};
    std::size_t size_{0};
};

}  // namespace wmb
