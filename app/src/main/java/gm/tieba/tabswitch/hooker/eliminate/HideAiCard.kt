package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.XposedContext.Companion.findClass
import gm.tieba.tabswitch.XposedContext.Companion.hookReplaceMethod
import gm.tieba.tabswitch.hooker.IHooker

/**
 * 隐藏图片查看器的「AI加料」跳转胶囊（ImageViewerActivity 底部信息区、正文上方的
 * ✨AI加料 > 胶囊，点击经 UrlManager.dealOneLink 跳宿主 AI 页）。
 *
 * 胶囊文案/图标/跳转 scheme 全部服务端下发（2026-11-22 实证：22.14 全量反编译与
 * dex 字节层均无「AI加料」「查看原文」字面量），渲染链：
 *   服务端响应 → ltb(查看器数据准备) → ImageUrlData.imageJumpIconData
 *   (com.baidu.tbadk.coreExtra.data.ImageJumpIconData: triggerContent=胶囊文案
 *   "AI加料"/triggerIcon/scheme，ltb 负责在相册各图间传播)
 *   → 查看器底部控制器 bt6.b(ImageUrlData): jumpIconData 非空且 triggerContent
 *   非空时 setVisibility(0)+ImageJumpButtonLayout.c() 配置显示，否则 clearData+GONE
 *   → com.baidu.tbadk.coreExtra.view.ImageJumpButtonLayout(胶囊视图本体)。
 *
 * 钩 getImageJumpIconData() 恒返回 null（数据层掐断）：bt6/ltb 对该 getter 的全部
 * 调用点均先判空（bt6.b / ltb 相册传播段逐一核实），置 null 即走宿主自带的
 * clearData+GONE 分支，无 NPE 风险；该 getter 的消费方仅 bt6+ltb，胶囊视图
 * ImageJumpButtonLayout 全宿主唯一使用方为 bt6，置空不外溢其他页面。正文区
 * （FloorImageTextViewNew）由 richTextArray 独立驱动，与胶囊数据无关，不受影响。
 *
 * 钩点为宿主 coreExtra 真实类名/真实方法名（非混淆），22.11-22.14 四版本反编译
 * 核对类/字段/方法同名同签名。单点 hooker 不做逐点隔离：此点漂移抛异常由注册
 * 循环按 hooker 级记入 exceptions，设置页开关行自动呈现「此功能初始化失败」。
 */
class HideAiCard : XposedContext(), IHooker {

    override fun key(): String {
        return "hide_ai_card"
    }

    override fun hook() {
        val imageUrlDataClass = findClass("com.baidu.tbadk.coreExtra.view.ImageUrlData")
        hookReplaceMethod(imageUrlDataClass, "getImageJumpIconData") { null }
    }
}
