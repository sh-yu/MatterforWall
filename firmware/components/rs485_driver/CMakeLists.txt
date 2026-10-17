idf_component_register(
    SRCS "src/esp_rs485_transport.cpp"
    INCLUDE_DIRS "include"
    REQUIRES core esp_driver_uart esp_driver_gpio esp_timer)
target_compile_features(${COMPONENT_LIB} PUBLIC cxx_std_17)

