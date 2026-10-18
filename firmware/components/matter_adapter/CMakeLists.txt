set(adapter_requires core)
# Dependencies are resolved before sdkconfig is loaded. Do not gate REQUIRES on
# CONFIG_*; otherwise the real source loses transitive SDK headers.
if(DEFINED ENV{ESP_MATTER_PATH})
    list(APPEND adapter_requires esp_matter chip openthread)
endif()
if(CONFIG_WMB_ENABLE_MATTER_EXPERIMENTAL)
    idf_component_register(SRCS "src/esp_matter_bridge.cpp" INCLUDE_DIRS "include"
                           REQUIRES ${adapter_requires})
else()
    idf_component_register(SRCS "src/esp_matter_bridge_stub.cpp" INCLUDE_DIRS "include"
                           REQUIRES ${adapter_requires})
endif()
target_compile_features(${COMPONENT_LIB} PUBLIC cxx_std_17)
