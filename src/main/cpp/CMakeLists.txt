cmake_minimum_required(VERSION 3.16)
project(NativePhysics VERSION 1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Jolt Physics Configuration
include(FetchContent)

# Use ZIP archive for stability and performance
FetchContent_Declare(
  jolt
  URL https://github.com/jrouwe/JoltPhysics/archive/refs/heads/master.zip
)

FetchContent_MakeAvailable(jolt)

# Configure Jolt build options
# Ensure Jolt builds as a static library
set(TARGET_JOLT_PHYSICS ON CACHE BOOL "" FORCE)
set(USE_SSE4_2 ON CACHE BOOL "" FORCE)
set(USE_AVX ON CACHE BOOL "" FORCE)
set(SAMPLES OFF CACHE BOOL "" FORCE)
set(JOB_SYSTEM_EXAMPLE OFF CACHE BOOL "" FORCE)
set(PERF_TEST OFF CACHE BOOL "" FORCE)
set(UNIT_TESTS OFF CACHE BOOL "" FORCE)
set(GENERATE_DEBUG_SYMBOLS OFF CACHE BOOL "" FORCE)
# Vital: Force static build so we don't need a separate Jolt DLL
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE) 

# Create the DLL
add_library(native_physics SHARED 
    physics_bridge.cpp
    physics_bridge.h
)

# Link Jolt Physics
# FetchContent makes 'jolt' source dir available via ${jolt_SOURCE_DIR}
target_include_directories(native_physics PRIVATE ${jolt_SOURCE_DIR})

# Jolt's CMake defines 'Jolt' as the target name
target_link_libraries(native_physics PRIVATE Jolt)

# Directives for the compiler
if(WIN32)
    # /MP for multi-processor compilation
    target_compile_definitions(native_physics PRIVATE _CRT_SECURE_NO_WARNINGS JPH_PLATFORM_WINDOWS)
    target_compile_options(native_physics PRIVATE /arch:AVX2 /W4 /MP)
else()
    target_compile_definitions(native_physics PRIVATE JPH_PLATFORM_LINUX)
endif()

# Installation logic (optional, for convenience)
install(TARGETS native_physics
    DESTINATION ${CMAKE_SOURCE_DIR}/../../../
)
