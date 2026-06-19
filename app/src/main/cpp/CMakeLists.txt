cmake_minimum_required(VERSION 3.22.1)
project("openfree_native")

# Force Release optimizations even in Debug APK builds to ensure acceptable real-time performance on device
set(CMAKE_BUILD_TYPE Release CACHE STRING "Choose the type of build" FORCE)

# Set paths to whisper.cpp submodule
set(WHISPER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp")

# Safety Check: check if submodule was actually cloned
if (NOT EXISTS "${WHISPER_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "whisper.cpp submodule not found at ${WHISPER_DIR}. Please run: git submodule update --init --recursive")
endif()

# Disable OpenMP to prevent thread scheduling issues, thermal throttling, and SIGSEGV crashes on Android
set(GGML_OPENMP OFF CACHE BOOL "Use OpenMP" FORCE)

# Build whisper.cpp target via subdirectory inclusion
add_subdirectory("${WHISPER_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp")

# Compile JNI bridge shared library
add_library(openfree_native SHARED
    whisper-jni.cpp
)

# Include whisper headers
target_include_directories(openfree_native PRIVATE
    "${WHISPER_DIR}"
    "${WHISPER_DIR}/include"
)

# Link JNI bridge with whisper library, Android logging library, and NDK runtime APIs
target_link_libraries(openfree_native
    whisper
    android
    log
)
