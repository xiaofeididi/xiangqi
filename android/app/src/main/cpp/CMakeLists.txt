cmake_minimum_required(VERSION 3.18.1)
project(xqyolo)

set(NCNN_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ncnn)

add_library(xqyolo SHARED yolo_jni.cpp)

target_include_directories(xqyolo PRIVATE ${NCNN_DIR}/include)

add_library(ncnn STATIC IMPORTED)
set_target_properties(ncnn PROPERTIES IMPORTED_LOCATION ${NCNN_DIR}/lib/libncnn.a)

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

# ncnn.a was built with OpenMP + libc++
find_library(omp-lib omp)
if(NOT omp-lib)
    # fallback clang runtime path
    file(GLOB OMP_CANDIDATE
        "${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/*/lib/linux/arm64/libomp.a")
    if(OMP_CANDIDATE)
        list(GET OMP_CANDIDATE 0 omp-lib)
    endif()
endif()
message(STATUS "omp-lib=${omp-lib}")

target_compile_options(xqyolo PRIVATE -fopenmp)

target_link_libraries(xqyolo
    ncnn
    ${jnigraphics-lib}
    ${android-lib}
    ${log-lib}
    ${omp-lib}
    c++_shared
    m
)
