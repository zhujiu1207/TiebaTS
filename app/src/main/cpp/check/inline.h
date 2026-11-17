//
// Created by Thom on 2019/3/18.
//
#include "../common/tslog.h"

#ifndef BREVENT_INLINE_H
#define BREVENT_INLINE_H

// 级别语义修正: 原 LOGI/LOGW 曾全部映射到 ERROR, 统一收口到 tslog.h
#define LOGI(...) TS_LOGI(__VA_ARGS__)
#define LOGW(...) TS_LOGW(__VA_ARGS__)
#define LOGE(...) TS_LOGE(__VA_ARGS__)

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

bool setRead(void *symbol);

bool isInlineHooked(void *symbol);

#ifdef __cplusplus
}
#endif

#endif //BREVENT_INLINE_H
