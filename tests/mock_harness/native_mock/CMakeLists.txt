cmake_minimum_required(VERSION 3.12)
project(whisper_mock CXX)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(whisper_mock SHARED whisper_mock.cpp)
