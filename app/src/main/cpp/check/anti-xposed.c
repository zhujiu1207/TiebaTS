//
// Created by Thom on 2019/3/7.
//

#include <stdio.h>
#include <stdbool.h>
#include <string.h>
#include <limits.h>
#include <dlfcn.h>

#include "anti-xposed.h"
#include "plt.h"

static jclass originalXposedClass;

static inline void fill_java_lang_VMClassLoader(char v[]) {
    // java/lang/VMClassLoader
    // 模数在首次调用时确定后恒定(下列 else-if 死分支已删, 勿再"补回")
    static unsigned int m = 0;

    if (m == 0) {
        m = 19;
    }

    v[0x0] = 'n';
    v[0x1] = 'd';
    v[0x2] = 'p';
    v[0x3] = 'f';
    v[0x4] = '\'';
    v[0x5] = 'e';
    v[0x6] = 'k';
    v[0x7] = 'e';
    v[0x8] = 'k';
    v[0x9] = '"';
    v[0xa] = 'X';
    v[0xb] = 'B';
    v[0xc] = 'S';
    v[0xd] = '}';
    v[0xe] = 's';
    v[0xf] = 's';
    v[0x10] = 'r';
    v[0x11] = 'N';
    v[0x12] = 'l';
    v[0x13] = 'e';
    v[0x14] = 'a';
    v[0x15] = 'c';
    v[0x16] = 'u';
    for (unsigned int i = 0; i < 0x17; ++i) {
        v[i] ^= ((i + 0x17) % m);
    }
    v[0x17] = '\0';
}

static inline void fill_de_robv_android_xposed_XposedBridge(char v[]) {
    // de/robv/android/xposed/XposedBridge
    static unsigned int m = 0;

    if (m == 0) {
        m = 31;
    }

    v[0x0] = '`';
    v[0x1] = '`';
    v[0x2] = ')';
    v[0x3] = 'u';
    v[0x4] = 'g';
    v[0x5] = 'k';
    v[0x6] = '|';
    v[0x7] = '$';
    v[0x8] = 'm';
    v[0x9] = 'c';
    v[0xa] = 'j';
    v[0xb] = '}';
    v[0xc] = '\x7f';
    v[0xd] = 'x';
    v[0xe] = 'v';
    v[0xf] = '<';
    v[0x10] = 'l';
    v[0x11] = 'e';
    v[0x12] = 'y';
    v[0x13] = 'd';
    v[0x14] = '}';
    v[0x15] = '}';
    v[0x16] = '5';
    v[0x17] = 'C';
    v[0x18] = 'l';
    v[0x19] = 'r';
    v[0x1a] = 'm';
    v[0x1b] = 'e';
    v[0x1c] = 'e';
    v[0x1d] = '@';
    v[0x1e] = 'q';
    v[0x1f] = 'm';
    v[0x20] = 'a';
    v[0x21] = 'a';
    v[0x22] = 'b';
    for (unsigned int i = 0; i < 0x23; ++i) {
        v[i] ^= ((i + 0x23) % m);
    }
    v[0x23] = '\0';
}

static inline void fill_findLoadedClass(char v[]) {
    // findLoadedClass
    static unsigned int m = 0;

    if (m == 0) {
        m = 13;
    }

    v[0x0] = 'd';
    v[0x1] = 'j';
    v[0x2] = 'j';
    v[0x3] = 'a';
    v[0x4] = 'J';
    v[0x5] = 'h';
    v[0x6] = 'i';
    v[0x7] = 'm';
    v[0x8] = 'o';
    v[0x9] = 'o';
    v[0xa] = 'O';
    v[0xb] = 'l';
    v[0xc] = '`';
    v[0xd] = 'q';
    v[0xe] = 'p';
    for (unsigned int i = 0; i < 0xf; ++i) {
        v[i] ^= ((i + 0xf) % m);
    }
    v[0xf] = '\0';
}

