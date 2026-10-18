#include "wmb/esp_ble_manager.hpp"

#include "wmb/ble_frame.hpp"

#include "esp_app_desc.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_system.h"
#include "host/ble_hs.h"
#include "host/ble_store.h"
#include "host/util/util.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "store/config/ble_store_config.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#if CONFIG_WMB_ENABLE_MATTER_EXPERIMENTAL
#include "platform/ConnectivityManager.h"
#include "platform/internal/BLEManager.h"
#include "platform/PlatformManager.h"
#include "setup_payload/OnboardingCodesUtil.h"
#include "esp_openthread.h"
#include "esp_openthread_lock.h"
#include "openthread/thread.h"
#endif

#include <algorithm>
#include <array>
#include <cstdio>
#include <cstring>
#include <utility>
#include <vector>

extern "C" void ble_store_config_init(void);

namespace wmb {

int ble_gap_event_bridge(::ble_gap_event* event, void* argument);
int ble_gatt_access_bridge(std::uint16_t connection, std::uint16_t attribute,
                           ::ble_gatt_access_ctxt* context, void* argument);

namespace {

constexpr const char* kTag = "wmb_ble";
constexpr const char* kDeviceName = "Wallpad Bridge";
constexpr std::uint16_t kNoConnection = BLE_HS_CONN_HANDLE_NONE;
constexpr std::size_t kNotificationPayload = 160;
constexpr std::uint8_t kOtaBegin = 1;
constexpr std::uint8_t kOtaFinish = 2;
constexpr std::uint8_t kOtaAbort = 3;
constexpr std::uint8_t kOtaQuery = 4;
constexpr std::uint8_t kOtaData = 5;
constexpr std::uint8_t kOtaOk = 0;
constexpr std::uint8_t kOtaInvalidRequest = 1;
constexpr std::uint8_t kOtaInvalidState = 2;
constexpr std::uint8_t kOtaWriteFailed = 3;
constexpr std::uint8_t kOtaVerificationFailed = 4;

// NimBLE stores 128-bit UUID bytes least-significant byte first.
const ble_uuid128_t kServiceUuid = BLE_UUID128_INIT(
    0x10, 0x8a, 0xf3, 0xe8, 0xc1, 0x94, 0x25, 0x9c,
    0x4d, 0x4f, 0x7b, 0x5f, 0x00, 0x00, 0xd1, 0x7b);
const ble_uuid128_t kDeviceInfoUuid = BLE_UUID128_INIT(
    0x10, 0x8a, 0xf3, 0xe8, 0xc1, 0x94, 0x25, 0x9c,
    0x4d, 0x4f, 0x7b, 0x5f, 0x01, 0x00, 0xd1, 0x7b);
const ble_uuid128_t kConfigurationUuid = BLE_UUID128_INIT(
    0x10, 0x8a, 0xf3, 0xe8, 0xc1, 0x94, 0x25, 0x9c,
    0x4d, 0x4f, 0x7b, 0x5f, 0x02, 0x00, 0xd1, 0x7b);
const ble_uuid128_t kPacketStreamUuid = BLE_UUID128_INIT(
    0x10, 0x8a, 0xf3, 0xe8, 0xc1, 0x94, 0x25, 0x9c,
    0x4d, 0x4f, 0x7b, 0x5f, 0x03, 0x00, 0xd1, 0x7b);
const ble_uuid128_t kCommandUuid = BLE_UUID128_INIT(
    0x10, 0x8a, 0xf3, 0xe8, 0xc1, 0x94, 0x25, 0x9c,
    0x4d, 0x4f, 0x7b, 0x5f, 0x04, 0x00, 0xd1, 0x7b);
const ble_uuid128_t kResponseUuid = BLE_UUID128_INIT(
    0x10, 0x8a, 0xf3, 0xe8, 0xc1, 0x94, 0x25, 0x9c,
    0x4d, 0x4f, 0x7b, 0x5f, 0x05, 0x00, 0xd1, 0x7b);
const ble_uuid128_t kDiagnosticsUuid = BLE_UUID128_INIT(
    0x10, 0x8a, 0xf3, 0xe8, 0xc1, 0x94, 0x25, 0x9c,
    0x4d, 0x4f, 0x7b, 0x5f, 0x06, 0x00, 0xd1, 0x7b);
const ble_uuid128_t kOtaControlUuid = BLE_UUID128_INIT(
    0x10, 0x8a, 0xf3, 0xe8, 0xc1, 0x94, 0x25, 0x9c,
    0x4d, 0x4f, 0x7b, 0x5f, 0x07, 0x00, 0xd1, 0x7b);
const ble_uuid128_t kOtaDataUuid = BLE_UUID128_INIT(
    0x10, 0x8a, 0xf3, 0xe8, 0xc1, 0x94, 0x25, 0x9c,
    0x4d, 0x4f, 0x7b, 0x5f, 0x08, 0x00, 0xd1, 0x7b);

std::uint16_t device_info_handle;
std::uint16_t configuration_handle;
std::uint16_t packet_stream_handle;
std::uint16_t command_handle;
std::uint16_t response_handle;
std::uint16_t diagnostics_handle;
std::uint16_t ota_control_handle;
std::uint16_t ota_data_handle;
std::uint8_t own_address_type;
EspBleManager* manager_instance;
bool shared_host = false;
#if CONFIG_WMB_ENABLE_MATTER_EXPERIMENTAL
ble_gap_event_listener shared_listener{};
#endif

int append_bytes(os_mbuf* output, const void* data, std::size_t size) {
    return os_mbuf_append(output, data, static_cast<std::uint16_t>(size)) == 0
               ? 0
               : BLE_ATT_ERR_INSUFFICIENT_RES;
}

Bytes flatten_mbuf(os_mbuf* input) {
    const auto size = OS_MBUF_PKTLEN(input);
    Bytes bytes(size);
    std::uint16_t copied = 0;
    if (ble_hs_mbuf_to_flat(input, bytes.data(), bytes.size(), &copied) != 0) return {};
    bytes.resize(copied);
    return bytes;
}

Bytes make_text_frame(BleMessageType type, std::uint16_t sequence, const char* text) {
    BleFrame frame{};
    frame.type = type;
    frame.sequence = sequence;
    frame.payload.assign(text, text + std::strlen(text));
    return encode_ble_frame(frame);
}

std::uint32_t read_u32_le(const Bytes& bytes, std::size_t offset) {
    return static_cast<std::uint32_t>(bytes[offset]) |
           (static_cast<std::uint32_t>(bytes[offset + 1U]) << 8U) |
           (static_cast<std::uint32_t>(bytes[offset + 2U]) << 16U) |
           (static_cast<std::uint32_t>(bytes[offset + 3U]) << 24U);
}

void append_u32_le(Bytes& bytes, std::uint32_t value) {
    for (unsigned shift = 0; shift < 32U; shift += 8U) {
        bytes.push_back(static_cast<std::uint8_t>((value >> shift) & 0xffU));
    }
}

bool send_custom(std::uint16_t connection, std::uint16_t attribute,
                 const Bytes& encoded, bool indication) {
    if (connection == kNoConnection || encoded.empty()) return false;
    os_mbuf* output = ble_hs_mbuf_from_flat(encoded.data(), encoded.size());
    if (output == nullptr) return false;
    const int result = indication
                           ? ble_gatts_indicate_custom(connection, attribute, output)
                           : ble_gatts_notify_custom(connection, attribute, output);
    return result == 0;
}

void advertise();

void host_task(void*) {
    ESP_LOGI(kTag, "NimBLE host task started");
    nimble_port_run();
    nimble_port_freertos_deinit();
}

void on_reset(int reason) { ESP_LOGE(kTag, "NimBLE reset; reason=%d", reason); }

void on_sync() {
    if (ble_hs_util_ensure_addr(0) != 0 ||
        ble_hs_id_infer_auto(0, &own_address_type) != 0) {
        ESP_LOGE(kTag, "BLE identity address unavailable");
        return;
    }
    advertise();
}

void advertise() {
    // Matter owns advertising and host lifecycle in shared mode.
    if (shared_host) return;
    ble_hs_adv_fields fields{};
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.uuids128 = const_cast<ble_uuid128_t*>(&kServiceUuid);
    fields.num_uuids128 = 1;
    fields.uuids128_is_complete = 1;
    if (ble_gap_adv_set_fields(&fields) != 0) {
        ESP_LOGE(kTag, "failed to set advertising fields");
        return;
    }

    ble_hs_adv_fields scan_response{};
    scan_response.name = reinterpret_cast<std::uint8_t*>(const_cast<char*>(kDeviceName));
    scan_response.name_len = std::strlen(kDeviceName);
    scan_response.name_is_complete = 1;
    if (ble_gap_adv_rsp_set_fields(&scan_response) != 0) {
        ESP_LOGE(kTag, "failed to set scan response");
        return;
    }

    ble_gap_adv_params parameters{};
    parameters.conn_mode = BLE_GAP_CONN_MODE_UND;
    parameters.disc_mode = BLE_GAP_DISC_MODE_GEN;
    const int result = ble_gap_adv_start(own_address_type, nullptr, BLE_HS_FOREVER,
                                         &parameters, ble_gap_event_bridge, nullptr);
    if (result == 0) ESP_LOGI(kTag, "advertising as %s", kDeviceName);
    else ESP_LOGE(kTag, "advertising failed; rc=%d", result);
}

const ble_gatt_svc_def services[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &kServiceUuid.u,
        .includes = nullptr,
        .characteristics = (ble_gatt_chr_def[]){
            {.uuid = &kDeviceInfoUuid.u, .access_cb = ble_gatt_access_bridge, .arg = nullptr,
             .descriptors = nullptr, .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY,
             .min_key_size = 0, .val_handle = &device_info_handle, .cpfd = nullptr},
            {.uuid = &kConfigurationUuid.u, .access_cb = ble_gatt_access_bridge, .arg = nullptr,
             .descriptors = nullptr,
             .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE |
                      BLE_GATT_CHR_F_READ_ENC | BLE_GATT_CHR_F_WRITE_ENC | BLE_GATT_CHR_F_INDICATE,
             .min_key_size = 16, .val_handle = &configuration_handle, .cpfd = nullptr},
            {.uuid = &kPacketStreamUuid.u, .access_cb = ble_gatt_access_bridge, .arg = nullptr,
             .descriptors = nullptr, .flags = BLE_GATT_CHR_F_NOTIFY | BLE_GATT_CHR_F_READ_ENC,
             .min_key_size = 16, .val_handle = &packet_stream_handle, .cpfd = nullptr},
            {.uuid = &kCommandUuid.u, .access_cb = ble_gatt_access_bridge, .arg = nullptr,
             .descriptors = nullptr, .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_ENC,
             .min_key_size = 16, .val_handle = &command_handle, .cpfd = nullptr},
            {.uuid = &kResponseUuid.u, .access_cb = ble_gatt_access_bridge, .arg = nullptr,
             .descriptors = nullptr, .flags = BLE_GATT_CHR_F_INDICATE | BLE_GATT_CHR_F_READ_ENC,
             .min_key_size = 16, .val_handle = &response_handle, .cpfd = nullptr},
            {.uuid = &kDiagnosticsUuid.u, .access_cb = ble_gatt_access_bridge, .arg = nullptr,
             .descriptors = nullptr,
             .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY | BLE_GATT_CHR_F_READ_ENC,
             .min_key_size = 16, .val_handle = &diagnostics_handle, .cpfd = nullptr},
            {.uuid = &kOtaControlUuid.u, .access_cb = ble_gatt_access_bridge, .arg = nullptr,
             .descriptors = nullptr,
             .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_ENC |
                      BLE_GATT_CHR_F_NOTIFY | BLE_GATT_CHR_F_INDICATE,
             .min_key_size = 16, .val_handle = &ota_control_handle, .cpfd = nullptr},
            {.uuid = &kOtaDataUuid.u, .access_cb = ble_gatt_access_bridge, .arg = nullptr,
             .descriptors = nullptr,
             .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP |
                      BLE_GATT_CHR_F_WRITE_ENC,
             .min_key_size = 16, .val_handle = &ota_data_handle, .cpfd = nullptr},
            {},
        },
    },
    {},
};

}  // namespace

