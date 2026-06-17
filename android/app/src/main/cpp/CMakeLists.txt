cmake_minimum_required(VERSION 3.22.1)
project("ptynative")

add_library(ptynative SHARED pty_native.c)

find_library(log-lib log)
target_link_libraries(ptynative ${log-lib})
