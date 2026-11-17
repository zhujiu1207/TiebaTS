@file:JvmName("ProcessUtils")

package gm.tieba.tabswitch.util

import android.os.Build

/**
 * 当前进程名: API 28+ 走 Application.getProcessName(), 低版本回退反射
 * ActivityThread.currentProcessName()。
 * 取不到返回 null(调用方自行决定兜底语义), 不抛异常——进程名是诊断信息,
 * 解析失败不能阻断调用方主流程。
 */
fun currentProcessName(): String? = try {
    if (Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName() else null
} catch (ignored: Throwable) {
    null
} ?: try {
    Class.forName("android.app.ActivityThread")
        .getMethod("currentProcessName").invoke(null) as? String
} catch (ignored: Throwable) {
    null
}