int ble_gap_event_bridge(ble_gap_event* event, void*) {
    if (manager_instance == nullptr) return 0;
    switch (event->type) {
        case BLE_GAP_EVENT_CONNECT:
            if (event->connect.status == 0) {
                manager_instance->connection_handle_ = event->connect.conn_handle;
                manager_instance->encrypted_ = false;
                ESP_LOGI(kTag, "BLE central connected");
                const int security_result = shared_host ? 0 : ble_gap_security_initiate(event->connect.conn_handle);
                if (security_result != 0 && security_result != BLE_HS_EALREADY) {
                    ESP_LOGW(kTag, "security initiation failed; rc=%d", security_result);
                }
            } else {
                advertise();
            }
            break;
        case BLE_GAP_EVENT_DISCONNECT:
            if (!manager_instance->ota_finish_pending_ && manager_instance->ota_manager_ != nullptr &&
                manager_instance->ota_manager_->active()) {
                manager_instance->ota_manager_->abort();
            }
            manager_instance->connection_handle_ = kNoConnection;
            manager_instance->packet_subscribed_ = false;
            manager_instance->diagnostics_subscribed_ = false;
            manager_instance->ota_control_subscribed_ = false;
            manager_instance->encrypted_ = false;
            ESP_LOGI(kTag, "BLE central disconnected; reason=%d", event->disconnect.reason);
            advertise();
            break;
        case BLE_GAP_EVENT_ADV_COMPLETE:
            advertise();
            break;
        case BLE_GAP_EVENT_ENC_CHANGE: {
            ble_gap_conn_desc description{};
            if (event->enc_change.status == 0 &&
                ble_gap_conn_find(event->enc_change.conn_handle, &description) == 0) {
                manager_instance->encrypted_ = description.sec_state.encrypted != 0;
                ESP_LOGI(kTag, "BLE security encrypted=%d bonded=%d",
                         description.sec_state.encrypted, description.sec_state.bonded);
            }
            break;
        }
        case BLE_GAP_EVENT_SUBSCRIBE:
            if (event->subscribe.attr_handle == packet_stream_handle) {
                manager_instance->packet_subscribed_ = event->subscribe.cur_notify != 0;
            } else if (event->subscribe.attr_handle == diagnostics_handle) {
                manager_instance->diagnostics_subscribed_ = event->subscribe.cur_notify != 0;
            } else if (event->subscribe.attr_handle == ota_control_handle) {
                manager_instance->ota_control_subscribed_ =
                    event->subscribe.cur_notify != 0 || event->subscribe.cur_indicate != 0;
            }
            break;
        case BLE_GAP_EVENT_REPEAT_PAIRING: {
            ble_gap_conn_desc description{};
            if (ble_gap_conn_find(event->repeat_pairing.conn_handle, &description) == 0) {
                ble_store_util_delete_peer(&description.peer_id_addr);
            }
            return BLE_GAP_REPEAT_PAIRING_RETRY;
        }
        default:
            break;
    }
    return 0;
}

