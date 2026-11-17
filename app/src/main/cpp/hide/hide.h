#ifndef XPOSEDTEMPLATE_NATIVE_API_H
#define XPOSEDTEMPLATE_NATIVE_API_H

#include <cstdint>
#include <string>

typedef int (*HookFunType)(void *func, void *replace, void **backup);

typedef int (*UnhookFunType)(void *func);

typedef void (*NativeOnModuleLoaded)(const char *name, void *handle);

typedef struct {
    uint32_t version;
    HookFunType hook_func;
    UnhookFunType unhook_func;
} NativeAPIEntries;

// 实测核对(LSPosed core/src/main/jni/src/native_api.cpp, v1.8.0/v1.9.2/master,
// 2026-10): entries->version == 2 (官方 wiki 的 Native-Hook 页是过时文档);
// 2021 早期版本为 1, 三字段布局与本结构逐字节相同。两者皆接受, 未知版本拒用
// (fail-safe), 防布局演进后按旧布局解读取到错误函数指针
#define TS_NATIVE_API_VERSION 2u
#define TS_NATIVE_API_VERSION_LEGACY 1u

typedef NativeOnModuleLoaded (*NativeInit)(const NativeAPIEntries *entries);

#endif //XPOSEDTEMPLATE_NATIVE_API_H
