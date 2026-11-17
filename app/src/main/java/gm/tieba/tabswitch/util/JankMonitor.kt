package gm.tieba.tabswitch.util

import android.app.ActivityManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock

/**
 * 主线程卡顿看门狗: 每秒向主线程投递自检任务, 任务实际执行晚于计划超过阈值即
 * 判定期间发生了卡顿, 由看门狗线程立刻采样主线程堆栈写入模块日志(节流防刷屏)。
 * 用户感觉卡顿时无需任何操作, 日志中已有卡顿瞬间的现场堆栈。
 *
 * 采样必须在观察者线程: probe 本身跑在主线程上, 它执行时卡顿已经结束,
 * 采到的栈顶永远是 probe 自己; 检测到超时后立刻从后台线程抓主线程堆栈,
 * 才能留下卡顿期间(常是仍在执行的卡顿任务)的现场。
 *
 * 开销: 每秒一次 postDelayed + 一次时间差计算, 可忽略; 日志开关关闭时只算不写。
 * 计时用 uptimeMillis: 不含深度休眠, 锁屏灭屏不会误报。
 */
object JankMonitor {
    private const val CHECK_INTERVAL_MS = 1000L
    // 用户可感知卡顿约在单帧 100ms 级; 记录阈值取更高, 只抓"明显卡住"的事件
    private const val BLOCK_THRESHOLD_MS = 400L
    private const val MIN_LOG_INTERVAL_MS = 10_000L
    private const val MAX_STACK_LINES = 60

    // 冻结判定阈值: 真机上实测进程被 cached-processor 冻结(或 doze)后 probe
    // 与看门狗线程一起停摆, 恢复运行时首跑把整个冻结期算成 overrun, 出现
    // 数十分钟至数小时的"卡顿"假阳性(真机日志 106 条卡顿约七成属此类,
    // 栈顶清一色 nativePollOnce)。真实主线程卡顿远达不到分钟级, 超此阈值的
    // 必为"探测间隙进程未运行", 只记一行归因不采栈
    private const val FROZEN_SUSPECT_MS = 60_000L

    private val sHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var sStarted = false

    @Volatile
    private var sLastLogAt = 0L

    // 卡顿检测与采样解耦的看门狗线程: 常驻阻塞, 仅在判定卡顿时被唤醒采样
    private val sSampler = java.util.concurrent.SynchronousQueue<Long>()

    fun start() {
        if (sStarted) return
        sStarted = true
        // 采样线程: 阻塞等待"卡顿发生"信号, 收到后立刻抓主线程堆栈。
        // 主线程卡顿期间观察者线程不受影响, 采到的是卡顿现场而非结束后状态
        Thread({
            while (true) {
                try {
                    val blockMs = sSampler.take()
                    logJank(blockMs)
                } catch (ignored: InterruptedException) {
                } catch (ignored: Throwable) {
                }
            }
        }, "TiebaTS-JankSampler").apply {
            isDaemon = true
            start()
        }
        val probe = object : Runnable {
            private var lastRunAt = 0L
            override fun run() {
                try {
                    val now = SystemClock.uptimeMillis()
                    if (lastRunAt != 0L) {
                        // 计划间隔之外的超出部分 = 主线程被其它工作占用的时间
                        val overrun = now - lastRunAt - CHECK_INTERVAL_MS
                        if (overrun > BLOCK_THRESHOLD_MS) {
                            // offer 不阻塞: 采样丢失只损失一次记录, 不能反过来拖住主线程
                            sSampler.offer(overrun)
                        }
                    }
                    lastRunAt = now
                } catch (ignored: Throwable) {
                }
                // 无论是否卡顿都继续投递: 下一个周期重新自检
                sHandler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
        sHandler.postDelayed(probe, CHECK_INTERVAL_MS)
    }

    // 进程是否处于用户可见状态: 冻结恢复假阳性只会发生在后台/缓存进程,
    // 前台进程冻结即用户可感知的异常, 仍按卡顿记录。
    // 查询失败按前台处理(保留记录), 漏记远比误记难排查
    private fun isProcessForeground(): Boolean = try {
        val am = gm.tieba.tabswitch.XposedContext.getContext()
            .getSystemService(ActivityManager::class.java)
        val importance = am.getRunningAppProcesses()
            ?.firstOrNull { it.pid == Process.myPid() }?.importance
        importance == null ||
            importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    } catch (ignored: Throwable) {
        true
    }

    // 运行在采样线程: 主线程此刻多半仍卡着, 抓到的即卡顿现场
    private fun logJank(blockMs: Long) {
        // 日志开关关闭时不记录(与运行日志开关语义一致): 判定提前到最前,
        // 冻结路径的 isProcessForeground() 是 binder IPC, 关闭日志时不应白付
        if (!Logger.isEnabled()) return
        val now = SystemClock.uptimeMillis()
        if (now - sLastLogAt < MIN_LOG_INTERVAL_MS) return
        // 冻结恢复假阳性: 超大 overrun 且进程不在前台时, 主线程并无卡顿——
        // 是进程整体被冻结, probe 与看门狗一起停摆。单行归因不留堆栈,
        // 避免此前"每条冻结 9 帧栈占日志 1/3 体量"的噪音
        if (blockMs > FROZEN_SUSPECT_MS && !isProcessForeground()) {
            sLastLogAt = now
            Logger.log(
                "主线程探测中断 ~${blockMs}ms, 进程此刻不在前台(判定为冻结/后台挂起恢复, 非卡顿, 不采样)"
            )
            return
            // 前台进程出现分钟级"卡顿": 极罕见但值得留全栈观察, 落到下方全栈路径
        }
        sLastLogAt = now
        try {
            val stack = Looper.getMainLooper().thread.stackTrace
            val sb = StringBuilder("===== 主线程卡顿 ~")
                .append(blockMs).append("ms (pid=").append(Process.myPid()).append(") =====")
            var lines = 0
            for (el in stack) {
                if (lines >= MAX_STACK_LINES) {
                    sb.append("\n  ...(截断,共").append(stack.size).append("帧)")
                    break
                }
                sb.append("\n  at ").append(el)
                lines++
            }
            Logger.log(sb.toString())
        } catch (ignored: Throwable) {
        }
    }
}