int ble_gatt_access_bridge(std::uint16_t, std::uint16_t attribute,
                           ble_gatt_access_ctxt* context, void*) {
    if (manager_instance == nullptr) return BLE_ATT_ERR_UNLIKELY;
    if (context->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        if (attribute == device_info_handle) {
            const esp_app_desc_t* description = esp_app_get_description();
            const char* thread_state = "disabled";
#if CONFIG_WMB_ENABLE_MATTER_EXPERIMENTAL
            thread_state = "busy";
            if (esp_openthread_lock_acquire(pdMS_TO_TICKS(50))) {
                auto* instance = esp_openthread_get_instance();
                const auto role = instance == nullptr ? OT_DEVICE_ROLE_DISABLED : otThreadGetDeviceRole(instance);
                thread_state = (role == OT_DEVICE_ROLE_CHILD || role == OT_DEVICE_ROLE_ROUTER || role == OT_DEVICE_ROLE_LEADER)
                                   ? "attached" : "detached";
                esp_openthread_lock_release();
            }
#endif
            char value[320];
            std::snprintf(value, sizeof(value),
                          "{\"name\":\"Wallpad Bridge\",\"firmware\":\"%s\","
                          "\"mode\":\"PASSIVE_SNIFFER\",\"txEnabled\":false,\"matter\":\"%s\",\"thread\":\"%s\"}",
                          description == nullptr ? "unknown" : description->version,
                          shared_host ? "enabled" : "disabled", thread_state);
            const auto encoded = make_text_frame(BleMessageType::DeviceInfo, 0, value);
            return append_bytes(context->om, encoded.data(), encoded.size());
        }
        if (attribute == configuration_handle) {
#if CONFIG_WMB_ENABLE_MATTER_EXPERIMENTAL
            char manual_code[32]{};
            chip::MutableCharSpan manual_span(manual_code, sizeof(manual_code));
            chip::DeviceLayer::PlatformMgr().LockChipStack();
            const auto code_result = GetManualPairingCode(manual_span, chip::RendezvousInformationFlags(chip::RendezvousInformationFlag::kBLE));
            chip::DeviceLayer::PlatformMgr().UnlockChipStack();
            if (code_result != CHIP_NO_ERROR) return BLE_ATT_ERR_UNLIKELY;
            char value[224]{};
            std::snprintf(value, sizeof(value),
                          "{\"baud\":9600,\"txEnabled\":false,\"matterManualCode\":\"%s\"}", manual_code);
#else
            constexpr char value[] =
                "{\"baud\":9600,\"dataBits\":8,\"parity\":\"NONE\","
                "\"stopBits\":1,\"frameTimeoutMs\":20,\"txEnabled\":false}";
#endif
            const auto encoded = make_text_frame(BleMessageType::Configuration, 0, value);
            return append_bytes(context->om, encoded.data(), encoded.size());
        }
        if (attribute == diagnostics_handle) {
            const auto stats = manager_instance->stats_provider_
                                   ? manager_instance->stats_provider_()
                                   : Rs485Stats{};
            char value[320];
            std::snprintf(value, sizeof(value),
                          "{\"freeHeap\":%u,\"minimumFreeHeap\":%u,"
                          "\"rxFrames\":%llu,\"rxBytes\":%llu,"
                          "\"txFrames\":%llu,\"framingErrors\":%llu,"
                          "\"parityErrors\":%llu,\"bufferOverruns\":%llu,"
                          "\"droppedTx\":%llu}",
                          static_cast<unsigned>(esp_get_free_heap_size()),
                          static_cast<unsigned>(esp_get_minimum_free_heap_size()),
                          static_cast<unsigned long long>(stats.rx_frames),
                          static_cast<unsigned long long>(stats.rx_bytes),
                          static_cast<unsigned long long>(stats.tx_frames),
                          static_cast<unsigned long long>(stats.framing_errors),
                          static_cast<unsigned long long>(stats.parity_errors),
                          static_cast<unsigned long long>(stats.buffer_overruns),
                          static_cast<unsigned long long>(stats.dropped_tx));
            const auto encoded = make_text_frame(BleMessageType::Diagnostics, 0, value);
            return append_bytes(context->om, encoded.data(), encoded.size());
        }
        return BLE_ATT_ERR_READ_NOT_PERMITTED;
    }

    if (context->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        const Bytes bytes = flatten_mbuf(context->om);
        const auto frame = decode_ble_frame(bytes);
        if (!frame) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;

        if (attribute == command_handle || attribute == configuration_handle) {
            constexpr char error[] = "AUTHORIZATION_REQUIRED";
            manager_instance->notify_response(
                frame->sequence, Bytes(error, error + sizeof(error) - 1U));
            return 0;
        }
        if (!manager_instance->encrypted_ || manager_instance->ota_manager_ == nullptr) {
            return BLE_ATT_ERR_INSUFFICIENT_AUTHEN;
        }
        // finish() runs on a worker. Do not mutate/read its OTA state in parallel,
        // including the interval between boot selection and reboot.
        if (manager_instance->ota_finish_pending_) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        if (attribute == ota_control_handle && frame->type == BleMessageType::OtaControl &&
            !frame->payload.empty()) {
            const std::uint8_t operation = frame->payload[0];
            if (operation == kOtaBegin) {
                if (frame->payload.size() != 37U) {
                    manager_instance->notify_ota_status(frame->sequence, operation,
                                                        kOtaInvalidRequest, "INVALID_BEGIN");
                    return 0;
                }
                manager_instance->force_receive_only_();
                const std::uint32_t size = read_u32_le(frame->payload, 1U);
                const Bytes hash(frame->payload.begin() + 5U, frame->payload.end());
                const bool begun = manager_instance->ota_manager_->begin(size, hash);
                manager_instance->notify_ota_status(
                    frame->sequence, operation, begun ? kOtaOk : kOtaInvalidState,
                    begun ? "READY" : manager_instance->ota_manager_->last_error());
                return 0;
            }
            if (operation == kOtaAbort) {
                manager_instance->ota_manager_->abort();
                manager_instance->notify_ota_status(frame->sequence, operation, kOtaOk, "ABORTED");
                return 0;
            }
            if (operation == kOtaQuery) {
                manager_instance->notify_ota_status(frame->sequence, operation, kOtaOk,
                                                    manager_instance->ota_manager_->active()
                                                        ? "IN_PROGRESS" : "IDLE");
                return 0;
            }
            if (operation == kOtaFinish) {
                if (!manager_instance->ota_manager_->active() ||
                    manager_instance->ota_finish_pending_.exchange(true)) {
                    manager_instance->notify_ota_status(frame->sequence, operation,
                                                        kOtaInvalidState, "NOT_ACTIVE");
                    return 0;
                }
                manager_instance->ota_finish_sequence_ = frame->sequence;
                if (xTaskCreate(EspBleManager::ota_finish_task, "ota_finish", 4096,
                                manager_instance, 5, nullptr) != pdPASS) {
                    manager_instance->ota_finish_pending_ = false;
                    manager_instance->notify_ota_status(frame->sequence, operation,
                                                        kOtaVerificationFailed, "TASK_FAILED");
                }
                return 0;
            }
            manager_instance->notify_ota_status(frame->sequence, operation,
                                                kOtaInvalidRequest, "UNKNOWN_OPERATION");
            return 0;
        }
        if (attribute == ota_data_handle && frame->type == BleMessageType::OtaData) {
            if (frame->payload.size() <= 4U || !manager_instance->ota_manager_->active()) {
                manager_instance->notify_ota_status(frame->sequence, kOtaData,
                                                    kOtaInvalidState, "NOT_ACTIVE");
                return 0;
            }
            const std::uint32_t offset = read_u32_le(frame->payload, 0U);
            const Bytes chunk(frame->payload.begin() + 4U, frame->payload.end());
            if (!manager_instance->ota_manager_->write(offset, chunk)) {
                manager_instance->notify_ota_status(frame->sequence, kOtaData,
                                                    kOtaWriteFailed, "WRITE_REJECTED");
                return BLE_ATT_ERR_UNLIKELY;
            }
            manager_instance->notify_ota_status(frame->sequence, kOtaData, kOtaOk, "WRITTEN");
            return 0;
        }
        return BLE_ATT_ERR_WRITE_NOT_PERMITTED;
    }
    return BLE_ATT_ERR_UNLIKELY;
}

