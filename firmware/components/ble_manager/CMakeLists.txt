set(ble_requires core ota_manager bt esp_app_format esp_system)
if(DEFINED ENV{ESP_MATTER_PATH})
    list(APPEND ble_requires chip openthread)
endif()
idf_component_register(
    SRCS "src/esp_ble_manager.cpp"
    INCLUDE_DIRS "include"
    REQUIRES ${ble_requires}
)
target_compile_features(${COMPONENT_LIB} PUBLIC cxx_std_17)
