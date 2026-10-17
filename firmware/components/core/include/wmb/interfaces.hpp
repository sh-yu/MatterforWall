#pragma once

#include <cstdint>
#include <functional>
#include <optional>
#include <string>
#include <vector>

namespace wmb {

using Bytes = std::vector<std::uint8_t>;

enum class Direction { Rx, Tx };
enum class OperatingMode { PassiveSniffer, ActiveControl };
enum class Parity { None, Even, Odd };

struct UartConfig {
    std::uint32_t baud{9600};
    std::uint8_t data_bits{8};
    Parity parity{Parity::None};
    std::uint8_t stop_bits{1};
    std::uint32_t frame_timeout_ms{20};
};

struct Packet {
    std::uint64_t timestamp_us{};
    Direction direction{Direction::Rx};
    Bytes data;
    bool synthetic_test_data{false};
};

struct Rs485Stats {
    std::uint64_t rx_frames{};
    std::uint64_t rx_bytes{};
    std::uint64_t tx_frames{};
    std::uint64_t framing_errors{};
    std::uint64_t parity_errors{};
    std::uint64_t buffer_overruns{};
    std::uint64_t dropped_tx{};
};

struct DeviceState {
    std::string id;
    std::string kind;
    std::string value;
    std::uint64_t observed_at_us{};
};

class IRs485Transport {
public:
    virtual ~IRs485Transport() = default;
    virtual bool configure(const UartConfig& config) = 0;
    virtual void set_receive_handler(std::function<void(Packet)> handler) = 0;
    virtual bool queue_transmit(Bytes frame) = 0;
    virtual void force_receive_only() = 0;
    virtual Rs485Stats stats() const = 0;
};

class IProtocolDecoder {
public:
    virtual ~IProtocolDecoder() = default;
    virtual std::optional<DeviceState> decode(const Packet& packet) const = 0;
};

class IProtocolEncoder {
public:
    virtual ~IProtocolEncoder() = default;
    virtual std::optional<Bytes> encode(const std::string& command,
                                        const std::string& argument) const = 0;
};

class IMatterBridge {
public:
    virtual ~IMatterBridge() = default;
    virtual bool start() = 0;
    virtual bool publish(const DeviceState& state) = 0;
    virtual bool commissioned() const = 0;
};

class IBleTransport {
public:
    virtual ~IBleTransport() = default;
    virtual bool notify_packet(const Packet& packet) = 0;
    virtual bool notify_response(std::uint16_t sequence, const Bytes& payload) = 0;
    virtual bool administrator_authenticated() const = 0;
};

class IConfigStorage {
public:
    virtual ~IConfigStorage() = default;
    virtual std::optional<Bytes> get(const std::string& key) const = 0;
    virtual bool put(const std::string& key, const Bytes& value) = 0;
    virtual bool erase_all_preserving_fabric() = 0;
};

class IOtaManager {
public:
    virtual ~IOtaManager() = default;
    virtual bool begin(std::uint32_t size, const Bytes& expected_sha256) = 0;
    virtual bool write(std::uint32_t offset, const Bytes& chunk) = 0;
    virtual bool finish() = 0;
    virtual void abort() = 0;
    virtual bool active() const = 0;
    virtual std::uint32_t next_offset() const = 0;
    virtual std::uint32_t expected_size() const = 0;
};

class IPacketAnalyzer {
public:
    virtual ~IPacketAnalyzer() = default;
    virtual void observe(const Packet& packet) = 0;
    virtual std::size_t unique_group_count() const = 0;
};

}  // namespace wmb
