#pragma once

#include "wmb/interfaces.hpp"

#include <atomic>
#include <cstdint>

namespace wmb {

class EspMatterBridge final : public IMatterBridge {
public:
    bool start() override;
    bool publish(const DeviceState& state) override;
    bool commissioned() const override;
    void set_commissioned(bool value);

private:
    std::uint16_t light_endpoint_id_{0};
    std::atomic_bool commissioned_{false};
};

}  // namespace wmb
