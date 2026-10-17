#pragma once

#include "wmb/interfaces.hpp"

#include <atomic>
#include <functional>

struct ble_gap_event;
struct ble_gatt_access_ctxt;

namespace wmb {

class EspBleManager final : public IBleTransport {
public:
    EspBleManager();
    ~EspBleManager() override;

    bool start(IOtaManager& ota_manager, std::function<void()> force_receive_only,
               std::function<Rs485Stats()> stats_provider);
    bool notify_packet(const Packet& packet) override;
    bool notify_response(std::uint16_t sequence, const Bytes& payload) override;
    bool administrator_authenticated() const override;

private:
    std::function<Rs485Stats()> stats_provider_;
    std::function<void()> force_receive_only_;
    IOtaManager* ota_manager_{nullptr};
    std::atomic<std::uint16_t> sequence_{1};
    std::atomic<std::uint16_t> connection_handle_{0xffff};
    std::atomic_bool packet_subscribed_{false};
    std::atomic_bool diagnostics_subscribed_{false};
    std::atomic_bool ota_control_subscribed_{false};
    std::atomic_bool ota_finish_pending_{false};
    std::atomic<std::uint16_t> ota_finish_sequence_{0};
    std::atomic_bool encrypted_{false};
    bool started_{false};

    bool notify_ota_status(std::uint16_t sequence, std::uint8_t operation,
                           std::uint8_t status, const char* detail);
    static void ota_finish_task(void* argument);

    friend int ble_gap_event_bridge(::ble_gap_event*, void*);
    friend int ble_gatt_access_bridge(std::uint16_t, std::uint16_t,
                                      ::ble_gatt_access_ctxt*, void*);
};

}  // namespace wmb
