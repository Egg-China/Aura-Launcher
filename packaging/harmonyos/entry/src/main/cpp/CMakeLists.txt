cmake_minimum_required(VERSION 3.16.0)
project(aura_launcher LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(aura_launcher SHARED aura_launcher_napi.cpp)
target_compile_options(aura_launcher PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(aura_launcher PUBLIC libace_napi.z.so)
