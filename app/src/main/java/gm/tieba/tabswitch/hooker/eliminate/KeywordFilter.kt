package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.dao.Preferences.getString

// 关键词过滤: 设置项按 "/" 隔开多个关键词, 命中任一即过滤。
// 关键词为纯文本子串匹配(预转小写, 忽略大小写), 不支持正则元字符。
// UserFilter 亦复用本接口的切分+缓存机制做 ID 名单解析(纯数字 ID 不受小写化影响)
internal interface KeywordFilter {

    fun key(): String

    // 按 key 缓存切分结果, 设置值变更后首个回调重切; 空段(trim 后)忽略
    fun getKeywords(): List<String> {
        val _keywords = getString(key()) ?: return emptyList()
        synchronized(cache) {
            val cached = cache[key()]
            if (cached != null && cached.first == _keywords) return cached.second
            val keywords = _keywords.split('/').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            cache[key()] = _keywords to keywords
            return keywords
        }
    }

    // 文本命中任一关键词; 文本侧整串转小写一次, 关键词已在缓存时预转小写
    fun matchesKeyword(keywords: List<String>, text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        val lower = text.lowercase()
        return keywords.any { lower.contains(it) }
    }

    companion object {
        // 按过滤器 key 分别缓存，避免多个过滤器共用同一槽位互相覆盖
        private val cache = HashMap<String, Pair<String, List<String>>>()
    }
}
