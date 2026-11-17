package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.util.parsePbContent

class ContentFilter : XposedContext(), IHooker, KeywordFilter {

    override fun key(): String {
        return "content_filter"
    }

    override fun hook() {
        // 逐点隔离: 单个 Builder 类缺失(版本漂移)只损失该入口,
        // 不让其余楼层/楼中楼入口一并失效(与 UserFilter 同范式)
        fun hookSafely(tag: String, register: () -> Unit) {
            gm.tieba.tabswitch.hookSafely("ContentFilter", tag, register)
        }

        // 楼层
        hookSafely("PbPage") {
            hookBeforeMethod(
                "tbclient.PbPage.DataRes\$Builder",
                "build", Boolean::class.javaPrimitiveType
            ) { param ->
                val keywords = getKeywords()
                if (keywords.isEmpty()) return@hookBeforeMethod
                filterProtoBuilderList(param.thisObject, "post_list") { post ->
                    post != null && runCatching {
                        // floor 未下发(null)时豁免, 与 UserFilter 同语义, 避免误删主楼
                        val floorValue = XposedHelpers.getObjectField(post, "floor") as? Int
                        floorValue != null && floorValue != 1 &&
                                matchesKeyword(keywords, parsePbContent(post, "content"))
                    }.getOrDefault(false)
                }
            }
        }

        // 楼中楼
        hookSafely("SubPost") {
            hookBeforeMethod(
                "tbclient.SubPost\$Builder",
                "build", Boolean::class.javaPrimitiveType
            ) { param ->
                val keywords = getKeywords()
                if (keywords.isEmpty()) return@hookBeforeMethod
                filterProtoBuilderList(param.thisObject, "sub_post_list") { subPost ->
                    subPost != null && runCatching {
                        matchesKeyword(keywords, parsePbContent(subPost, "content"))
                    }.getOrDefault(false)
                }
            }
        }

        // 楼层回复
        hookSafely("PbFloor") {
            hookBeforeMethod(
                "tbclient.PbFloor.DataRes\$Builder",
                "build", Boolean::class.javaPrimitiveType
            ) { param ->
                val keywords = getKeywords()
                if (keywords.isEmpty()) return@hookBeforeMethod
                filterProtoBuilderList(param.thisObject, "subpost_list") { subPost ->
                    subPost != null && runCatching {
                        matchesKeyword(keywords, parsePbContent(subPost, "content"))
                    }.getOrDefault(false)
                }
            }
        }
    }
}