EspBleManager::EspBleManager() = default;

EspBleManager::~EspBleManager() {
    if (manager_instance == this) manager_instance = nullptr;
}

bool EspBleManager::start(IOtaManager& ota_manager, std::function<void()> force_receive_only,
                          std::function<Rs485Stats()> stats_provider) {
    if (started_ || manager_instance != nullptr) return false;
    stats_provider_ = std::move(stats_provider);
    force_receive_only_ = std::move(force_receive_only);
    ota_manager_ = &ota_manager;
    manager_instance = this;

#if CONFIG_WMB_ENABLE_MATTER_EXPERIMENTAL
    shared_host = true;
    std::vector<ble_gatt_svc_def> extra_services{services[0]};
    if (chip::DeviceLayer::Internal::BLEMgrImpl().ConfigureExtraServices(extra_services, false) != CHIP_NO_ERROR) {
        manager_instance = nullptr;
        return false;
    }
    // Scan response contains the management UUID and a short discoverable name;
    // Matter keeps its own commissioning service advertisement unchanged.
    std::array<std::uint8_t, 23> response{0x11, 0x07};
    std::copy(std::begin(kServiceUuid.value), std::end(kServiceUuid.value), response.begin() + 2);
    response[18] = 4; response[19] = 0x09;
    response[20] = 'W'; response[21] = 'M'; response[22] = 'B';
    if (chip::DeviceLayer::Internal::BLEMgrImpl().ConfigureScanResponseData(chip::ByteSpan(response.data(), response.size())) != CHIP_NO_ERROR) {
        manager_instance = nullptr;
        return false;
    }
    started_ = true;
    return true;
#endif

    if (nimble_port_init() != ESP_OK) {
        manager_instance = nullptr;
        return false;
    }
    ble_hs_cfg.reset_cb = on_reset;
    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;
    ble_hs_cfg.sm_bonding = 1;
    ble_hs_cfg.sm_sc = 1;
    ble_hs_cfg.sm_mitm = 0;
    ble_hs_cfg.sm_io_cap = BLE_HS_IO_NO_INPUT_OUTPUT;
    ble_hs_cfg.sm_our_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;

    ble_svc_gap_init();
    ble_svc_gatt_init();
    if (ble_gatts_count_cfg(services) != 0 || ble_gatts_add_svcs(services) != 0 ||
        ble_svc_gap_device_name_set(kDeviceName) != 0) {
        manager_instance = nullptr;
        return false;
    }
    ble_store_config_init();
    nimble_port_freertos_init(host_task);
    started_ = true;
    return true;
}

