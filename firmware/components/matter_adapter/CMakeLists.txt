if(CONFIG_WMB_ENABLE_MATTER_EXPERIMENTAL)
    idf_component_register(SRCS "src/esp_matter_bridge.cpp" INCLUDE_DIRS "include"
                           PRIV_INCLUDE_DIRS "$ENV{ESP_MATTER_PATH}/components/esp_matter"
                                             "$ENV{ESP_MATTER_PATH}/components/esp_matter/data_model"
                                             "$ENV{ESP_MATTER_PATH}/components/esp_matter/utils"
                                             "$ENV{ESP_MATTER_PATH}/components/esp_matter/zap_common"
                                             "$ENV{ESP_MATTER_PATH}/connectedhomeip/connectedhomeip/src"
                                             "$ENV{ESP_MATTER_PATH}/connectedhomeip/connectedhomeip/zzz_generated/app-common"
                                             "$ENV{ESP_MATTER_PATH}/connectedhomeip/connectedhomeip/third_party/nlassert/repo/include"
                                             "$ENV{ESP_MATTER_PATH}/connectedhomeip/connectedhomeip/third_party/nlio/repo/include"
                                             "${CMAKE_BINARY_DIR}/esp-idf/chip/gen/include"
                           REQUIRES core esp_matter openthread)
else()
    idf_component_register(SRCS "src/esp_matter_bridge_stub.cpp" INCLUDE_DIRS "include"
                           REQUIRES core)
endif()
target_compile_features(${COMPONENT_LIB} PUBLIC cxx_std_17)
