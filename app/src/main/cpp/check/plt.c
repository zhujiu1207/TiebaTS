//
// Created by Thom on 2019/2/16.
//

#include <stdint.h>
#include <string.h>
#include <stdbool.h>
#include <stdlib.h>
#include <dlfcn.h>
#include "plt.h"
// LOGW 定义于此: 本文件 dl_iterate_phdr 兜底分支用到, 缺失时该分支在
// arm32 且 API<21 的配置下会编译失败(现行 arm64 配置该分支被预处理掉)
#include "inline.h"

/*
 * reference: https://android.googlesource.com/platform/bionic/+/master/linker/linker_soinfo.cpp
 */
static uint32_t gnu_hash(const uint8_t *name) {
    uint32_t h = 5381;

    while (*name) {
        h += (h << 5) + *name++;
    }
    return h;
}

static uint32_t elf_hash(const uint8_t *name) {
    uint32_t h = 0, g;

    while (*name) {
        h = (h << 4) + *name++;
        g = h & 0xf0000000;
        h ^= g;
        h ^= g >> 24;
    }

    return h;
}

static ElfW(Dyn) *find_dyn_by_tag(ElfW(Dyn) *dyn, ElfW(Sxword) tag) {
    while (dyn->d_tag != DT_NULL) {
        if (dyn->d_tag == tag) {
            return dyn;
        }
        ++dyn;
    }
    return NULL;
}

static inline bool is_global(ElfW(Sym) *sym) {
    unsigned char stb = ELF_ST_BIND(sym->st_info);
    if (stb == STB_GLOBAL || stb == STB_WEAK) {
        return sym->st_shndx != SHN_UNDEF;
    } else {
        return false;
    }
}

// 解析 DT_STRSZ: 符号名字符串表的长度, 是 st_name 唯一可用的边界。
// 磁盘损坏/被篡改的 so 的 hash/symtab 结构可以任意畸形, 而 find_symbol/find_plt
// 会对进程内每个 so 执行——无边界校验的 strcmp(dynstr + st_name, ...) / dynsym[idx]
// 都是越界读, 单个坏 so 即可让每次 findXposed 检测 SIGSEGV 崩宿主
static size_t find_strsz(ElfW(Dyn) *base_addr) {
    ElfW(Dyn) *dyn = find_dyn_by_tag(base_addr, DT_STRSZ);
    if (dyn == NULL) return 0;
    return (size_t) dyn->d_un.d_val;
}

// st_name 落在 strtab 内才允许 strcmp; strtab 自身以 '\0' 结尾兜底
static inline bool st_name_in_bounds(ElfW(Sym) *sym, size_t strsz) {
    return strsz > 0 && sym->st_name < strsz;
}

// 符号索引的经验上界: 每个符号至少在 strtab 中占 1 字节, symcount <= strsz
// (真符号远小于此, 仅用于把"重定位表被篡改出的巨大 idx"挡在越界读之外)
static inline bool sym_idx_in_bounds(size_t idx, size_t strsz) {
    return strsz > 0 && idx < strsz / sizeof(ElfW(Sym));
}

// 链遍历迭代上限: 合法链长不超过符号数(<< strsz/16 量级), 损坏的 GNU_HASH
// 链可能成环或无终止标志, 无上限会死循环乃至越界读
#define CHAIN_MAX_STEPS 0x100000u

