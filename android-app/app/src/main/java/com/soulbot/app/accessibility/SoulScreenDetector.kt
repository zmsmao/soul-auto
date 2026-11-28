package com.soulbot.app.accessibility

internal enum class SoulScreen {
    CHAT, SOUL_MATCH_CHAT, SOUL_PLANET, LIST, SQUARE, PROFILE, GIFT, NO_SEND, LIVE_ROOM,
    GREETING_LIST, QIYU_POPUP, OUTSIDE, OTHER,
}

/** Page classification over the current Soul accessibility surface. */
internal class SoulScreenDetector(
    private val ui: SoulAccessibilityUi,
    private val displayedConversationName: () -> String,
 ) {
    fun visibleTitleTexts(): List<String> =
        ui.findByIds(SoulAccessibilityIds.ID_GENERIC_TITLE)
            .filter({ ui.isVisibleOnScreen(it) })
            .map({ ui.textOf(it) })
            .filter(String::isNotBlank)
    fun visibleTextsContaining(query: String): List<String> = runCatching {
        ui.root()?.findAccessibilityNodeInfosByText(query)
            ?.filter({ ui.isVisibleOnScreen(it) })
            ?.map({ ui.textOf(it) })
            ?.filter(String::isNotBlank)
            ?: emptyList()
    }.getOrDefault(emptyList())
    fun soulMatchProfileTexts(): List<String> = buildList {
        // The conversation name and metadata use different ids in different Soul builds.
        displayedConversationName().takeIf(String::isNotBlank)?.let(::add)
        addAll(visibleTitleTexts())
        addAll(visibleTextsContaining("Ta的引力签"))
        addAll(visibleTextsContaining("Ta的星座"))
    }.distinct()
    fun isSoulMatchChatSurface(): Boolean =
        ui.findVisibleById(SoulAccessibilityIds.ID_INPUT_BOX) != null &&
            (ui.findVisibleById(SoulAccessibilityIds.ID_SOUL_MATCH_PERCENT) != null ||
                visibleTitleTexts().any {
                    it.startsWith("Ta的引力签") || it.startsWith("Ta的星座")
                })
    fun isSoulPlanetSurface(): Boolean =
        ui.findVisibleById(SoulAccessibilityIds.ID_INPUT_BOX) == null &&
            (ui.findExactText("灵魂匹配").isNotEmpty() ||
                ui.findExactText("开始匹配").isNotEmpty())

    /**
     * Some live-room layers leave the square pager in the accessibility tree.
     * Require live-player controls instead of relying on the pager's mere presence.
     */
    fun isLikelyLiveRoomSurface(): Boolean {
        val labels = ui.visibleLabels().map { it.replace(" ", "").replace("\\n", "") }
        val strongControls = listOf("说点什么", "发送弹幕", "退出直播", "直播结束", "主播正在直播")
        val supportingControls = listOf("在线人数", "送礼物", "关注主播", "弹幕", "主播")
        val hasStrongControl = labels.any { label -> strongControls.any(label::contains) }
        val supportCount = supportingControls.count { marker -> labels.any { it.contains(marker) } }
        return (hasStrongControl && supportCount >= 1) || supportCount >= 3
    }
    fun currentScreen(): SoulScreen = when {
        !ui.isSoulForeground() -> SoulScreen.OUTSIDE
        ui.findVisibleById(SoulAccessibilityIds.ID_QIYU_CHAT) != null -> SoulScreen.QIYU_POPUP
        isLikelyLiveRoomSurface() -> SoulScreen.LIVE_ROOM
        ui.findVisibleById(SoulAccessibilityIds.ID_NO_SEND_CLOSE) != null -> SoulScreen.NO_SEND
        ui.findVisibleById(SoulAccessibilityIds.ID_GIFT_CLOSE) != null -> SoulScreen.GIFT
        isSoulMatchChatSurface() -> SoulScreen.SOUL_MATCH_CHAT
        ui.findVisibleById(SoulAccessibilityIds.ID_INPUT_BOX) != null -> SoulScreen.CHAT
        ui.findVisibleById(SoulAccessibilityIds.ID_CHAT_SECRET) != null -> SoulScreen.PROFILE
        ui.findVisibleById(SoulAccessibilityIds.ID_GREETING_TIPS) != null -> SoulScreen.GREETING_LIST
        ui.findVisibleById(SoulAccessibilityIds.ID_SQUARE_PAGER) != null -> SoulScreen.SQUARE
        ui.findVisibleById(SoulAccessibilityIds.ID_CONV_NAME) != null -> SoulScreen.LIST
        isSoulPlanetSurface() -> SoulScreen.SOUL_PLANET
        else -> SoulScreen.OTHER
    }
}