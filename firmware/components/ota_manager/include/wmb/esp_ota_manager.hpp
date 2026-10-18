#pragma once

#include "wmb/interfaces.hpp"

#include "esp_ota_ops.h"
#include "mbedtls/sha256.h"

#include <array>
#include <atomic>

namespace wmb {

class EspOtaManager final : public IOtaManager {
public:
    EspOtaManager();
    ~EspOtaManager() override;
    bool begin(std::uint32_t size, const Bytes& expected_sha256) override;
    bool write(std::uint32_t offset, const Bytes& chunk) override;
    bool finish() override;
    void abort() override;
    bool active() const override;
    std::uint32_t next_offset() const override;
    std::uint32_t expected_size() const override;
    const char* last_error() const override { return last_error_; }

private:
    esp_ota_handle_t handle_{0};
    const esp_partition_t* partition_{nullptr};
    std::uint32_t expected_size_{0};
    std::uint32_t offset_{0};
    std::array<std::uint8_t, 32> expected_hash_{};
    Bytes image_prefix_;
    mbedtls_sha256_context sha_context_{};
    std::atomic_bool active_{false};
    const char* last_error_{"NONE"};
};

}  // namespace wmb
