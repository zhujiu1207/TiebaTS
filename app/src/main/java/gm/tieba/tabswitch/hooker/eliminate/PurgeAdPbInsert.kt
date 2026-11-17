package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.XposedContext.Companion.findClass
import gm.tieba.tabswitch.XposedContext.Companion.hookReplaceMethod
import gm.tieba.tabswitch.XposedContext.Companion.safeConstantFor
import java.lang.reflect.Modifier

// Pb 渲染前广告插入/首楼推荐/掉落红包动画。从 PurgeAd 原文搬移（逐字保留注释）。

internal object PurgeAdPbInsert {

    // PbAdapterManagerInsertUtilKt(≤22.11): Pb 页渲染前插入 funad 的三个工具方法
    // (精确签名 fast-path)。22.12 起该类移除, 插入逻辑并入 LegacyHeaderBusinessKt
    // (funad/banner 卡插入方法形状: (ArrayList, int, PbData, ThreadData, …) 与
    // (ArrayList, int, PbData, ILegacyBusiness), 见 blockLegacyHeaderAdInsert)
    internal fun blockPbEarlyAdInsert() {
        var hookedAny = false
        try {
            val utilClass = findClass(PurgeAdKeys.PB_AD_INSERT_UTIL)
            val paramSpecs = listOf(
                listOf(PurgeAdKeys.PB_DATA_CLASS, PurgeAdKeys.PB_FRAGMENT_CLASS, "boolean"),
                listOf("java.util.ArrayList", "boolean", "java.util.ArrayList", PurgeAdKeys.PB_FRAGMENT_CLASS, PurgeAdKeys.PB_DATA_CLASS),
                listOf("java.util.ArrayList", "int", PurgeAdKeys.PB_DATA_CLASS, PurgeAdKeys.THREAD_DATA_CLASS, PurgeAdKeys.PB_FRAGMENT_CLASS)
            )
            val hooked = HashSet<java.lang.reflect.Method>()
            for (md in utilClass.declaredMethods) {
                if (!Modifier.isStatic(md.modifiers)) continue
                val matched = paramSpecs.any { spec ->
                    spec.size == md.parameterTypes.size &&
                        spec.zip(md.parameterTypes.map { it.name }).all { (want, have) -> want == have }
                }
                if (matched && hooked.add(md)) {
                    // 按返回类型安全化: 匹配只看参数形状, 宿主更新后若出现同形状的
                    // primitive 返回重载, replace null 会在宿主调用点拆箱 NPE
                    hookReplaceMethod(md) {
                        when {
                            android.util.SparseArray::class.java.isAssignableFrom(md.returnType) ->
                                android.util.SparseArray<Any>()
                            md.returnType == Void.TYPE -> null
                            md.returnType == Boolean::class.javaPrimitiveType ||
                                md.returnType == java.lang.Boolean::class.java -> false
                            md.returnType == Int::class.javaPrimitiveType ||
                                md.returnType == java.lang.Integer::class.java -> 0
                            else -> null
                        }
                    }
                    hookedAny = true
                }
            }
        } catch (ignored: Throwable) {
        }
        if (!hookedAny) {
            blockLegacyHeaderAdInsert()
        }
    }

