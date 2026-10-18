#include "wmb/esp_rs485_transport.hpp"
#include "wmb/esp_ble_manager.hpp"
#include "wmb/esp_matter_bridge.hpp"
#include "wmb/esp_ota_manager.hpp"
#include "wmb/packet_analyzer.hpp"
#include "wmb/safety_policy.hpp"
#include "wmb/elevator_candidates.hpp"

#include "esp_log.h"
#include "esp_ota_ops.h"
#include "nvs_flash.h"

#include <memory>

namespace {

constexpr const char* kTag = "wallpad_bridge";
std::unique_ptr<wmb::EspRs485Transport> transport;
std::unique_ptr<wmb::PacketAnalyzer> analyzer;
std::unique_ptr<wmb::EspBleManager> ble_manager;
std::unique_ptr<wmb::EspOtaManager> ota_manager;
std::unique_ptr<wmb::EspMatterBridge> matter;
wmb::ElevatorCandidateScanner elevator_scanner;

void rollback_failed_update() {
    const auto* running = esp_ota_get_running_partition();
    esp_ota_img_states_t state{};
    if (running != nullptr && esp_ota_get_state_partition(running, &state) == ESP_OK &&
        state == ESP_OTA_IMG_PENDING_VERIFY) {
        esp_ota_mark_app_invalid_rollback_and_reboot();
    }
}

}  // namespace

extern "C" void app_main() {
    esp_err_t nvs_result = nvs_flash_init();
    if (nvs_result == ESP_ERR_NVS_NO_FREE_PAGES ||
        nvs_result == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        // Do not erase NVS here: it holds Matter fabric data, OTA state, and
        // user configuration. Recovery must be an explicit physical/admin
        // maintenance action, never an unattended boot side effect.
        ESP_LOGE(kTag, "NVS recovery required; preserving Matter fabric and user configuration");
        rollback_failed_update();
        return;
    }
    ESP_ERROR_CHECK(nvs_result);

    analyzer = std::make_unique<wmb::PacketAnalyzer>();
    transport = std::make_unique<wmb::EspRs485Transport>();
    ota_manager = std::make_unique<wmb::EspOtaManager>();
    ble_manager = std::make_unique<wmb::EspBleManager>();
    if (!ble_manager->start(*ota_manager,
                            [] { if (transport != nullptr) transport->force_receive_only(); },
                            [] { return transport == nullptr ? wmb::Rs485Stats{} : transport->stats(); })) {
        ESP_LOGE(kTag, "BLE management service initialization failed");
        rollback_failed_update();
        return;
    }
    transport->force_receive_only();
    transport->set_receive_handler([](wmb::Packet packet) {
        analyzer->observe(packet);
        for (const auto byte : packet.data) {
            const auto candidate = elevator_scanner.feed(byte);
            if (candidate != wmb::ElevatorCandidate::None) {
                ESP_LOGI(kTag, "HARDWARE_TEST_PENDING elevator candidate=%s; no state inference",
                         candidate == wmb::ElevatorCandidate::Kocom21ChecksumMatch
                             ? "option1_checksum_match" : "option2_checksum_unknown");
            }
        }
        ESP_LOGI(kTag, "RX len=%u groups=%u", static_cast<unsigned>(packet.data.size()),
                 static_cast<unsigned>(analyzer->unique_group_count()));
        if (ble_manager != nullptr) ble_manager->notify_packet(packet);
    });

    const wmb::UartConfig uart{9600, 8, wmb::Parity::None, 1, 20};
    if (!transport->configure(uart)) {
        ESP_LOGE(kTag, "RS485 initialization failed; TX remains disabled");
        rollback_failed_update();
        return;
    }
    ESP_LOGW(kTag, "PASSIVE_SNIFFER active; KOCOM protocol is unverified");
#if CONFIG_WMB_ENABLE_MATTER_EXPERIMENTAL
    matter = std::make_unique<wmb::EspMatterBridge>();
    if (!matter->start()) {
        ESP_LOGE(kTag, "Matter initialization failed");
        rollback_failed_update();
        return;
    }
    if (!ble_manager->attach_shared_host()) {
        ESP_LOGE(kTag, "Matter BLE management attachment failed; preserve rollback");
        rollback_failed_update();
        return;
    }
#else
    ESP_LOGW(kTag, "Matter adapter disabled; HARDWARE_TEST_PENDING");
#endif
    // Critical self-test reached: NVS and passive UART are operational.
    const esp_partition_t* running = esp_ota_get_running_partition();
    esp_ota_img_states_t state{};
    if (running != nullptr && esp_ota_get_state_partition(running, &state) == ESP_OK &&
        state == ESP_OTA_IMG_PENDING_VERIFY) {
        ESP_ERROR_CHECK(esp_ota_mark_app_valid_cancel_rollback());
    }
}
