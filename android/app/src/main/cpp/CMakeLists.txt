cmake_minimum_required(VERSION 3.22.1)
project(everest_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)

# Sumber whisper.cpp diambil sekali dengan scripts/setup-whisper.sh (tag v1.9.5)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)
if (NOT EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "whisper.cpp belum ada. Jalankan: ./scripts/setup-whisper.sh dari folder android/")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} whisper_build)

add_library(everest_whisper SHARED whisper_jni.cpp)
target_link_libraries(everest_whisper PRIVATE whisper log android)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(everest_whisper PRIVATE -O3)
endif()
