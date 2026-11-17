//
// 三 so 共用的日志宏(纯宏头文件, 无符号链接依赖)。
// tag 统一为 "TiebaTS"——check 侧原 LOGx 经 inline.h 使用 tag
// "gm.tieba.tabswitch", 收编后与 hide/classloader 的 "TiebaTS" 一致,
// logcat 过滤规则按旧 tag 配置的需同步调整;
// 级别语义修正——check 侧原 inline.h 曾把 LOGI/LOGW 全部映射到
// ANDROID_LOG_ERROR, logcat 按级别过滤时 INFO 线索与真错误混在一起。
// crash so 不经此头: 信号处理器内只允许 async-signal-safe 直写,
// 由 crash.cpp 自行用 write() 实现, 详见其注释。
//

#ifndef TS_LOG_H
#define TS_LOG_H

#include <android/log.h>

#ifndef TS_LOG_TAG
#define TS_LOG_TAG "TiebaTS"
#endif

#define TS_LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, TS_LOG_TAG, __VA_ARGS__))
#define TS_LOGW(...) ((void)__android_log_print(ANDROID_LOG_WARN, TS_LOG_TAG, __VA_ARGS__))
#define TS_LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, TS_LOG_TAG, __VA_ARGS__))

#endif //TS_LOG_H
