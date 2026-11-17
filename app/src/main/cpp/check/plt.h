//
// Created by Thom on 2019/2/16.
//

#ifndef BREVENT_PLT_H
#define BREVENT_PLT_H

#include <elf.h>
#include <link.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// 本文件只保留按符号名在全部已加载 so 的 dynsym 中精确查找
// (PLT_CHECK_SYM_ONE)这一条路径。上游 Brevent 检测库的 PLT 表比对
// (PLT_CHECK_PLT_APP/PLT_CHECK_PLT_ALL + Symbol.symbol_plt)在本模块
// 的全部调用方中不可达, 已随 path.c(isThirdParty)一并移除
#define PLT_CHECK_SYM_ONE ((unsigned short) 0x8u)

typedef struct Symbol {
    unsigned short check;
    ElfW(Addr) *symbol_sym;
    const char *symbol_name;
} Symbol;

int dl_iterate_phdr_symbol(Symbol *symbol);

void *plt_dlsym(const char *name);

#ifdef __cplusplus
}
#endif

#endif //BREVENT_PLT_H
