package gm.tieba.tabswitch.hooker.add

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.TextView
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.AcRules.findRule
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.hooker.Obfuscated
import gm.tieba.tabswitch.hooker.deobfuscation.Matcher
import gm.tieba.tabswitch.hooker.deobfuscation.SmaliMatcher
import gm.tieba.tabswitch.util.fixAlertDialogWidth
import gm.tieba.tabswitch.util.getCurrentActivity
import gm.tieba.tabswitch.util.getDialogTheme
import gm.tieba.tabswitch.util.getObjectField
import gm.tieba.tabswitch.util.getTbadkCoreApplicationInst
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.query.matchers.MethodsMatcher

class SelectClipboard : XposedContext(), IHooker, Obfuscated {

    override fun key(): String {
        return "select_clipboard"
    }

    override fun matchers(): List<Matcher> {
        return listOf(
            SmaliMatcher("Landroid/text/ClipboardManager;->setText(Ljava/lang/CharSequence;)V").apply {
                classMatcher = ClassMatcher.create().methods(
                    MethodsMatcher.create().add(
                        MethodMatcher.create().addInvoke(
                            MethodMatcher.create()
                                .descriptor("Lcom/baidu/tbadk/core/data/SmallTailInfo;-><init>()V")
                        )
                    )
                )
            }
        )
    }

    override fun hook() {
        findRule(matchers()) { matcher, clazz, method ->
            when (matcher) {
                "Landroid/text/ClipboardManager;->setText(Ljava/lang/CharSequence;)V" ->
                    hookReplaceMethod(clazz, method) { param ->
                        // replace 抛出的异常会向宿主调用方重抛: 宿主版本变化导致
                        // TbRichText 字段缺失等情况时降级为"直接放行原逻辑不可行(replace 无法回退)",
                        // 这里就地吞掉并记日志, 绝不让异常沿宿主调用栈传播
                        // 对话框是否已弹出: show() 之后的异常不应再回退原复制
                        var dialogShown = false
                        try {
                            val tbRichText = getObjectField(
                                param.thisObject,
                                "com.baidu.tbadk.widget.richText.TbRichText"
                            )
                            val currentActivity = getCurrentActivity()
                            // 无前台 Activity 时对话框无处依附: replace 模式下原方法已不会
                            // 执行, 必须显式调回原方法, 否则复制操作被静默吞掉
                            if (currentActivity == null) {
                                return@hookReplaceMethod gm.tieba.tabswitch.bridge.XposedBridge.invokeOriginalMethod(
                                    param.method, param.thisObject, param.args
                                )
                            }

                            AlertDialog.Builder(
                                currentActivity,
                                getDialogTheme(getContext())
                            )
                                .setTitle("自由复制")
                                .setMessage(tbRichText.toString())
                                .setNeutralButton("复制全部") { _, _ ->
                                    // 按钮回调由宿主框架直接调用, 不经 hook 包装层:
                                    // toString/剪贴板写入抛异常会沿宿主主线程击穿,
                                    // 就地吞掉并 toast 降级(与 TSPreference 点击监听防护范式一致)
                                    runCatching {
                                        val clipboardManager =
                                            getTbadkCoreApplicationInst().getSystemService(
                                                Context.CLIPBOARD_SERVICE
                                            ) as ClipboardManager
                                        clipboardManager.setPrimaryClip(ClipData.newPlainText("tieba", tbRichText.toString()))
                                    }.onFailure {
                                        gm.tieba.tabswitch.util.Logger.log("SelectClipboard 复制全部失败: $it")
                                    }
                                }
                                .setPositiveButton("完成", null)
                                .create()
                                .apply {
                                    show()
                                    dialogShown = true
                                    findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
                                    // show() 之后的修补步骤单独隔离: 此处失败不应再回退原复制,
                                    // 否则用户同时看到"自由复制"对话框和原 setText 副作用, 双重行为
                                    runCatching { fixAlertDialogWidth(this) }
                                        .onFailure { gm.tieba.tabswitch.util.Logger.log("SelectClipboard 对话框宽度修补失败: $it") }
                                }
                            null
                        } catch (tr: Throwable) {
                            gm.tieba.tabswitch.util.Logger.log("SelectClipboard 对话框展示失败: $tr")
                            // 降级为原复制行为: replace 已使原方法丢失, 失败时手动回退,
                            // 避免"对话框失败 + 原复制也没了"双重功能损失。
                            // 仅在对话框未弹出时回退: 已弹出的情况下原副作用叠加是双重行为
                            if (!dialogShown) {
                                runCatching {
                                    gm.tieba.tabswitch.bridge.XposedBridge.invokeOriginalMethod(
                                        param.method, param.thisObject, param.args
                                    )
                                    // 二级回退也失败时复制操作会静默消失, 必须留线索
                                }.onFailure { gm.tieba.tabswitch.util.Logger.log("SelectClipboard 原复制回退失败: $it") }
                            }
                            null
                        }
                    }
            }
        }
    }
}
