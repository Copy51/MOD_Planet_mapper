cmake_minimum_required(VERSION 3.16)
project(NativePhysics VERSION 1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Jolt Physics Configuration
include(FetchContent)

# Use ZIP archive for stability and performance (no Git clone overhead)
FetchContent_Declare(
  jolt
  URL https://github.com/joltphysics/joltphysics/archive/refs/heads/main.zip
)

FetchContent_MakeAvailable(jolt)

# Configure Jolt build options
set(USE_SSE4_2 ON CACHE BOOL "" FORCE)
set(USE_AVX ON CACHE BOOL "" FORCE)
set(SAMPLES OFF CACHE BOOL "" FORCE)
set(PERF_TEST OFF CACHE BOOL "" FORCE)
set(UNIT_TESTS OFF CACHE BOOL "" FORCE)
set(GENERATE_DEBUG_SYMBOLS OFF CACHE BOOL "" FORCE)

# Create the DLL
add_library(native_physics SHARED 
    physics_bridge.cpp
    physics_bridge.h
)

# Link Jolt Physics
# FetchContent makes 'jolt' source dir available via ${jolt_SOURCE_DIR}
target_include_directories(native_physics PRIVATE ${jolt_SOURCE_DIR})
target_link_libraries(native_physics PRIVATE Jolt)

# Directives for the compiler
if(WIN32)
    target_compile_definitions(native_physics PRIVATE _CRT_SECURE_NO_WARNINGS JPH_PLATFORM_WINDOWS)
    target_compile_options(native_physics PRIVATE /arch:AVX2 /W4)
else()
    target_compile_definitions(native_physics PRIVATE JPH_PLATFORM_LINUX)
endif()

# Installation logic (optional, for convenience)
install(TARGETS native_physics
    DESTINATION ${CMAKE_SOURCE_DIR}/../../../
)