static inline void fill_findLoadedClass_signature(char v[]) {
    // (Ljava/lang/ClassLoader;Ljava/lang/String;)Ljava/lang/Class;
    static unsigned int m = 0;

    if (m == 0) {
        m = 59;
    }

    v[0x0] = ')';
    v[0x1] = 'N';
    v[0x2] = 'i';
    v[0x3] = 'e';
    v[0x4] = 's';
    v[0x5] = 'g';
    v[0x6] = '(';
    v[0x7] = 'd';
    v[0x8] = 'h';
    v[0x9] = 'd';
    v[0xa] = 'l';
    v[0xb] = '#';
    v[0xc] = 'N';
    v[0xd] = 'b';
    v[0xe] = 'n';
    v[0xf] = 'c';
    v[0x10] = 'b';
    v[0x11] = '^';
    v[0x12] = '|';
    v[0x13] = 'u';
    v[0x14] = 'q';
    v[0x15] = 's';
    v[0x16] = 'e';
    v[0x17] = '#';
    v[0x18] = 'U';
    v[0x19] = 'p';
    v[0x1a] = 'z';
    v[0x1b] = 'j';
    v[0x1c] = '|';
    v[0x1d] = '1';
    v[0x1e] = 's';
    v[0x1f] = 'A';
    v[0x20] = 'O';
    v[0x21] = 'E';
    v[0x22] = '\x0c';
    v[0x23] = 'w';
    v[0x24] = 'Q';
    v[0x25] = 'T';
    v[0x26] = 'N';
    v[0x27] = 'F';
    v[0x28] = 'N';
    v[0x29] = '\x11';
    v[0x2a] = '\x02';
    v[0x2b] = '`';
    v[0x2c] = 'G';
    v[0x2d] = 'O';
    v[0x2e] = 'Y';
    v[0x2f] = 'Q';
    v[0x30] = '\x1e';
    v[0x31] = '^';
    v[0x32] = 'R';
    v[0x33] = 'Z';
    v[0x34] = 'R';
    v[0x35] = '\x19';
    v[0x36] = 't';
    v[0x37] = 'T';
    v[0x38] = 'X';
    v[0x39] = 'I';
    v[0x3a] = 's';
    v[0x3b] = ':';
    for (unsigned int i = 0; i < 0x3c; ++i) {
        v[i] ^= ((i + 0x3c) % m);
    }
    v[0x3c] = '\0';
}

jclass findLoadedClass(JNIEnv *env, jobject classLoader, const char *name) {
    char v1[0x80], v2[0x80];
    jclass loadedClass = NULL;

    fill_java_lang_VMClassLoader(v1);
    jclass vmClassLoader = (*env)->FindClass(env, v1);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
    if (vmClassLoader == NULL) {
        goto clean;
    }

    fill_findLoadedClass(v1);
    fill_findLoadedClass_signature(v2);
    jmethodID findLoadedClass = (*env)->GetStaticMethodID(env, vmClassLoader, v1, v2);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
    if (findLoadedClass == NULL) {
        goto cleanVmClassLoader;
    }

    jstring string = (*env)->NewStringUTF(env, name);
    // NewStringUTF 可为 NULL(OOM/编码失败): 不判空传入 CallStaticObjectMethod
    // 会抛 NPE, 虽被下方 ExceptionCheck 清掉, 但依赖"异常必被清除"的隐式链路
    if (string == NULL) {
        goto cleanVmClassLoader;
    }
    loadedClass = (jclass) (*env)->CallStaticObjectMethod(env,
                                                          vmClassLoader,
                                                          findLoadedClass,
                                                          classLoader,
                                                          string);

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }

    (*env)->DeleteLocalRef(env, string);
cleanVmClassLoader:
    (*env)->DeleteLocalRef(env, vmClassLoader);
clean:
    return loadedClass;
}

jclass findXposedBridge(JNIEnv *env, jobject classLoader) {
    char v1[0x80];
    fill_de_robv_android_xposed_XposedBridge(v1);
    return findLoadedClass(env, classLoader, v1);
}
