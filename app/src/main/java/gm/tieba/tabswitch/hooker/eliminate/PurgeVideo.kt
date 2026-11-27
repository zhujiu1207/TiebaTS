package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.hooker.Obfuscated
import gm.tieba.tabswitch.hooker.deobfuscation.Matcher
import gm.tieba.tabswitch.dao.AcRules.findRule
import gm.tieba.tabswitch.logBoth
import org.luckypray.dexkit.query.matchers.ClassMatcher

class PurgeVideo : XposedContext(), IHooker, Obfuscated {

    override fun key(): String {
        return "purge_video"
    }

    override fun matchers(): List<Matcher> {
        return listOf(
            // 22.11+: 视频广告组件 wn9.b(FeedVideoAdComponent,...) 组装独立的视频广告卡。
            // 类内唯一组合特征为 "videoSchemaData"+"download_bar", 方法体含 "download_bar"
            object : Matcher("purge_video_ad_video_card") {
                override val methodMatcher =
                    org.luckypray.dexkit.query.matchers.MethodMatcher.create().usingStrings("download_bar")
            }.apply {
                classMatcher = ClassMatcher.create().usingStrings("videoSchemaData", "download_bar")
                requiredVersion = "22.11.0.0"
            }
        )
    }

    override fun hook() {
        // 22.11+: 首页推荐流为模板卡片体系, 一个帖子是合成卡(common_card), 其数据载荷内含
        // 内层卡列表(feed_head/title/video/social_bar...), 视频只是内层卡之一 (实测日志证实)。
        // 仅按 key 过滤会只删掉内层 video 卡、留下帖子骨架; 参照 ForbidAd4TieBa 的做法,
        // 须在列表入口检查合成卡载荷内的内层卡, 命中 video 键则整帖移除。
        // 注意: 不能再像旧实现那样把视频组件装配方法置空, 否则合成卡内没有 video 卡,
        // 整帖判定失去依据, 只会留下帖子骨架。
        // 列表入口 hook 统一走 FeedListPipeline(单 hook 多阶段链), 此处只注册阶段
        FeedListPipeline.register { list, _, _ -> filterTemplateList(list) }

        // 22.11+: 视频广告为独立顶层卡, 禁用其装配入口。
        // 规则库只存"类名+方法名", 命中的装配方法可能带参: findAndHookMethod 按名
        // 无参精确匹配带参方法抛 NoSuchMethodError 被吞, 装配层静默失效(仅剩
        // L/P/R 过滤层)。参照 Purge.kt 插屏分支: 枚举同名全部重载逐个替换;
        // 返回值按方法实际返回类型派发安全零值(基本类型返回 null 会拆箱崩溃)
        findRule("purge_video_ad_video_card") { _, clazz, method ->
            try {
                val targets = findClass(clazz).declaredMethods.filter { it.name == method }
                if (targets.isEmpty()) {
                    logBoth("PurgeVideo: 视频广告装配入口拦截失败 -- $clazz#$method 无可匹配方法")
                }
                targets.forEach { md ->
                    runCatching {
                        hookReplaceMethod(md) { safeConstantFor(md.returnType, 0, false) }
                    }.onFailure {
                        logBoth("PurgeVideo: 视频广告装配入口拦截失败 -- ${md.name}${md.parameterTypes.contentToString()} -- $it")
                    }
                }
            } catch (ignored: Throwable) {
            }
        }

        // 显示层兜底: 视频帖卡片组件(CardVideoView)挂载时隐藏整个列表项,
        // 卸载时恢复, 防止ViewHolder复用导致普通帖被误隐藏
        try {
            val videoViewClass = findClass("com.baidu.tieba.feed.component.CardVideoView")
            hookAfterMethod(videoViewClass, "onAttachedToWindow") { param ->
                val v = param.thisObject as android.view.View
                if (v.visibility == android.view.View.VISIBLE) {
                    recyclerViewItemRoot(v)?.visibility = android.view.View.GONE
                }
            }
            hookAfterMethod(videoViewClass, "onDetachedFromWindow") { param ->
                val v = param.thisObject as android.view.View
                recyclerViewItemRoot(v)?.let { root ->
                    if (root.visibility != android.view.View.VISIBLE) {
                        root.visibility = android.view.View.VISIBLE
                    }
                }
            }
        } catch (ignored: Throwable) {
        }

        // 显示层兜底(22.11 首页模板卡): 视频贴由 HomePageCardVideoViewHolder 渲染,
        // 其基类构造(CardViewHolder(tj8))时 itemView 即整个列表项, 直接隐藏。
        // 该 ViewHolder 只会复用于视频贴, 无误隐普通帖风险, 无需恢复
        try {
            val cardViewHolderClass = findClass("com.baidu.tieba.card.holder.CardViewHolder")
            val videoHolderClass =
                findClass("com.baidu.tieba.homepage.personalize.adapter.HomePageCardVideoViewHolder")
            // 构造参数类名随版本混淆轮换(nl8/pj8/yk8/nm8/rm8), 混淆字典跨版本漂移:
            // 逐候选尝试挂构造钩子(类存在但构造签名不符时继续下一个),
            // 全部失败时静默放弃(兜底链前置的 CardVideoView 钩子仍在)。
            // CardViewHolder 泛型化为 <T extends 卡片视图基类>: 22.12 为 yk8,
            // 22.13 轮换为 nl8, 22.14 轮换为 ol8(同为 T extends BaseCardInfo),
            // 22.15 轮换为 nm8(abstract, T extends BaseCardInfo; 视频卡数据类为
            // rm8 extends nm8<vn8>——nl8 在 22.14 仍存在但已退化为 ol8 的子类、
            // 22.15 的 ol8 已同名异义为 WebView 监听类, 精确参数匹配不再命中,
            // 故新名必须置首)
            for (name in listOf(
                "com.baidu.tieba.nm8", "com.baidu.tieba.rm8",
                "com.baidu.tieba.ol8", "com.baidu.tieba.nl8",
                "com.baidu.tieba.yk8", "com.baidu.tieba.pj8", "com.baidu.tieba.tj8"
            )) {
                val paramClass = runCatching { findClass(name) }.getOrNull() ?: continue
                val hooked = runCatching {
                    hookAfterConstructor(cardViewHolderClass, paramClass) { param ->
                        if (videoHolderClass.isInstance(param.thisObject)) {
                            (XposedHelpers.getObjectField(param.thisObject, "itemView") as? android.view.View)
                                ?.visibility = android.view.View.GONE
                        }
                    }
                    true
                }.getOrDefault(false)
                if (hooked) break
            }
        } catch (ignored: Throwable) {
        }
    }

