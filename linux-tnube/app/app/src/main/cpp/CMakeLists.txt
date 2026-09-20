cmake_minimum_required(VERSION 3.22.1)
project(ptyexec C)

add_library(ptyexec SHARED ptyexec.c)

find_library(log-lib log)
target_link_libraries(ptyexec ${log-lib})
