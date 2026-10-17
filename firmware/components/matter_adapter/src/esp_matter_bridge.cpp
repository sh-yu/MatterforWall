#include "wmb/esp_matter_bridge.hpp"

#include "esp_log.h"
#include "esp_matter.h"
#include "platform/ESP32/OpenthreadLauncher.h"

namespace wmb {
namespace {

constexpr const char* kTag = "wmb_matter";
EspMatterBridge* active_bridge = nullptr;
std::atomic_bool internal_attribute_update{false};

esp_err_t identify_callback(esp_matter::identification::callback_type_t,
                            std::uint16_t, std::uint8_t, std::uint8_t, void*) {
    return ESP_OK;
}

esp_err_t attribute_callback(esp_matter::attribute::callback_type_t type,
                             std::uint16_t, std::uint32_t cluster_id, std::uint32_t attribute_id,
                             esp_matter_attr_val_t*, void*) {
    // Commands are forwarded to the verified RS485 encoder only after the
    // application safety policy approves them. Until then updates are rejected
    // by the integration layer and this endpoint is observation-only.
    if (type == esp_matter::attribute::PRE_UPDATE &&
        cluster_id == chip::app::Clusters::OnOff::Id &&
        attribute_id == chip::app::Clusters::OnOff::Attributes::OnOff::Id &&
        !internal_attribute_update) {
        return ESP_ERR_NOT_SUPPORTED;
    }
    return ESP_OK;
}

void event_callback(const ChipDeviceEvent* event, intptr_t) {
    if (active_bridge == nullptr) return;
    if (event->Type == chip::DeviceLayer::DeviceEventType::kCommissioningComplete) {
        active_bridge->set_commissioned(true);
        ESP_LOGI(kTag, "commissioning complete");
    }
}

}  // namespace

bool EspMatterBridge::start() {
    using namespace esp_matter;
    active_bridge = this;
    node::config_t node_config{};
    node_t* node = node::create(&node_config, attribute_callback, identify_callback);
    if (node == nullptr) return false;

    endpoint_t* aggregator_endpoint = endpoint::aggregator::create(
        node, ENDPOINT_FLAG_NONE, nullptr);
    if (aggregator_endpoint == nullptr) return false;

    endpoint::on_off_light::config_t light_config{};
    endpoint_t* light = endpoint::on_off_light::create(
        node, &light_config, ENDPOINT_FLAG_NONE, nullptr);
    if (light == nullptr) return false;
    light_endpoint_id_ = endpoint::get_id(light);

    esp_openthread_platform_config_t thread_config = {
        .radio_config = ESP_OPENTHREAD_DEFAULT_RADIO_CONFIG(),
        .host_config = ESP_OPENTHREAD_DEFAULT_HOST_CONFIG(),
        .port_config = ESP_OPENTHREAD_DEFAULT_PORT_CONFIG(),
    };
    set_openthread_platform_config(&thread_config);
    const auto result = esp_matter::start(event_callback);
    if (result != ESP_OK) return false;
    ESP_LOGI(kTag, "aggregator and On/Off Light endpoint %u created", light_endpoint_id_);
    return true;
}

bool EspMatterBridge::publish(const DeviceState& state) {
    if (light_endpoint_id_ == 0U || state.kind != "light.on_off") return false;
    auto value = esp_matter_bool(state.value == "on");
    internal_attribute_update = true;
    const auto result = esp_matter::attribute::update(
               light_endpoint_id_, chip::app::Clusters::OnOff::Id,
               chip::app::Clusters::OnOff::Attributes::OnOff::Id, &value);
    internal_attribute_update = false;
    return result == ESP_OK;
}

bool EspMatterBridge::commissioned() const { return commissioned_; }

void EspMatterBridge::set_commissioned(bool value) { commissioned_ = value; }

}  // namespace wmb
