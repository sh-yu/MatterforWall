#include "wmb/esp_rs485_transport.hpp"

#include "driver/gpio.h"
#include "esp_check.h"
#include "esp_log.h"
#include "esp_timer.h"

#include <algorithm>
#include <new>
#include <utility>

namespace wmb {
namespace {

constexpr const char* kTag = "wmb_rs485";

uart_parity_t to_idf_parity(Parity parity) {
    switch (parity) {
        case Parity::Even: return UART_PARITY_EVEN;
        case Parity::Odd: return UART_PARITY_ODD;
        case Parity::None: default: return UART_PARITY_DISABLE;
    }
}

}  // namespace

EspRs485Transport::EspRs485Transport() = default;

EspRs485Transport::~EspRs485Transport() {
    transmit_authorized_ = false;
    force_receive_only();
    if (event_task_ != nullptr) vTaskDelete(event_task_);
    if (tx_task_ != nullptr) vTaskDelete(tx_task_);
    if (configured_) uart_driver_delete(kPort);
    if (tx_queue_ != nullptr) vQueueDelete(tx_queue_);
}

bool EspRs485Transport::configure(const UartConfig& config) {
    if (configured_ || (config.data_bits != 7U && config.data_bits != 8U) ||
        (config.stop_bits != 1U && config.stop_bits != 2U)) {
        return false;
    }

    // Fail-safe: put tied DE+/RE in receive before UART installation.
    const gpio_config_t direction_config{
        .pin_bit_mask = 1ULL << kDirectionGpio,
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_ENABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    if (gpio_config(&direction_config) != ESP_OK ||
        gpio_set_level(static_cast<gpio_num_t>(kDirectionGpio), 0) != ESP_OK) {
        return false;
    }

    uart_config_t uart_config{
        .baud_rate = static_cast<int>(config.baud),
        .data_bits = config.data_bits == 7U ? UART_DATA_7_BITS : UART_DATA_8_BITS,
        .parity = to_idf_parity(config.parity),
        .stop_bits = config.stop_bits == 2U ? UART_STOP_BITS_2 : UART_STOP_BITS_1,
        .flow_ctrl = UART_HW_FLOWCTRL_DISABLE,
        .rx_flow_ctrl_thresh = 0,
        .source_clk = UART_SCLK_DEFAULT,
        .flags = {},
    };
    if (uart_param_config(kPort, &uart_config) != ESP_OK ||
        uart_set_pin(kPort, kTxGpio, kRxGpio, UART_PIN_NO_CHANGE,
                     UART_PIN_NO_CHANGE) != ESP_OK ||
        uart_driver_install(kPort, kRxBufferSize, 0, 24, &uart_events_, 0) != ESP_OK) {
        return false;
    }
    const auto timeout_symbols = std::clamp<std::uint32_t>(
        (config.frame_timeout_ms * config.baud + 10999U) / 11000U, 1U, 126U);
    if (uart_set_mode(kPort, UART_MODE_UART) != ESP_OK ||
        uart_set_rx_timeout(kPort, timeout_symbols) != ESP_OK) {
        uart_driver_delete(kPort);
        return false;
    }
    tx_queue_ = xQueueCreate(8, sizeof(Bytes*));
    if (tx_queue_ == nullptr) {
        uart_driver_delete(kPort);
        return false;
    }
    configured_ = true;
    xTaskCreate(event_task_entry, "rs485_rx", 4096, this, 10, &event_task_);
    xTaskCreate(tx_task_entry, "rs485_tx", 3072, this, 9, &tx_task_);
    ESP_LOGI(kTag, "passive RX ready: TX=D4/GPIO22 RX=D5/GPIO23 DE=D2/GPIO2");
    return event_task_ != nullptr && tx_task_ != nullptr;
}

void EspRs485Transport::set_receive_handler(std::function<void(Packet)> handler) {
    std::lock_guard<std::mutex> lock(mutex_);
    receive_handler_ = std::move(handler);
}

bool EspRs485Transport::queue_transmit(Bytes frame) {
    if (!configured_ || !transmit_authorized_ || frame.empty() || frame.size() > 1024U) {
        std::lock_guard<std::mutex> lock(mutex_);
        ++stats_.dropped_tx;
        return false;
    }
    auto* queued = new (std::nothrow) Bytes(std::move(frame));
    if (queued == nullptr || xQueueSend(tx_queue_, &queued, 0) != pdPASS) {
        delete queued;
        std::lock_guard<std::mutex> lock(mutex_);
        ++stats_.dropped_tx;
        return false;
    }
    return true;
}

void EspRs485Transport::force_receive_only() {
    transmit_authorized_ = false;
    set_direction(false);
}

Rs485Stats EspRs485Transport::stats() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return stats_;
}

void EspRs485Transport::set_transmit_authorized(bool authorized) {
    transmit_authorized_ = authorized;
    if (!authorized) force_receive_only();
}

void EspRs485Transport::event_task_entry(void* context) {
    static_cast<EspRs485Transport*>(context)->event_loop();
}

void EspRs485Transport::tx_task_entry(void* context) {
    static_cast<EspRs485Transport*>(context)->tx_loop();
}

void EspRs485Transport::event_loop() {
    uart_event_t event{};
    Bytes buffer(kRxBufferSize);
    while (true) {
        if (xQueueReceive(uart_events_, &event, portMAX_DELAY) != pdTRUE) continue;
        if (event.type == UART_DATA) {
            const auto wanted = std::min<std::size_t>(event.size, buffer.size());
            const int received = uart_read_bytes(kPort, buffer.data(), wanted, pdMS_TO_TICKS(20));
            if (received > 0) {
                const auto now = esp_timer_get_time();
                last_rx_us_ = now;
                Packet packet{static_cast<std::uint64_t>(now), Direction::Rx,
                              Bytes(buffer.begin(), buffer.begin() + received), false};
                std::function<void(Packet)> handler;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    ++stats_.rx_frames;
                    stats_.rx_bytes += static_cast<std::uint64_t>(received);
                    handler = receive_handler_;
                }
                if (handler) handler(std::move(packet));
            }
        } else if (event.type == UART_FIFO_OVF || event.type == UART_BUFFER_FULL) {
            uart_flush_input(kPort);
            xQueueReset(uart_events_);
            std::lock_guard<std::mutex> lock(mutex_);
            ++stats_.buffer_overruns;
        } else if (event.type == UART_FRAME_ERR || event.type == UART_BREAK) {
            std::lock_guard<std::mutex> lock(mutex_);
            ++stats_.framing_errors;
        } else if (event.type == UART_PARITY_ERR) {
            std::lock_guard<std::mutex> lock(mutex_);
            ++stats_.parity_errors;
        }
    }
}