static ElfW(Addr) *
find_symbol(struct dl_phdr_info *info, ElfW(Dyn) *base_addr, const char *symbol) {
    ElfW(Dyn) *dyn;

    size_t strsz = find_strsz(base_addr);
    if (strsz == 0) return NULL;

    dyn = find_dyn_by_tag(base_addr, DT_SYMTAB);
    if (dyn == NULL) return NULL;
    ElfW(Sym) *dynsym = (ElfW(Sym) *) (info->dlpi_addr + dyn->d_un.d_ptr);

    dyn = find_dyn_by_tag(base_addr, DT_STRTAB);
    if (dyn == NULL) return NULL;
    char *dynstr = (char *) (info->dlpi_addr + dyn->d_un.d_ptr);

    dyn = find_dyn_by_tag(base_addr, DT_GNU_HASH);
    if (dyn != NULL) {
        ElfW(Word) *dt_gnu_hash = (ElfW(Word) *) (info->dlpi_addr + dyn->d_un.d_ptr);
        size_t gnu_nbucket_ = dt_gnu_hash[0];
        uint32_t gnu_symoffset_ = dt_gnu_hash[1];
        uint32_t gnu_maskwords_ = dt_gnu_hash[2];
        uint32_t gnu_shift2_ = dt_gnu_hash[3];
        // 畸形头(nbucket=0/maskwords 非二次幂/shift2 越界/symoffset 超过 dynsym
        // 容量)会在取模、bloom 寻址或 chain 指针计算上直接越界, 先全部挡掉。
        // 注意 symoffset 与 nbucket 无约束关系(nbucket=1, symoffset=161 完全合法,
        // 宿主 APK 内 64% 的 GNU hash 库即此形态), 不能按"symoffset 越过 bucket
        // 表"判畸形; 合法上界是 dynsym 符号总数, 而每个符号名至少占 strtab 1 字节,
        // 故以 strtab 大小近似封顶
        if (gnu_nbucket_ == 0 || gnu_nbucket_ > 0x10000000u ||
            gnu_maskwords_ == 0 || (gnu_maskwords_ & (gnu_maskwords_ - 1)) != 0 ||
            gnu_shift2_ >= 32 ||
            gnu_symoffset_ > (uint32_t)(strsz / sizeof(ElfW(Sym)))) {
            return NULL;
        }
        ElfW(Addr) *gnu_bloom_filter_ = (ElfW(Addr) *) (dt_gnu_hash + 4);
        uint32_t *gnu_bucket_ = (uint32_t *) (gnu_bloom_filter_ + gnu_maskwords_);
        uint32_t *gnu_chain_ = gnu_bucket_ + gnu_nbucket_ - gnu_symoffset_;

        --gnu_maskwords_;

        uint32_t hash = gnu_hash((uint8_t *) symbol);
        uint32_t h2 = hash >> gnu_shift2_;

        uint32_t bloom_mask_bits = sizeof(ElfW(Addr)) * 8;
        uint32_t word_num = (hash / bloom_mask_bits) & gnu_maskwords_;
        ElfW(Addr) bloom_word = gnu_bloom_filter_[word_num];

        if ((1 & (bloom_word >> (hash % bloom_mask_bits)) &
             (bloom_word >> (h2 % bloom_mask_bits))) == 0) {
            return NULL;
        }

        uint32_t n = gnu_bucket_[hash % gnu_nbucket_];

        if (n == 0) {
            return NULL;
        }

        uint32_t steps = 0;
        do {
            // bucket/chain 表项是被篡改 so 可控的任意 32 位值, 在解引用
            // dynsym + n / gnu_chain_[n] 前必须校验索引本身在界内
            if (!sym_idx_in_bounds(n, strsz)) return NULL;
            ElfW(Sym) *sym = dynsym + n;
            if (((gnu_chain_[n] ^ hash) >> 1) == 0
                && is_global(sym)
                && st_name_in_bounds(sym, strsz)
                && strcmp(dynstr + sym->st_name, symbol) == 0) {
                ElfW(Addr) *symbol_sym = (ElfW(Addr) *) (info->dlpi_addr + sym->st_value);
#ifdef DEBUG_PLT
                LOGI("found %s(gnu+%u) in %s, %p", symbol, n, info->dlpi_name, symbol_sym);
#endif
                return symbol_sym;
            }
            if ((gnu_chain_[n] & 1) != 0) break;
            ++n;
            if (++steps > CHAIN_MAX_STEPS) return NULL;
        } while (true);

        return NULL;
    }

    dyn = find_dyn_by_tag(base_addr, DT_HASH);
    if (dyn != NULL) {
        ElfW(Word) *dt_hash = (ElfW(Word) *) (info->dlpi_addr + dyn->d_un.d_ptr);
        size_t nbucket_ = dt_hash[0];
        if (nbucket_ == 0 || nbucket_ > 0x10000000u) return NULL;
        uint32_t *bucket_ = dt_hash + 2;
        uint32_t *chain_ = bucket_ + nbucket_;

        uint32_t hash = elf_hash((uint8_t *) (symbol));
        uint32_t steps = 0;
        for (uint32_t n = bucket_[hash % nbucket_]; n != 0 && steps <= CHAIN_MAX_STEPS; n = chain_[n], ++steps) {
            // 同 GNU_HASH 路径: bucket/chain 表项内容不受信, 先校验索引再解引用
            if (!sym_idx_in_bounds(n, strsz)) return NULL;
            ElfW(Sym) *sym = dynsym + n;
            if (is_global(sym) &&
                st_name_in_bounds(sym, strsz) &&
                strcmp(dynstr + sym->st_name, symbol) == 0) {
                ElfW(Addr) *symbol_sym = (ElfW(Addr) *) (info->dlpi_addr + sym->st_value);
#ifdef DEBUG_PLT
                LOGI("found %s(elf+%u) in %s, %p", symbol, n, info->dlpi_name, symbol_sym);
#endif
                return symbol_sym;
            }
        }

        return NULL;
    }

    return NULL;
}

