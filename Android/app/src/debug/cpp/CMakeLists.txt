cmake_minimum_required(VERSION 3.22.1)

project(ort_debug_index LANGUAGES CXX)

# The static anchor gives Android Gradle Plugin a native target to import. ORT sources are marked
# HEADER_FILE_ONLY below, so Android Studio indexes them without compiling ONNX Runtime again.
add_library(ort_debug_index STATIC ort_debug_anchor.cpp)

if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    # These module-local links keep Android Studio's Android view from filtering out source entries
    # that physically live above the Gradle project root.
    set(ORT_CORE_DIR "${CMAKE_CURRENT_LIST_DIR}/ort-core")
    set(ORT_INCLUDE_DIR "${CMAKE_CURRENT_LIST_DIR}/ort-include")
    set(ORT_JNI_DIR "${CMAKE_CURRENT_LIST_DIR}/ort-jni")

    file(GLOB_RECURSE ORT_DEBUG_SOURCES CONFIGURE_DEPENDS FOLLOW_SYMLINKS
        "${ORT_INCLUDE_DIR}/*.h"
        "${ORT_INCLUDE_DIR}/*.hpp"
        "${ORT_JNI_DIR}/*.c"
        "${ORT_JNI_DIR}/*.h"
        "${ORT_CORE_DIR}/*.c"
        "${ORT_CORE_DIR}/*.cc"
        "${ORT_CORE_DIR}/*.cpp"
        "${ORT_CORE_DIR}/*.h"
        "${ORT_CORE_DIR}/*.hpp"
        "${ORT_CORE_DIR}/*.inc"
    )

    set_source_files_properties(${ORT_DEBUG_SOURCES} PROPERTIES HEADER_FILE_ONLY TRUE)
    target_sources(ort_debug_index PRIVATE ${ORT_DEBUG_SOURCES})
    target_include_directories(ort_debug_index PRIVATE
        "${ORT_INCLUDE_DIR}"
        "${ORT_CORE_DIR}/../.."
        "${ORT_CORE_DIR}/.."
    )
endif()
