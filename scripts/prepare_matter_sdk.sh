#!/usr/bin/env bash
# ESP-IDF v5.4.1 must already be installed (e.g. official espressif/idf image).
set -eo pipefail
: "${ESP_MATTER_PATH:?Set ESP_MATTER_PATH to the SDK cache directory}"
if [ ! -d "$ESP_MATTER_PATH/.git" ]; then
    git clone --filter=blob:none --no-checkout https://github.com/espressif/esp-matter.git "$ESP_MATTER_PATH"
fi
git -C "$ESP_MATTER_PATH" checkout 4cedd4ad40821933d6cc237e80ab6604f2953fb0
git -C "$ESP_MATTER_PATH" submodule update --init --depth 1 connectedhomeip/connectedhomeip
(
    cd "$ESP_MATTER_PATH/connectedhomeip/connectedhomeip"
    python3 scripts/checkout_submodules.py --platform esp32 --shallow --recursive --jobs 4
)
(
    cd "$ESP_MATTER_PATH"
    bash install.sh --no-host-tool
)