static inline bool isso(const char *str) {
    if (str == NULL) {
        return false;
    }
    const char *dot = strrchr(str, '.');
    return dot != NULL
           && *++dot == 's'
           && *++dot == 'o'
           && (*++dot == '\0' || *dot == '\r' || *dot == '\n');
}

static int callback(struct dl_phdr_info *info, __unused size_t size, void *data) {
    if (!isso(info->dlpi_name)) {
#ifdef DEBUG_PLT
        LOGW("ignore non-so: %s", info->dlpi_name);
#endif
        return 0;
    }
    Symbol *symbol = (Symbol *) data;
    for (ElfW(Half) phdr_idx = 0; phdr_idx < info->dlpi_phnum; ++phdr_idx) {
        ElfW(Phdr) phdr = info->dlpi_phdr[phdr_idx];
        if (phdr.p_type != PT_DYNAMIC) {
            continue;
        }
        ElfW(Dyn) *base_addr = (ElfW(Dyn) *) (info->dlpi_addr + phdr.p_vaddr);
        ElfW(Addr) *addr = find_symbol(info, base_addr, symbol->symbol_name);
        if (addr != NULL) {
            symbol->symbol_sym = addr;
            if (symbol->check == PLT_CHECK_SYM_ONE) {
                return PLT_CHECK_SYM_ONE;
            }
        }
    }
    return 0;
}

void *plt_dlsym(const char *name) {
    Symbol symbol;
    memset(&symbol, 0, sizeof(Symbol));
    symbol.check = PLT_CHECK_SYM_ONE;
    symbol.symbol_name = name;
    dl_iterate_phdr_symbol(&symbol);
    return symbol.symbol_sym;
}

/**
 * symbol->check 按位组合 PLT_CHECK_PLT_APP / PLT_CHECK_PLT_ALL / PLT_CHECK_SYM_ONE
 * @param symbol
 * @return
 */
int dl_iterate_phdr_symbol(Symbol *symbol) {
    int result;
#ifdef DEBUG_PLT
    LOGI("start dl_iterate_phdr: %s", symbol->symbol_name);
#endif
#if __ANDROID_API__ >= 21 || !defined(__arm__)
    result = dl_iterate_phdr(callback, symbol);
#else
    int (*dl_iterate_phdr)(int (*)(struct dl_phdr_info *, size_t, void *), void *);
    dl_iterate_phdr = dlsym(RTLD_NEXT, "dl_iterate_phdr");
    if (dl_iterate_phdr != NULL) {
        result = dl_iterate_phdr(callback, symbol);
    } else {
        result = 0;
        void *handle = dlopen("libdl.so", RTLD_NOW);
        // dlclose(NULL) 是未定义行为(POSIX): dlopen 失败时不进入
        if (handle != NULL) {
            dl_iterate_phdr = dlsym(handle, "dl_iterate_phdr");
            if (dl_iterate_phdr != NULL) {
                result = dl_iterate_phdr(callback, symbol);
            } else {
                LOGW("cannot dlsym dl_iterate_phdr");
            }
            dlclose(handle);
        } else {
            LOGW("cannot dlopen libdl.so");
        }
    }
#endif
#ifdef DEBUG_PLT
    LOGI("complete dl_iterate_phdr: %s", symbol->symbol_name);
#endif
    return result;
}
