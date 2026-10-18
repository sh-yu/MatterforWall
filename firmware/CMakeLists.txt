cmake_minimum_required(VERSION 3.16)

set(EXTRA_COMPONENT_DIRS
    "${CMAKE_CURRENT_LIST_DIR}/components/core"
    "${CMAKE_CURRENT_LIST_DIR}/components/rs485_driver"
    "${CMAKE_CURRENT_LIST_DIR}/components/ble_manager"
    "${CMAKE_CURRENT_LIST_DIR}/components/ota_manager"
    "${CMAKE_CURRENT_LIST_DIR}/components/matter_adapter")

if(DEFINED ENV{ESP_MATTER_PATH})
    set(ESP_MATTER_PATH $ENV{ESP_MATTER_PATH})
    set(MATTER_SDK_PATH ${ESP_MATTER_PATH}/connectedhomeip/connectedhomeip)
    include(${ESP_MATTER_PATH}/examples/common/cmake_common/components_include.cmake)
    list(APPEND EXTRA_COMPONENT_DIRS
        "${ESP_MATTER_PATH}/components"
        "${MATTER_SDK_PATH}/config/esp32/components")
endif()

include($ENV{IDF_PATH}/tools/cmake/project.cmake)
set(PROJECT_VER "0.4.4")
project(wallpad-matter-bridge)

if(DEFINED ENV{ESP_MATTER_PATH})
    idf_build_set_property(CXX_COMPILE_OPTIONS
        "-std=gnu++17;-Os;-DCHIP_HAVE_CONFIG_H;-Wno-overloaded-virtual" APPEND)
    idf_build_set_property(C_COMPILE_OPTIONS "-Os" APPEND)
endif()
