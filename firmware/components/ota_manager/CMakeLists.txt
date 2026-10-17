idf_component_register(
    SRCS "src/esp_ota_manager.cpp"
    INCLUDE_DIRS "include"
    REQUIRES core app_update mbedtls nvs_flash)
target_compile_features(${COMPONENT_LIB} PUBLIC cxx_std_17)