    // 22.11+: 模板键 → 视频贴判定
    private val VIDEO_TEMPLATE_KEYS = setOf("video", "video_card", "staggered_video")

    // 在适配器列表入口处过滤视频模板卡; 返回原列表表示无需修改。
    // 骨架共享于 Fs9Api.filterByCardKeys(原手写主循环已收编):
    // 独立视频卡直接移除; 合成卡载荷内的内层卡含视频则整帖移除。
    // payloadCheckAllOuter=true 保持原实现对任意非命中键条目都探测载荷的口径
    // (home_delegate_card 等其它外壳合成卡内嵌视频时不可漏)
    private fun filterTemplateList(list: List<*>?): List<*>? =
        Fs9Api.filterByCardKeys(list, VIDEO_TEMPLATE_KEYS, payloadCheckAllOuter = true) { payload ->
            Fs9Api.payloadHasKey(payload, VIDEO_TEMPLATE_KEYS)
        }

    // RecyclerView 类按缓存复用: 视频卡 attach/detach(列表滚动时高频)的父链
    // 上每层都 findClass 属重复固定开销, 解析一次即可(解析失败由 lazy 重试)
    private val sRecyclerViewClass by lazy {
        runCatching { findClass("androidx.recyclerview.widget.RecyclerView") }.getOrNull()
    }

    // 沿父链找到 RecyclerView 的直接子项(即列表项根视图)
    private fun recyclerViewItemRoot(view: android.view.View): android.view.View? {
        val recyclerViewClass = sRecyclerViewClass ?: return null
        var current = view.parent as? android.view.View ?: return null
        while (true) {
            val parent = current.parent ?: return null
            // isInstance 而非类名精确相等: 宿主常用 RecyclerView 自定义子类;
            // 经宿主 ClassLoader 解析(recyclerview 不在模块编译期 classpath)
            if (recyclerViewClass.isInstance(parent)) {
                return current
            }
            if (parent !is android.view.View) return null
            current = parent
        }
    }
}
