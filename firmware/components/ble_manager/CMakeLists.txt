idf_component_register(
    SRCS "src/esp_ble_manager.cpp"
    INCLUDE_DIRS "include"
    REQUIRES core ota_manager bt esp_app_format esp_system
)
target_compile_features(${COMPONENT_LIB} PUBLIC cxx_std_17)
