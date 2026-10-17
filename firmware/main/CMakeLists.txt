idf_component_register(SRCS "app_main.cpp" INCLUDE_DIRS "."
                       REQUIRES core rs485_driver ble_manager ota_manager matter_adapter nvs_flash app_update)
target_compile_features(${COMPONENT_LIB} PUBLIC cxx_std_17)
