cmake_minimum_required(VERSION 3.20)
project(wallpad_matter_bridge_core LANGUAGES CXX)

option(WMB_BUILD_TESTS "Build portable core tests" ON)

add_library(wmb_core
    firmware/components/core/src/ble_frame.cpp
    firmware/components/core/src/packet_analyzer.cpp
    firmware/components/core/src/safety_policy.cpp)
target_include_directories(wmb_core PUBLIC firmware/components/core/include)
target_compile_features(wmb_core PUBLIC cxx_std_17)
if(MSVC)
  target_compile_options(wmb_core PRIVATE /W4 /permissive-)
else()
  target_compile_options(wmb_core PRIVATE -Wall -Wextra -Wpedantic -Werror)
endif()

if(WMB_BUILD_TESTS)
  enable_testing()
  add_executable(wmb_core_tests tests/host/core_tests.cpp)
  target_link_libraries(wmb_core_tests PRIVATE wmb_core)
  add_test(NAME wmb_core_tests COMMAND wmb_core_tests)
endif()

