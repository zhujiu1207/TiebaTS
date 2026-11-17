//
// Created by Thom on 2020/9/6.
//

#include <stdlib.h>
#include <pthread.h>
#include "hash.h"

static int size;
static int index;
static intptr_t *container;

// add/clear 由 NativeCheck.findXposed 的检测线程调用: TraceChecker.checkAll
// 每次点击新起线程, 并发 add 与 clear 会产生 UAF/堆损坏, 必须串行化
static pthread_mutex_t container_lock = PTHREAD_MUTEX_INITIALIZER;

// 调用方必须已持有 container_lock
static bool clear_locked() {
    bool result = false;
    if (container) {
        free(container);
        size = 0;
        index = 0;
        container = NULL;
        result = true;
    }
    return result;
}

bool add(intptr_t hash) {
    pthread_mutex_lock(&container_lock);
    bool result;
    if (hash == 0) {
        // 必须走无锁内部实现: 持锁调用 clear() 会对同一非递归互斥量二次加锁,
        // bionic 下自死锁并永久占锁, 令所有后续 findXposed 检测全部阻塞
        result = clear_locked();
    } else {
        bool exists = false;
        for (int i = 0; i < index; ++i) {
            if (container[i] == hash) {
                exists = true;
                break;
            }
        }
        if (exists) {
            result = false;
        } else {
            if (index >= size) {
                intptr_t *next = (intptr_t *) (realloc(container, (size + 4) * sizeof(intptr_t)));
                if (next == NULL) {
                    pthread_mutex_unlock(&container_lock);
                    return false;
                }
                container = next;
                size += 4;
            }
            container[index++] = hash;
            result = true;
        }
    }
    pthread_mutex_unlock(&container_lock);
    return result;
}

bool clear() {
    pthread_mutex_lock(&container_lock);
    bool result = clear_locked();
    pthread_mutex_unlock(&container_lock);
    return result;
}
