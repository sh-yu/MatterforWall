#include "wmb/safety_policy.hpp"

#include <utility>

namespace wmb {

SafetyPolicy::SafetyPolicy() = default;

void SafetyPolicy::allow_verified_command(std::string command) {
    if (command != "gas.open" && command.rfind("elevator.", 0U) != 0U) {
        allow_list_.insert(std::move(command));
    }
}

bool SafetyPolicy::may_enter_active_control(const SafetyContext& context) const {
    return context.boot_complete && !context.ota_active && context.bus_healthy &&
           context.administrator_authenticated && context.profile_control_verified &&
           context.explicit_tx_enabled;
}

bool SafetyPolicy::may_transmit(const std::string& command,
                                const SafetyContext& context) const {
    return may_enter_active_control(context) && command != "gas.open" &&
           command.rfind("elevator.", 0U) != 0U && allow_list_.count(command) == 1U;
}

}  // namespace wmb