    // 22.12+: LegacyHeaderBusinessKt 的渲染前广告卡插入方法。方法名已混淆,
    // PbData/ILegacyBusiness 类型也被改名, 但两个"锚点方法"形状稳定且唯一:
    //   首楼: (PbData, PostData, List, int, PostData) → 由 param[0] 取得 PbData 类型
    //   判定: (PbData, List, int, ILegacyBusiness) 的静态布尔方法 → 由 param[3] 取得
    //         ILegacyBusiness 类型
    // 再按形状定位插入方法(参数含稳定类型 ThreadData 的 funad/banner 插入):
    //   funad 卡: 静态, [0]=ArrayList, [1]=int, [2]=PbData, [3]=ThreadData, 非空返回
    //             (22.12 为 p/i/k 三个, [2] 均为 PbData, 校验该槽位防止版本漂移后
    //             同形状的无关方法被误 no-op 致宿主解引用 NPE)
    //   banner 卡: 静态, 4 参 (ArrayList, int, PbData, ILegacyBusiness), 非空返回 (m)
    // 替换为返回安全零值(按方法实际返回类型派发), 插入即被阻断
    private fun blockLegacyHeaderAdInsert() {
        try {
            val owner = findClass(PurgeAdKeys.PB_LEGACY_HEADER_UTIL)
            val postDataClass = findClass(PurgeAdKeys.PB_POST_DATA_CLASS)
            val threadDataClass = findClass(PurgeAdKeys.THREAD_DATA_CLASS)
            val listType = java.util.List::class.java
            val rawListType = findClass("java.util.ArrayList")
            val intType = Int::class.javaPrimitiveType

            // 锚点1: 首楼形状(与 blockPbFirstFloorRecommend 同一方法) → PbData 类型
            val firstFloor = owner.declaredMethods.filter { md ->
                Modifier.isStatic(md.modifiers) &&
                    md.returnType == Boolean::class.javaPrimitiveType &&
                    md.parameterTypes.size == 5 &&
                    md.parameterTypes[1] == postDataClass &&
                    md.parameterTypes[2] == listType &&
                    md.parameterTypes[3] == intType &&
                    md.parameterTypes[4] == postDataClass
            }
            val pbDataClass = firstFloor.singleOrNull()?.parameterTypes?.getOrNull(0)

            // 锚点2: (PbData, List, int, ILegacyBusiness) 静态布尔 → ILegacyBusiness 类型
            var legacyBusinessClass: Class<*>? = null
            if (pbDataClass != null) {
                legacyBusinessClass = owner.declaredMethods.filter { md ->
                    Modifier.isStatic(md.modifiers) &&
                        md.returnType == Boolean::class.javaPrimitiveType &&
                        md.parameterTypes.size == 4 &&
                        md.parameterTypes[0] == pbDataClass &&
                        md.parameterTypes[1] == listType &&
                        md.parameterTypes[2] == intType
                }.singleOrNull()?.parameterTypes?.getOrNull(3)
            }
            if (pbDataClass == null) return

            for (md in owner.declaredMethods) {
                if (!Modifier.isStatic(md.modifiers)) continue
                if (md.returnType.isPrimitive) continue
                val params = md.parameterTypes
                val isFunAdInsert = params.size >= 4 &&
                    params[0] == rawListType &&
                    params[1] == intType &&
                    params[2] == pbDataClass &&
                    params[3] == threadDataClass
                val isBannerInsert = legacyBusinessClass != null &&
                    params.size == 4 &&
                    params[0] == rawListType &&
                    params[1] == intType &&
                    params[2] == pbDataClass &&
                    params[3] == legacyBusinessClass
                if (isFunAdInsert || isBannerInsert) {
                    runCatching {
                        hookReplaceMethod(md) { safeConstantFor(md.returnType, 0, false) }
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    // 首楼推荐: LegacyHeaderBusinessKt 中 (…, PostData, List, int, PostData) 的静态布尔方法
    internal fun blockPbFirstFloorRecommend() {
        try {
            val owner = findClass(PurgeAdKeys.PB_LEGACY_HEADER_UTIL)
            val postDataClass = findClass(PurgeAdKeys.PB_POST_DATA_CLASS)
            val listType = java.util.List::class.java
            val candidates = owner.declaredMethods.filter { md ->
                Modifier.isStatic(md.modifiers) &&
                    md.returnType == Boolean::class.javaPrimitiveType &&
                    md.parameterTypes.size == 5 &&
                    md.parameterTypes[1] == postDataClass &&
                    md.parameterTypes[2] == listType &&
                    md.parameterTypes[3] == Int::class.javaPrimitiveType &&
                    md.parameterTypes[4] == postDataClass
            }
            if (candidates.size == 1) {
                hookReplaceMethod(candidates[0]) { false }
            }
        } catch (ignored: Throwable) {
        }
    }

    // 掉落红包/广告动画: PbFallingView 的展示入口直接 no-op。
    // 此前对 init/show 压扁(GONE+0×0)不可靠: 展示是两段式的——C() 先校验,
    // 位图异步加载完成后 D() 会 setVisibility(0) 重新显示并启动动画, 压扁被
    // 宿主覆盖导致"有时仍掉落"。改为按形状替换展示方法(void, 4 参, [2]=int,
    // [3]=boolean, 22.11/22.12 均为 C), no-op 后展示链路整体截断; 多候选时按
    // 惯用名 C 兜底。t.onAnimationEnd 回调(u4 恢复编辑栏)仅在动画结束后触发,
    // 无动画则无状态需要恢复, no-op 无副作用; init(Context) 保留压扁兜底
    internal fun blockPbFallingAd() {
        try {
            val viewClass = findClass(PurgeAdKeys.PB_FALLING_VIEW)
            val methods = viewClass.declaredMethods.filter { md ->
                !Modifier.isStatic(md.modifiers) && md.returnType == Void.TYPE
            }
            val initMethods = methods.filter {
                it.parameterTypes.size == 1 &&
                    android.content.Context::class.java.isAssignableFrom(it.parameterTypes[0])
            }
            val showCandidates = methods.filter {
                it.parameterTypes.size == 4 &&
                    it.parameterTypes[2] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[3] == Boolean::class.javaPrimitiveType
            }
            initMethods.forEach { md ->
                runCatching {
                    gm.tieba.tabswitch.XposedContext.Companion.hookBeforeMethod(md) { param ->
                        squashAdView(param.thisObject)
                    }
                }
            }
            val show = showCandidates.singleOrNull()
                ?: showCandidates.firstOrNull { it.name == PurgeAdKeys.PB_FALLING_SHOW }
            show?.let { md ->
                runCatching { hookReplaceMethod(md) { null } }
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun squashAdView(view: Any?) {
        val v = view as? android.view.View ?: return
        try {
            v.visibility = android.view.View.GONE
            v.minimumHeight = 0
            v.minimumWidth = 0
            val lp = v.layoutParams
            if (lp != null) {
                lp.width = 0
                lp.height = 0
                v.layoutParams = lp
            }
        } catch (ignored: Throwable) {
        }
    }
}
