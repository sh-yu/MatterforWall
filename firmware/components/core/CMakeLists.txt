idf_component_register(
    SRCS "src/ble_frame.cpp" "src/packet_analyzer.cpp" "src/safety_policy.cpp"
    INCLUDE_DIRS "include")
target_compile_features(${COMPONENT_LIB} PUBLIC cxx_std_17)