void EspRs485Transport::tx_loop() {
    while (true) {
        Bytes* frame = nullptr;
        if (xQueueReceive(tx_queue_, &frame, portMAX_DELAY) != pdTRUE || frame == nullptr) continue;
        if (!transmit_authorized_) {
            delete frame;
            continue;
        }
        // Require 5 ms of observed bus silence and give up after 100 ms. This
        // is only a collision-avoidance guard; protocol arbitration is still
        // required before ACTIVE_CONTROL can ever be enabled.
        const auto deadline = esp_timer_get_time() + 100000;
        while (esp_timer_get_time() - last_rx_us_.load() < 5000 &&
               esp_timer_get_time() < deadline) {
            vTaskDelay(pdMS_TO_TICKS(1));
        }
        if (!transmit_authorized_ || esp_timer_get_time() >= deadline) {
            std::lock_guard<std::mutex> lock(mutex_);
            ++stats_.dropped_tx;
            delete frame;
            continue;
        }
        set_direction(true);
        const int written = uart_write_bytes(kPort, frame->data(), frame->size());
        const bool drained = written == static_cast<int>(frame->size()) &&
                             uart_wait_tx_done(kPort, pdMS_TO_TICKS(100)) == ESP_OK;
        set_direction(false);
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (drained) ++stats_.tx_frames;
            else ++stats_.dropped_tx;
        }
        delete frame;
    }
}

void EspRs485Transport::set_direction(bool transmit) {
    gpio_set_level(static_cast<gpio_num_t>(kDirectionGpio), transmit ? 1 : 0);
}

}  // namespace wmb
