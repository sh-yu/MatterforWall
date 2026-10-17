#pragma once

#include "wmb/interfaces.hpp"

#include <string>
#include <unordered_set>

namespace wmb {

struct SafetyContext {
    bool boot_complete{false};
    bool ota_active{false};
    bool bus_healthy{false};
    bool administrator_authenticated{false};
    bool profile_control_verified{false};
    bool explicit_tx_enabled{false};
};

class SafetyPolicy {
public:
    SafetyPolicy();
    void allow_verified_command(std::string command);
    bool may_enter_active_control(const SafetyContext& context) const;
    bool may_transmit(const std::string& command, const SafetyContext& context) const;

private:
    std::unordered_set<std::string> allow_list_;
};

}  // namespace wmb

