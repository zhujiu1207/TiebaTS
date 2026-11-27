@file:JvmName("DisplayUtils")

package gm.tieba.tabswitch.util

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.WindowManager
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import kotlin.math.roundToInt
import kotlin.system.exitProcess

fun isLightMode(context: Context): Boolean {
    return context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_NO
}

fun restart(activity: Activity) {
    val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
    intent?.let {
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(it)
        exitProcess(0)
    } ?: run {
        // 理论上宿主自身不会走到这里, 但静默失败会让"重启贴吧"按钮毫无反应
        // 且无任何日志线索(调用方: 反混淆完成收尾/规则异常弹窗)
        gm.tieba.tabswitch.util.Logger.log("restart: getLaunchIntentForPackage 返回 null, 重启未执行")
    }
}

fun getTbSkin(context: Context): String {
    //Lcom/baidu/tbadk/core/TbadkCoreApplication;->getSkinType()I
    // 反射失败时 XposedHelpers 抛的是 NoSuchMethodError(Error), 用 Throwable 才能兜住
    val skinType: Int = try {
        val instance = getTbadkCoreApplicationInst()
        XposedHelpers.callMethod(instance, "getSkinType") as Int
    } catch (e: Throwable) {
        XposedBridge.log(e)
        // 回退路径自身不能再抛: SP 键被宿主以其他类型写入时 getBoolean/getString
        // 抛 ClassCastException, 恰好在最需要兜底的版本上把整个设置页打死
        return try {
            val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (settings.getBoolean("key_is_follow_system_mode", false)) {
                if (isLightMode(context)) "" else "_2"
            } else {
                val commonSettings = context.getSharedPreferences(
                    "common_settings", Context.MODE_PRIVATE
                )
                when (commonSettings.getString("skin_", "0")?.toIntOrNull() ?: 0) {
                    1, 4 -> "_2"
                    else -> ""
                }
            }
        } catch (ignored: Throwable) {
            ""
        }
    }
    return when (skinType) {
        1, 4 -> "_2"
        else -> ""
    }
}

fun dipToPx(context: Context, dipValue: Float): Int {
    val scale = context.resources.displayMetrics.density
    return (dipValue * scale).roundToInt()
}

fun pxToDip(context: Context, pxValue: Float): Int {
    val scale = context.resources.displayMetrics.density
    return (pxValue / scale).roundToInt()
}

fun getDisplayWidth(context: Context): Int? {
    return context.resources?.displayMetrics?.widthPixels
}

fun fixAlertDialogWidth(alert: AlertDialog) {
    alert.window?.let {
        val layoutParams = WindowManager.LayoutParams()
        layoutParams.copyFrom(it.attributes)
        getDisplayWidth(XposedContext.getContext())?.let { displayWidth ->
            layoutParams.width = displayWidth
        }
        it.attributes = layoutParams
    }
}

fun getDialogTheme(context: Context): Int = getDialogTheme(isLightMode(context))

fun getDialogTheme(isLightMode: Boolean): Int =
    if (isLightMode) android.R.style.Theme_DeviceDefault_Light_Dialog_Alert else android.R.style.Theme_DeviceDefault_Dialog_Alert