bool EspBleManager::attach_shared_host() {
#if CONFIG_WMB_ENABLE_MATTER_EXPERIMENTAL
    for (unsigned attempt = 0; attempt < 100U && !ble_hs_synced(); ++attempt) {
        vTaskDelay(pdMS_TO_TICKS(100));
    }
    if (!ble_hs_synced() || ble_gap_event_listener_register(&shared_listener, ble_gap_event_bridge, nullptr) != 0) return false;
    // Do not reclaim BLE RAM after commissioning: management OTA must remain
    // available on the same initialized host.
    chip::DeviceLayer::PlatformMgr().LockChipStack();
    const auto result = chip::DeviceLayer::ConnectivityMgr().SetBLEAdvertisingEnabled(true);
    chip::DeviceLayer::PlatformMgr().UnlockChipStack();
    return result == CHIP_NO_ERROR;
#else
    return true;
#endif
}

bool EspBleManager::notify_packet(const Packet& packet) {
    const auto connection = connection_handle_.load();
    if (!started_ || connection == kNoConnection || !packet_subscribed_ || !encrypted_ ||
        ota_finish_pending_ || (ota_manager_ != nullptr && ota_manager_->active())) {
        return false;
    }

    BleFrame frame{};
    frame.type = BleMessageType::PacketStream;
    frame.sequence = sequence_++;
    frame.payload.reserve(10U + packet.data.size());
    for (unsigned shift = 0; shift < 64U; shift += 8U) {
        frame.payload.push_back(static_cast<std::uint8_t>((packet.timestamp_us >> shift) & 0xffU));
    }
    frame.payload.push_back(packet.direction == Direction::Rx ? 0U : 1U);
    frame.payload.push_back(packet.synthetic_test_data ? 1U : 0U);
    frame.payload.insert(frame.payload.end(), packet.data.begin(), packet.data.end());

    bool sent = true;
    for (const auto& fragment : fragment_ble_frame(frame, kNotificationPayload)) {
        const Bytes encoded = encode_ble_frame(fragment);
        sent = send_custom(connection, packet_stream_handle, encoded, false) && sent;
    }
    return sent;
}

