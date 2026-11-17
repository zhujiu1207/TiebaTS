cmake_minimum_required(VERSION 3.18.1)
project(TS)
set(CMAKE_CXX_STANDARD 20)

set(C_FLAGS "-Werror=format -fdata-sections -ffunction-sections -fno-exceptions -fno-rtti -fno-threadsafe-statics")
# 16KB 页对齐(NDK r27 起才默认开启): Android 15/16 的 16KB 页内核设备上,
# 仅 4KB 对齐的 LOAD 段会被动态链接器直接拒载——libhide 由 LSPosed 原生接口
# 加载且无 Java 兜底, 拒载即隐藏功能静默失效; 对 4KB 页设备无副作用
set(LINKER_FLAGS "-Wl,--hash-style=both -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    message("Building Release...")

    # -Oz 而非 -O2: check/hide 是低频检测代码(启动探测一次)而非热路径,
    # 体积优先于速度; 热路径压力全在 JVM 层, 原生侧无性能敏感区
    set(C_FLAGS "${C_FLAGS} -Oz -fvisibility=hidden -fvisibility-inlines-hidden")
    set(LINKER_FLAGS "${LINKER_FLAGS} -Wl,-exclude-libs,ALL -Wl,--gc-sections")
else ()
    message("Building Debug...")

    add_definitions(-DDEBUG)
endif ()

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${C_FLAGS}")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${C_FLAGS}")

set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} ${LINKER_FLAGS}")
set(CMAKE_MODULE_LINKER_FLAGS "${CMAKE_MODULE_LINKER_FLAGS} ${LINKER_FLAGS}")

find_package(cxx REQUIRED CONFIG)
find_library(log-lib log)

# 原生崩溃捕获: 独立静态库, 只允许 hide 链接。
# 静态库成员会复制进每个引用它的 so——若 check 也链接并调用 ts_crash_*,
# 进程内会出现两份 g_old[]/g_installed, 两个信号处理器互相链式覆盖;
# 此前靠"check 恰好未引用"侥幸成立, 现在从链接关系上结构性保证全进程
# 只有 hide 持有一份处理器状态。check 侧不得调用 ts_crash_*。
add_library(ts_crash STATIC crash/crash.cpp)
target_include_directories(ts_crash PUBLIC crash)
target_link_libraries(ts_crash ${log-lib})

# file(GLOB CONFIGURE_DEPENDS) 替代已废弃的 aux_source_directory:
# 后者新增/改名源文件不会触发重新配置, 会静默漏编
file(GLOB CHECK_SRC CONFIGURE_DEPENDS
        ${CMAKE_CURRENT_SOURCE_DIR}/check/*.c
        ${CMAKE_CURRENT_SOURCE_DIR}/check/*.cpp)
add_library(check SHARED ${CHECK_SRC})
target_link_libraries(check ${log-lib} cxx::cxx)

file(GLOB HIDE_SRC CONFIGURE_DEPENDS
        ${CMAKE_CURRENT_SOURCE_DIR}/hide/*.c
        ${CMAKE_CURRENT_SOURCE_DIR}/hide/*.cpp)
add_library(hide SHARED ${HIDE_SRC})
target_link_libraries(hide ${log-lib} cxx::cxx ts_crash)
