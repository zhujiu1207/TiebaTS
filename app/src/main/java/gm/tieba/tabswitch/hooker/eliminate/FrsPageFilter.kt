package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.logBoth

class FrsPageFilter : XposedContext(), IHooker, KeywordFilter {

    override fun key(): String {
        return "frs_page_filter"
    }

    override fun hook() {
        // 逐点隔离: 单个 Builder 类缺失(版本漂移)只损失该入口。
        // 与 UserFilter 挂同一批 Builder: 保持各自独立 hook(单点隔离互不拖垮),
        // 扫描骨架已收编 filterBusinessInfoFeedList, 双份的是轻量谓词遍历
        runCatching {
            hookBeforeMethod(
                "tbclient.FrsPage.PageData\$Builder",
                "build", Boolean::class.javaPrimitiveType
            ) { param -> filterPageData(param.thisObject) }
        }.onFailure { logBoth("FrsPageFilter: hook FrsPage 失败 -- $it") }
        runCatching {
            hookBeforeMethod(
                "tbclient.ThreadList.PageData\$Builder",
                "build", Boolean::class.javaPrimitiveType
            ) { param -> filterPageData(param.thisObject) }
        }.onFailure { logBoth("FrsPageFilter: hook ThreadList 失败 -- $it") }
    }

    private fun filterPageData(pageData: Any) {
        val keywords = getKeywords()
        if (keywords.isEmpty()) return
        filterBusinessInfoFeedList(pageData) { key, value ->
            when (key) {
                "title", "abstract" -> matchesKeyword(keywords, value)
                else -> false
            }
        }
    }
}