bool EspBleManager::notify_response(std::uint16_t sequence, const Bytes& payload) {
    BleFrame frame{};
    frame.type = BleMessageType::Error;
    frame.sequence = sequence;
    frame.payload = payload;
    return send_custom(connection_handle_, response_handle, encode_ble_frame(frame), true);
}

bool EspBleManager::notify_ota_status(std::uint16_t sequence, std::uint8_t operation,
                                      std::uint8_t status, const char* detail) {
    if (!ota_control_subscribed_ || !encrypted_) return false;
    BleFrame frame{};
    frame.type = BleMessageType::OtaControl;
    frame.sequence = sequence;
    frame.payload = {operation, status};
    const std::uint32_t next = ota_manager_ == nullptr ? 0U : ota_manager_->next_offset();
    const std::uint32_t size = ota_manager_ == nullptr ? 0U : ota_manager_->expected_size();
    append_u32_le(frame.payload, next);
    append_u32_le(frame.payload, size);
    if (detail != nullptr) frame.payload.insert(frame.payload.end(), detail, detail + std::strlen(detail));
    return send_custom(connection_handle_, ota_control_handle, encode_ble_frame(frame), false);
}

void EspBleManager::ota_finish_task(void* argument) {
    auto* manager = static_cast<EspBleManager*>(argument);
    const bool finished = manager != nullptr && manager->ota_manager_ != nullptr &&
                          manager->ota_manager_->finish();
    if (manager != nullptr) {
        manager->notify_ota_status(
            manager->ota_finish_sequence_, kOtaFinish,
            finished ? kOtaOk : kOtaVerificationFailed,
            finished ? "VERIFIED_REBOOTING" : "VERIFY_FAILED");
        if (!finished) manager->ota_finish_pending_ = false;
    }
    if (finished) {
        vTaskDelay(pdMS_TO_TICKS(1500));
        esp_restart();
    }
    vTaskDelete(nullptr);
}

bool EspBleManager::administrator_authenticated() const { return false; }

}  // namespace wmb
