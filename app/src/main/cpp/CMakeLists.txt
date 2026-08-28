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

# Deliberately NOT EXCLUDE_FROM_ALL: the Android Gradle plugin packages the
# shared libraries produced by the CMake project, and the dlopen-able ggml
# backend variants must be among them. Tests, examples, tools and the server
# are already disabled above, so "all" is just the libraries.
add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp")

add_library(pocketai_llm SHARED pocketai_llm.cpp)

target_include_directories(pocketai_llm PRIVATE
    "${LLAMA_CPP_DIR}/include"
    "${LLAMA_CPP_DIR}/ggml/include")

target_compile_options(pocketai_llm PRIVATE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(pocketai_llm PRIVATE -Wl,--gc-sections)

target_link_libraries(pocketai_llm PRIVATE llama ggml ggml-base android log)

# Collect every dlopen-able ggml backend that this ABI produced.
#
# The variant names are architecture specific (android_armv8.* on arm64,
# x64/haswell/... on x86_64), so they are discovered from the build system
# rather than hard-coded - that keeps the emulator build working and picks up
# new variants when llama.cpp adds them.
set(POCKETAI_BACKEND_TARGETS "")
get_property(POCKETAI_GGML_TARGETS
    DIRECTORY "${LLAMA_CPP_DIR}/ggml/src"
    PROPERTY BUILDSYSTEM_TARGETS)

foreach (candidate ${POCKETAI_GGML_TARGETS})
    if (candidate MATCHES "^ggml-cpu-" OR candidate MATCHES "^ggml-vulkan$")
        list(APPEND POCKETAI_BACKEND_TARGETS ${candidate})
    endif()
endforeach()

if (POCKETAI_BACKEND_TARGETS)
    add_dependencies(pocketai_llm ${POCKETAI_BACKEND_TARGETS})

    # With GGML_BACKEND_DL the backends are CMake MODULE libraries and ggml
    # points them at CMAKE_RUNTIME_OUTPUT_DIRECTORY. The Android Gradle plugin
    # only packages what lands in CMAKE_LIBRARY_OUTPUT_DIRECTORY, so without
    # this redirect the .so files are built but never make it into the APK -
    # and since the CPU backend is not linked into libggml.so under
    # GGML_BACKEND_DL, the app would ship with no usable backend at all.
    foreach (variant ${POCKETAI_BACKEND_TARGETS})
        set_target_properties(${variant} PROPERTIES
            LIBRARY_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
            RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
    endforeach()

    message(STATUS "PocketAI ggml backends: ${POCKETAI_BACKEND_TARGETS}")
    message(STATUS "PocketAI backend output dir: ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
else()
    message(FATAL_ERROR
        "No dlopen-able ggml backend variants were configured. GGML_BACKEND_DL "
        "leaves libggml.so without a built-in CPU backend, so the app would be "
        "unable to run any model.")
endif()
