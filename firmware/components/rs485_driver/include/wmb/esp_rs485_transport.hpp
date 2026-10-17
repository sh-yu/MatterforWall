#pragma once

#include "wmb/interfaces.hpp"

#include "driver/uart.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"

#include <atomic>
#include <mutex>

namespace wmb {

class EspRs485Transport final : public IRs485Transport {
public:
    EspRs485Transport();
    ~EspRs485Transport() override;

    bool configure(const UartConfig& config) override;
    void set_receive_handler(std::function<void(Packet)> handler) override;
    bool queue_transmit(Bytes frame) override;
    void force_receive_only() override;
    Rs485Stats stats() const override;

    void set_transmit_authorized(bool authorized);

private:
    static void event_task_entry(void* context);
    static void tx_task_entry(void* context);
    void event_loop();
    void tx_loop();
    void set_direction(bool transmit);

    static constexpr uart_port_t kPort = UART_NUM_1;
    static constexpr int kTxGpio = 22;  // XIAO D4 -> transceiver DI
    static constexpr int kRxGpio = 23;  // XIAO D5 <- transceiver RO
    static constexpr int kDirectionGpio = 2;  // XIAO D2 -> DE + /RE
    static constexpr std::size_t kRxBufferSize = 4096;

    QueueHandle_t uart_events_{nullptr};
    QueueHandle_t tx_queue_{nullptr};
    TaskHandle_t event_task_{nullptr};
    TaskHandle_t tx_task_{nullptr};
    std::function<void(Packet)> receive_handler_;
    mutable std::mutex mutex_;
    Rs485Stats stats_{};
    std::atomic_bool configured_{false};
    std::atomic_bool transmit_authorized_{false};
    std::atomic<std::int64_t> last_rx_us_{0};
};

}  // namespace wmb
