package com.xiaozhi.android.mcp

data class LauncherAppCandidate(
    val label: String,
    val packageName: String
)

object AppNameMatcher {
    fun isDirectLaunchCommand(rawValue: String): Boolean {
        return DIRECT_LAUNCH_PATTERN.containsMatchIn(rawValue.trim())
    }

    fun normalize(rawValue: String): String {
        var value = rawValue
            .lowercase()
            .replace(PUNCTUATION_PATTERN, "")
            .trim()
        value = COMMAND_PREFIX_PATTERN.replace(value, "")
        value = COMMAND_SUFFIX_PATTERN.replace(value, "")
        return value.replace(WHITESPACE_PATTERN, "").trim()
    }

    fun bestMatch(
        candidates: List<LauncherAppCandidate>,
        rawQuery: String
    ): LauncherAppCandidate? {
        val query = normalize(rawQuery)
        if (query.isBlank()) return null
        val alias = APP_ALIASES[query]
        return candidates
            .mapNotNull { candidate ->
                score(candidate, query, alias)
                    ?.takeIf { it > 0 }
                    ?.let { candidate to it }
            }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    private fun score(
        candidate: LauncherAppCandidate,
        query: String,
        alias: String?
    ): Int? {
        val label = normalize(candidate.label)
        val packageName = candidate.packageName.lowercase()
        return when {
            query == label -> EXACT_SCORE
            query == packageName -> EXACT_SCORE
            alias != null && alias == label -> ALIAS_SCORE
            alias != null && alias == packageName -> ALIAS_SCORE
            label.startsWith(query) -> PREFIX_SCORE + label.length
            packageName.startsWith(query) -> PACKAGE_PREFIX_SCORE
            label.contains(query) -> CONTAINS_SCORE + label.length
            packageName.contains(query) -> PACKAGE_CONTAINS_SCORE
            else -> null
        }
    }

    private val PUNCTUATION_PATTERN = Regex("['’\"“”`()。,，、;；:：!！?？\\[\\]{}<>|\\\\/*+-]")
    private val WHITESPACE_PATTERN = Regex("\\s+")
    private val COMMAND_PREFIX_PATTERN =
        Regex(
            "^(?:(请|帮我|麻烦|帮忙)?(打开一下|开一下|打开|开启|启动|进入|运行)" +
                "|((please\\s+)?(open|launch|start|run)))"
        )
    private val COMMAND_SUFFIX_PATTERN = Regex("(的)?(应用|软件|客户端|app)$")
    private val DIRECT_LAUNCH_PATTERN =
        Regex(
            "^\\s*(?:(请|帮我|麻烦|帮忙)?\\s*(打开一下|开一下|打开|开启|启动|进入|运行)" +
                "|(please\\s+)?(open|launch|start|run)\\s+)"
        )

    private val APP_ALIASES = mapOf(
        "微信" to "com.tencent.mm",
        "qq" to "com.tencent.mobileqq",
        "淘宝" to "com.taobao.taobao",
        "支付宝" to "com.eg.android.AlipayGphone",
        "抖音" to "com.ss.android.ugc.aweme",
        "高德地图" to "com.autonavi.minimap",
        "百度地图" to "com.baidu.BaiduMap",
        "网易云音乐" to "com.netease.cloudmusic",
        "哔哩哔哩" to "tv.danmaku.bili",
        "b站" to "tv.danmaku.bili"
    )

    private const val EXACT_SCORE = 1_000
    private const val ALIAS_SCORE = 950
    private const val PREFIX_SCORE = 800
    private const val PACKAGE_PREFIX_SCORE = 700
    private const val CONTAINS_SCORE = 600
    private const val PACKAGE_CONTAINS_SCORE = 500
}
