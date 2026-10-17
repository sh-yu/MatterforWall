# Build environment

## Firmware

Pinned baseline: ESP-IDF v5.4.2, esp-matter release/v1.4.2, Python 3.11, target
ESP32-C6. Clone esp-matter recursively, run its install/export scripts, then:

```bash
cd firmware
idf.py set-target esp32c6
idf.py build
python ../scripts/check_ota_size.py build/wallpad-matter-bridge.bin partitions.csv
```

Set `ESP_MATTER_PATH` to the pinned esp-matter checkout. A newer pair must not
be substituted without a clean build, commissioning test, Thread/BLE stress
test, and partition-size report.

## Android

Android Gradle Plugin 8.11.1, Kotlin 2.2.0, Gradle 8.14, JDK 17+, compile SDK 35,
and minimum SDK 26 are pinned in the project.

## Portable core

The root CMake project builds without ESP-IDF and exercises wire parsing,
analysis, and safety rules on Windows, Linux, or macOS.

