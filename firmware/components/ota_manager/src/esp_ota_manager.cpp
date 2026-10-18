#include "wmb/esp_ota_manager.hpp"

#include "esp_log.h"
#include "esp_app_desc.h"
#include "esp_image_format.h"
#include "nvs.h"
#include "sdkconfig.h"

#include <algorithm>
#include <cstdio>
#include <cstring>

namespace wmb {
namespace {

constexpr const char* kTag = "wmb_ota";
constexpr const char* kNvsNamespace = "wmb_ota";
constexpr const char* kMinimumVersionKey = "min_version";
constexpr std::size_t kImageDescriptionOffset =
    sizeof(esp_image_header_t) + sizeof(esp_image_segment_header_t);
constexpr std::size_t kRequiredPrefix = kImageDescriptionOffset + sizeof(esp_app_desc_t);

constexpr bool signed_update_validation_enabled() {
#if defined(CONFIG_SECURE_SIGNED_APPS_NO_SECURE_BOOT) || \
    defined(CONFIG_SECURE_BOOT_V2_ENABLED) || defined(CONFIG_SECURE_BOOT)
    return true;
#else
    return false;
#endif
}

bool parse_version(const char* text, std::array<std::uint32_t, 3>& output) {
    if (text == nullptr) return false;
    unsigned major = 0;
    unsigned minor = 0;
    unsigned patch = 0;
    char trailing = '\0';
    const int fields = std::sscanf(text, "%u.%u.%u%c", &major, &minor, &patch, &trailing);
    if (fields != 3) return false;
    output = {major, minor, patch};
    return true;
}

std::uint32_t pack_version(const std::array<std::uint32_t, 3>& version) {
    if (version[0] > 1023U || version[1] > 1023U || version[2] > 4095U) return 0;
    return (version[0] << 22U) | (version[1] << 12U) | version[2];
}

std::uint32_t stored_minimum_version() {
    nvs_handle_t handle{};
    if (nvs_open(kNvsNamespace, NVS_READONLY, &handle) != ESP_OK) return 0;
    std::uint32_t version = 0;
    (void)nvs_get_u32(handle, kMinimumVersionKey, &version);
    nvs_close(handle);
    return version;
}

bool store_minimum_version(std::uint32_t version) {
    nvs_handle_t handle{};
    if (nvs_open(kNvsNamespace, NVS_READWRITE, &handle) != ESP_OK) return false;
    const bool success = nvs_set_u32(handle, kMinimumVersionKey, version) == ESP_OK &&
                         nvs_commit(handle) == ESP_OK;
    nvs_close(handle);
    return success;
}

}  // namespace

EspOtaManager::EspOtaManager() { mbedtls_sha256_init(&sha_context_); }

EspOtaManager::~EspOtaManager() {
    abort();
    mbedtls_sha256_free(&sha_context_);
}

bool EspOtaManager::begin(std::uint32_t size, const Bytes& expected_sha256) {
    if (size == 0U || expected_sha256.size() != expected_hash_.size()) {
        last_error_ = "INVALID_IMAGE_METADATA";
        return false;
    }
    if (!signed_update_validation_enabled()) {
        last_error_ = "SIGNED_VALIDATION_REQUIRED";
        return false;
    }
    const auto* next_partition = esp_ota_get_next_update_partition(nullptr);
    if (next_partition == nullptr || size > next_partition->size) {
        last_error_ = "IMAGE_EXCEEDS_OTA_SLOT";
        return false;
    }
    // An explicit new BEGIN restarts an interrupted transfer at offset zero.
    // Validate metadata first; never change the running partition or NVS.
    abort();
    partition_ = next_partition;
    const auto begin_result = esp_ota_begin(partition_, OTA_WITH_SEQUENTIAL_WRITES, &handle_);
    if (begin_result != ESP_OK) {
        last_error_ = begin_result == ESP_ERR_OTA_ROLLBACK_INVALID_STATE
            ? "RUNNING_IMAGE_NOT_CONFIRMED" : "FLASH_BEGIN_FAILED";
        return false;
    }
    last_error_ = "NONE";
    expected_size_ = size;
    offset_ = 0;
    std::copy(expected_sha256.begin(), expected_sha256.end(), expected_hash_.begin());
    image_prefix_.clear();
    image_prefix_.reserve(kRequiredPrefix);
    mbedtls_sha256_starts(&sha_context_, 0);
    active_ = true;
    ESP_LOGI(kTag, "OTA session started: size=%u partition=%s",
             static_cast<unsigned>(size), partition_->label);
    return true;
}

bool EspOtaManager::write(std::uint32_t offset, const Bytes& chunk) {
    if (!active_ || offset != offset_ || chunk.empty() || offset_ > expected_size_ ||
        chunk.size() > expected_size_ - offset_) {
        ESP_LOGE(kTag, "OTA chunk rejected: active=%d got=%u expected=%u size=%u",
                 active_.load(), static_cast<unsigned>(offset), static_cast<unsigned>(offset_),
                 static_cast<unsigned>(chunk.size()));
        return false;
    }
    const esp_err_t write_result = esp_ota_write(handle_, chunk.data(), chunk.size());
    if (write_result != ESP_OK) {
        ESP_LOGE(kTag, "OTA flash write failed at %u: %s",
                 static_cast<unsigned>(offset), esp_err_to_name(write_result));
        return false;
    }
    if (image_prefix_.size() < kRequiredPrefix) {
        const std::size_t count = std::min(chunk.size(), kRequiredPrefix - image_prefix_.size());
        image_prefix_.insert(image_prefix_.end(), chunk.begin(), chunk.begin() + count);
    }
    mbedtls_sha256_update(&sha_context_, chunk.data(), chunk.size());
    offset_ += static_cast<std::uint32_t>(chunk.size());
    return true;
}

bool EspOtaManager::finish() {
    if (!active_ || offset_ != expected_size_ || image_prefix_.size() < kRequiredPrefix) return false;
    std::array<std::uint8_t, 32> actual_hash{};
    mbedtls_sha256_finish(&sha_context_, actual_hash.data());
    if (actual_hash != expected_hash_) {
        ESP_LOGE(kTag, "OTA SHA-256 mismatch");
        abort();
        return false;
    }
    esp_app_desc_t candidate{};
    std::memcpy(&candidate, image_prefix_.data() + kImageDescriptionOffset, sizeof(candidate));
    const esp_app_desc_t* running = esp_app_get_description();
    std::array<std::uint32_t, 3> candidate_version{};
    std::array<std::uint32_t, 3> running_version{};
    if (candidate.magic_word != ESP_APP_DESC_MAGIC_WORD || running == nullptr ||
        std::strncmp(candidate.project_name, running->project_name, sizeof(candidate.project_name)) != 0 ||
        !parse_version(candidate.version, candidate_version) ||
        !parse_version(running->version, running_version)) {
        ESP_LOGE(kTag, "OTA image identity or semantic version is invalid");
        abort();
        return false;
    }
    const std::uint32_t candidate_packed = pack_version(candidate_version);
    const std::uint32_t running_packed = pack_version(running_version);
    const std::uint32_t minimum_packed = std::max(running_packed, stored_minimum_version());
    if (candidate_packed == 0U || candidate_packed < minimum_packed) {
        ESP_LOGE(kTag, "OTA downgrade rejected");
        abort();
        return false;
    }

    // esp_ota_end validates the image and its embedded signature when signed
    // OTA validation is configured. Version fields are inside that signature.
    const esp_err_t end_result = esp_ota_end(handle_);
    if (end_result != ESP_OK) {
        ESP_LOGE(kTag, "OTA image/signature validation failed: %s", esp_err_to_name(end_result));
        handle_ = 0;
        active_ = false;
        return false;
    }
    handle_ = 0;
    if (!store_minimum_version(candidate_packed)) {
        ESP_LOGE(kTag, "OTA minimum version persistence failed");
        active_ = false;
        return false;
    }
    const esp_err_t boot_result = esp_ota_set_boot_partition(partition_);
    if (boot_result != ESP_OK) {
        ESP_LOGE(kTag, "OTA boot partition selection failed: %s", esp_err_to_name(boot_result));
        active_ = false;
        return false;
    }
    ESP_LOGI(kTag, "OTA image verified; boot partition=%s", partition_->label);
    active_ = false;
    return true;
}

void EspOtaManager::abort() {
    if (active_ && handle_ != 0) esp_ota_abort(handle_);
    handle_ = 0;
    partition_ = nullptr;
    expected_size_ = 0;
    offset_ = 0;
    image_prefix_.clear();
    active_ = false;
}

bool EspOtaManager::active() const { return active_; }

std::uint32_t EspOtaManager::next_offset() const { return offset_; }

std::uint32_t EspOtaManager::expected_size() const { return expected_size_; }

}  // namespace wmb
