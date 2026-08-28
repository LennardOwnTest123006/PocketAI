cmake_minimum_required(VERSION 3.22.1)
project(pocketai_llm CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(POCKETAI_VULKAN "Build the ggml Vulkan backend" OFF)

set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
if (NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp sources are missing.\n"
        "Run scripts/fetch_llama_cpp.sh before building the native inference engine.")
endif()

# ---------------------------------------------------------------------------
# ggml / llama.cpp configuration
#
# GGML_BACKEND_DL + GGML_CPU_ALL_VARIANTS makes ggml emit one CPU backend per
# ARM feature level (armv8.0 / armv8.2+dotprod / +fp16 / armv8.6+i8mm) and pick
# the fastest one the running SoC actually supports at load time.  That gives a
# Snapdragon 8 Gen 3 (Galaxy Z Flip6) the i8mm kernels while still starting up
# on older arm64 devices instead of crashing with SIGILL.
# ---------------------------------------------------------------------------
set(BUILD_SHARED_LIBS           ON  CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL             ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS       ON  CACHE BOOL "" FORCE)
set(GGML_NATIVE                 OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP                 OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE              OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE                 OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS            OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES         OFF CACHE BOOL "" FORCE)

set(LLAMA_BUILD_TESTS           OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS           OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER          OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON          OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL                  OFF CACHE BOOL "" FORCE)

if (POCKETAI_VULKAN)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
endif()

add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

add_library(pocketai_llm SHARED pocketai_llm.cpp)

target_include_directories(pocketai_llm PRIVATE
    "${LLAMA_CPP_DIR}/include"
    "${LLAMA_CPP_DIR}/ggml/include")

target_compile_options(pocketai_llm PRIVATE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(pocketai_llm PRIVATE -Wl,--gc-sections)

target_link_libraries(pocketai_llm PRIVATE llama ggml ggml-base android log)

# Make sure every ggml backend variant is built (and therefore packaged into the
# APK) even though llama.cpp was added with EXCLUDE_FROM_ALL.
set(POCKETAI_BACKEND_TARGETS "")
foreach (variant
        ggml-cpu-android_armv8.0_1
        ggml-cpu-android_armv8.2_1
        ggml-cpu-android_armv8.2_2
        ggml-cpu-android_armv8.6_1)
    if (TARGET ${variant})
        list(APPEND POCKETAI_BACKEND_TARGETS ${variant})
    endif()
endforeach()

if (POCKETAI_VULKAN AND TARGET ggml-vulkan)
    list(APPEND POCKETAI_BACKEND_TARGETS ggml-vulkan)
endif()

if (POCKETAI_BACKEND_TARGETS)
    add_dependencies(pocketai_llm ${POCKETAI_BACKEND_TARGETS})
    message(STATUS "PocketAI ggml backends: ${POCKETAI_BACKEND_TARGETS}")
else()
    message(WARNING "PocketAI: no dynamic ggml backend variants were found")
endif()
