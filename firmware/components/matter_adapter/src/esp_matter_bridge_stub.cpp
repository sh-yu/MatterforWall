#include "wmb/esp_matter_bridge.hpp"

namespace wmb {

bool EspMatterBridge::start() { return false; }
bool EspMatterBridge::publish(const DeviceState&) { return false; }
bool EspMatterBridge::commissioned() const { return false; }
void EspMatterBridge::set_commissioned(bool) {}

}  // namespace wmb
