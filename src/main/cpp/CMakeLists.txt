cmake_minimum_required(VERSION 3.16)
project(NativePhysics VERSION 1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Jolt Physics Configuration
include(FetchContent)

FetchContent_Declare(
  JoltPhysics
  URL https://github.com/jrouwe/JoltPhysics/archive/refs/heads/master.zip
)

# Configure Jolt build options BEFORE populating
set(TARGET_HELLO_WORLD OFF CACHE BOOL "" FORCE)
set(TARGET_PERFORMANCE_TEST OFF CACHE BOOL "" FORCE)
set(TARGET_SAMPLES OFF CACHE BOOL "" FORCE)
set(TARGET_UNIT_TESTS OFF CACHE BOOL "" FORCE)
set(TARGET_VIEWER OFF CACHE BOOL "" FORCE)
set(USE_SSE4_2 ON CACHE BOOL "" FORCE)
set(USE_AVX ON CACHE BOOL "" FORCE)
set(ENABLE_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(INTERPROCEDURAL_OPTIMIZATION ON CACHE BOOL "" FORCE)
set(USE_STATIC_MSVC_RUNTIME_LIBRARY OFF CACHE BOOL "" FORCE)

# Populate JoltPhysics
FetchContent_GetProperties(JoltPhysics)
if(NOT joltphysics_POPULATED)
    FetchContent_Populate(JoltPhysics)
    # JoltPhysics's actual CMake is in Build subdirectory
    add_subdirectory(${joltphysics_SOURCE_DIR}/Build ${joltphysics_BINARY_DIR})
endif()

# Create the DLL
add_library(native_physics SHARED 
    physics_bridge.cpp
    physics_bridge.h
)

# Link Jolt Physics - include the main Jolt directory for headers
target_include_directories(native_physics PRIVATE ${joltphysics_SOURCE_DIR})
target_link_libraries(native_physics PRIVATE Jolt)

# Match Jolt's Static Runtime configuration
if(MSVC)
    set_property(TARGET native_physics PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

# Compiler settings
if(WIN32)
    target_compile_options(native_physics PRIVATE /arch:AVX2 /W3 /MP)
else()
    target_compile_options(native_physics PRIVATE -mavx2 -msse4.2)
endif()

# Installation logic
install(TARGETS native_physics
    RUNTIME DESTINATION ${CMAKE_SOURCE_DIR}/../../../
    LIBRARY DESTINATION ${CMAKE_SOURCE_DIR}/../../../
)
