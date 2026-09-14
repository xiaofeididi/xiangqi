cmake_minimum_required(VERSION 3.18.1)
project(xqyolo)

set(NCNN_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ncnn)

add_library(xqyolo SHARED yolo_jni.cpp)

target_include_directories(xqyolo PRIVATE ${NCNN_DIR}/include)

# static ncnn (arm64)
add_library(ncnn STATIC IMPORTED)
set_target_properties(ncnn PROPERTIES IMPORTED_LOCATION ${NCNN_DIR}/lib/libncnn.a)

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(xqyolo
    ncnn
    ${jnigraphics-lib}
    ${android-lib}
    ${log-lib}
)
