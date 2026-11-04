cmake_minimum_required(VERSION 3.20)
project(voxfix_translate C CXX)

if(NOT LLAMA_SOURCE)
    message(FATAL_ERROR "LLAMA_SOURCE must point at the pinned llama.cpp checkout")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
# The K60 (SM8475) has dotprod and fp16; leave i8mm out until it is measured.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8.2-a+dotprod+fp16")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+dotprod+fp16")

add_subdirectory(${LLAMA_SOURCE} llama)

add_library(voxfix_translate SHARED translate_jni.cpp)
target_link_libraries(voxfix_translate PRIVATE llama log)
target_link_options(voxfix_translate PRIVATE "-Wl,-z,max-page-size=16384")
set_target_properties(voxfix_translate PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
