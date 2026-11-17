#include <jni.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdlib.h>
#include <sys/system_properties.h>
#include <dlfcn.h>
#include "check.h"
#include "classloader.h"
#include "inline.h"

static bool check_hook_function(void *handle, const char *name) {
    void *symbol = dlsym(handle, name);
    if (symbol != NULL && setRead(symbol) && isInlineHooked(symbol)) {
        return true;
    }
    return false;
}

#define HOOK_SYMBOL(x, y) check_hook_function(x, y)

jboolean _inline(JNIEnv *env, jclass clazz, jstring jname) {
    // 显式判空: 对 null jstring 调 GetStringUTFChars 在 ART 下是未定义行为
    if (jname == NULL) return false;
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    if (name == NULL) return false;
    void *handle = dlopen("libc.so", RTLD_NOW);
    bool isInlineHooked = false;
    // dlclose(NULL) 是未定义行为(POSIX): dlopen 失败时不进入
    if (handle) {
        isInlineHooked = HOOK_SYMBOL(handle, name);
        dlclose(handle);
    }
    (*env)->ReleaseStringUTFChars(env, jname, name);
    return isInlineHooked;
}

jboolean FindClass_inline(JNIEnv *env, jclass clazz) {
    void *symbol = (*env)->FindClass;
    return isInlineHooked(symbol);
}

static inline void fill_ro_build_version_sdk(char v[]) {
    // ro.build.version.sdk
    // 模数在首次调用时确定后恒定(下列 else-if 死分支已删, 勿再"补回")
    static unsigned int m = 0;

    if (m == 0) {
        m = 19;
    }

    v[0x0] = 's';
    v[0x1] = 'm';
    v[0x2] = '-';
    v[0x3] = 'f';
    v[0x4] = 'p';
    v[0x5] = 'o';
    v[0x6] = 'k';
    v[0x7] = 'l';
    v[0x8] = '\'';
    v[0x9] = '|';
    v[0xa] = 'n';
    v[0xb] = '~';
    v[0xc] = '~';
    v[0xd] = 'g';
    v[0xe] = '`';
    v[0xf] = '~';
    v[0x10] = '?';
    v[0x11] = 'a';
    v[0x12] = 'd';
    v[0x13] = 'j';
    for (unsigned int i = 0; i < 0x14; ++i) {
        v[i] ^= ((i + 0x14) % m);
    }
    v[0x14] = '\0';
}

bool xposed_status = false;

jboolean findXposed(JNIEnv *env, jclass clazz) {
    // 0=未读, -1=已读但失败(不再重试也不执行检测): 属性读取失败时若保持 0
    // 会每次重试且因 sdk<21 静默跳过检测, "检测功能无输出"且无任何日志线索
    static int sdk = 0;
    if (sdk == 0) {
        char v1[0x20];
        char prop[PROP_VALUE_MAX] = {0};
        fill_ro_build_version_sdk(v1);
        int len = __system_property_get(v1, prop);
        int parsed = (len > 0) ? (int) strtol(prop, NULL, 10) : 0;
        sdk = (parsed > 0) ? parsed : -1;
        if (sdk == -1) {
            LOGW("findXposed: read ro.build.version.sdk failed, classloader check disabled");
        }
    }

    checkClassLoader(env, sdk);
    return xposed_status;
}

jint _access(JNIEnv *env, jclass clazz, jstring jpath) {
    if (jpath == NULL) return -1;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (path == NULL) return -1;
    int i = access(path, F_OK);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return i;
}

// 解码函数一律填充调用方缓冲: 此前每次调用 strdup 返回堆内存且从不释放,
// _fopen 逐行读取 /proc/self/maps 时每行最多 3 次 = 数千次泄漏(约百 KB/次检测)
static inline void getCannotOpen(char v[]) {
    // cannot open /proc/self/maps
    char x[] = "gdhig}*d|h`/?a`|w:eemd,idvt";
    static unsigned int m = 0;

    if (m == 0) {
        m = 23;
    }

    for (unsigned int i = 0; i < 0x1b; ++i) {
        x[i] ^= ((i + 0x1b) % m);
    }
    memcpy(v, x, 0x1c);
}

static inline void getDataApp(char v[]) {
    // /data/app
    char x[] = "-geqg/`rs";
    static unsigned int m = 0;

    if (m == 0) {
        m = 7;
    }

    for (unsigned int i = 0; i < 0x9; ++i) {
        x[i] ^= ((i + 0x9) % m);
    }
    memcpy(v, x, 0xa);
}

static inline void getComGoogleAndroid(char v[]) {
    // com.google.android
    char x[] = "fij&nedkld,bjatham";
    static unsigned int m = 0;

    if (m == 0) {
        m = 13;
    }

    for (unsigned int i = 0; i < 0x12; ++i) {
        x[i] ^= ((i + 0x12) % m);
    }
    memcpy(v, x, 0x13);
}

static inline void getComBaiduTieba(char v[]) {
    // com.baidu.tieba
    char x[] = "gjk)jhcdt,wm`df";
    static unsigned int m = 0;

    if (m == 0) {
        m = 11;
    }

    for (unsigned int i = 0; i < 0xf; ++i) {
        x[i] ^= ((i + 0xf) % m);
    }
    memcpy(v, x, 0x10);
}

jstring _fopen(JNIEnv *env, jclass clazz, jstring jpath) {
    char cannotOpen[0x1c];
    getCannotOpen(cannotOpen);
    // 显式判空: 对 null jstring 调 GetStringUTFChars 在 ART 下是未定义行为
    if (jpath == NULL) {
        return (*env)->NewStringUTF(env, cannotOpen);
    }
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (path == NULL) {
        return (*env)->NewStringUTF(env, cannotOpen);
    }
    FILE *f = fopen(path, "r");
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (f == NULL) {
        return (*env)->NewStringUTF(env, cannotOpen);
    }

    char result[PATH_MAX];
    strcpy(result, "");
    char line[PATH_MAX];
    // 匹配串移出循环: 一次解码, 整个文件复用(此前每行解码 3 次且泄漏)
    char dataApp[0xa];
    char comGoogleAndroid[0x13];
    char comBaiduTieba[0x10];
    getDataApp(dataApp);
    getComGoogleAndroid(comGoogleAndroid);
    getComBaiduTieba(comBaiduTieba);
    while (fgets(line, PATH_MAX - 1, f) != NULL) {
        if (strstr(line, dataApp) != NULL
            && strstr(line, comGoogleAndroid) == NULL
            && strstr(line, comBaiduTieba) == NULL) {
            if (strlen(result) + strlen(line) + 1 > PATH_MAX) break;
            strcat(result, line);
        }
    }
    fclose(f);
    return (*env)->NewStringUTF(env, result);
}
