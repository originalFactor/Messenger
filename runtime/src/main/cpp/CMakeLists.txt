# Copyright 2026 ECSDevs
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# libtermux.so provides JNI.createSubprocess() (fork + /dev/ptmx PTY,
# TERMUX__PREFIX-aware environment, execvp) that the vendored Termux terminal
# emulator needs; something Java cannot do without native code. Source vendored
# from termux-app v0.118.3 (terminal-emulator, Apache-2.0).
cmake_minimum_required(VERSION 3.22.1)

project(termux C)

add_library(termux SHARED termux.c)

# Same flags as upstream (minus -Werror, which upstream's pinned NDK tolerated).
target_compile_options(termux PRIVATE -std=c11 -Wall -Wextra -Os -fno-stack-protector)
target_link_options(termux PRIVATE -Wl,--gc-sections)
