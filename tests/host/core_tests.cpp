#include "wmb/ble_frame.hpp"
#include "wmb/packet_analyzer.hpp"
#include "wmb/safety_policy.hpp"

#include <algorithm>
#include <cstdlib>
#include <iostream>
#include <string>

namespace {

int failures = 0;

void expect(bool condition, const std::string& message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        ++failures;
    }
}

void test_ble_codec() {
    const wmb::BleFrame original{1, wmb::BleMessageType::PacketStream,
                                 wmb::AckRequired, 0x1234, {0x10, 0x20, 0x30}};
    auto bytes = wmb::encode_ble_frame(original);
    auto decoded = wmb::decode_ble_frame(bytes);
    expect(decoded.has_value(), "valid BLE frame decodes");
    expect(decoded && decoded->sequence == original.sequence, "sequence is little-endian");
    expect(decoded && decoded->payload == original.payload, "payload round-trips");
    bytes.back() ^= 0x01U;
    expect(!wmb::decode_ble_frame(bytes), "bad CRC is rejected");
    wmb::BleFrame oversized = original;
    oversized.payload.resize(wmb::kBleMaxPayload + 1U);
    expect(wmb::encode_ble_frame(oversized).empty(), "oversized payload fails closed");
}

void test_fragmentation() {
    const wmb::BleFrame original{1, wmb::BleMessageType::PacketStream, 0, 7,
                                 {0, 1, 2, 3, 4, 5, 6}};
    const auto fragments = wmb::fragment_ble_frame(original, 3);
    expect(fragments.size() == 3U, "frame fragments to MTU payload");
    wmb::BleReassembler reassembler;
    std::optional<wmb::BleFrame> complete;
    for (const auto& fragment : fragments) {
        complete = reassembler.accept(fragment);
    }
    expect(complete && complete->payload == original.payload, "fragments reassemble");
    expect(wmb::fragment_ble_frame(original, 0).empty(), "zero MTU fails closed");
}

void test_analysis() {
    wmb::PacketAnalyzer analyzer;
    analyzer.observe({100, wmb::Direction::Rx, {1, 2, 3}, true});
    analyzer.observe({200, wmb::Direction::Rx, {1, 2, 3}, true});
    analyzer.observe({250, wmb::Direction::Rx, {1, 9, 3}, true});
    expect(analyzer.unique_group_count() == 2U, "exact packets group together");
    const auto groups = analyzer.groups();
    expect(!groups.empty() && groups.front().count == 2U, "groups sort by count");
    const auto diff = wmb::byte_differences({1, 2, 3}, {1, 9, 3, 4});
    expect(diff.size() == 2U && diff[0].offset == 1U, "byte differences include growth");
    const auto candidates = wmb::checksum_candidates({1, 2, 3});
    expect(std::find(candidates.begin(), candidates.end(), wmb::ChecksumCandidate::Sum8) !=
               candidates.end(),
           "SUM8 candidate is detected");
}

void test_safety() {
    wmb::SafetyPolicy policy;
    policy.allow_verified_command("light.on");
    policy.allow_verified_command("gas.open");
    policy.allow_verified_command("elevator.call");
    wmb::SafetyContext context{true, false, true, true, true, true};
    expect(policy.may_transmit("light.on", context), "verified light command can transmit");
    expect(!policy.may_transmit("gas.open", context), "gas open is always denied");
    expect(!policy.may_transmit("elevator.call", context), "elevator TX is denied");
    context.ota_active = true;
    expect(!policy.may_transmit("light.on", context), "OTA disables transmit");
}

}  // namespace

int main() {
    test_ble_codec();
    test_fragmentation();
    test_analysis();
    test_safety();
    if (failures == 0) {
        std::cout << "All portable core tests passed\n";
    }
    return failures == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}
