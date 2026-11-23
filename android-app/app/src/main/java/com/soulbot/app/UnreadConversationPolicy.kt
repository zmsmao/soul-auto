package com.soulbot.app

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

internal data class UnreadConversationCandidate(
    val name: String,
    val timeText: String,
    /** Zero-based top-to-bottom position in Soul's conversation list. */
    val displayOrder: Int,
    val previewText: String = "",
)

/** Prioritizes high-intent/emotional previews, then orders equal-priority unread rows by age. */
internal object UnreadConversationPolicy {
    private const val MINUTES_PER_DAY = 24L * 60L
    private const val UNKNOWN_AGE_MINUTES = MINUTES_PER_DAY

    fun oldestFirst(
        conversations: List<UnreadConversationCandidate>,
        now: LocalDateTime = LocalDateTime.now(),
    ): List<UnreadConversationCandidate> = conversations
        .sortedWith(
            compareByDescending<UnreadConversationCandidate> { priorityScore(it.previewText) }
                .thenBy { parsedAgeMinutes(it.timeText, now) == null }
                .thenByDescending { parsedAgeMinutes(it.timeText, now) ?: Long.MIN_VALUE }
                .thenBy { it.displayOrder },
        )

    /**
     * High-priority signals include explicit contact/meeting/photo interest and
     * emotional follow-ups. This only changes queue order, never the reply content.
     */
    fun priorityScore(text: String): Int {
        val normalized = text.lowercase().replace(Regex("""[\s，。！？!?、,.~～…·]+"""), "")
        if (normalized.isBlank()) return 0
        val directSignals = listOf(
            "微信", "微信号", "联系方式", "加v", "加q", "v信", "q号",
            "见面", "约出来", "见一面", "哪天见", "什么时候见", "约会",
            "照片", "发自拍", "自拍", "看看你", "发张照片",
            "怎么不理我", "你怎么不回", "你不理我", "你还在吗",
            "是不是不想聊", "不想聊了吗", "我有点难过", "很难过",
            "我很委屈", "有点委屈", "我生气了", "很失望", "伤心",
        )
        if (directSignals.any(normalized::contains)) return 100
        if (Regex("""(?:加|留|发|给我).{0,3}(?:v|q)(?:号|一下|好友)?""", RegexOption.IGNORE_CASE)
                .containsMatchIn(normalized)
        ) return 100
        return 0
    }

    fun ageMinutes(text: String, now: LocalDateTime = LocalDateTime.now()): Long {
        return parsedAgeMinutes(text, now) ?: UNKNOWN_AGE_MINUTES
    }

    fun parsedAgeMinutes(text: String, now: LocalDateTime = LocalDateTime.now()): Long? {
        val value = text.trim()
        if (value.isEmpty() || value == "刚刚" || value == "现在") return 0L

        Regex("""(\d+)\s*秒前""").find(value)?.let { return 0L }
        Regex("""(\d+)\s*分钟前""").find(value)?.let {
            return it.groupValues[1].toLongOrNull()
        }
        Regex("""(\d+)\s*小时前""").find(value)?.let {
            return safeMultiply(it.groupValues[1], 60L)
        }
        Regex("""(\d+)\s*天前""").find(value)?.let {
            return safeMultiply(it.groupValues[1], MINUTES_PER_DAY)
        }
        Regex("""(\d+)\s*(?:周|星期)前""").find(value)?.let {
            return safeMultiply(it.groupValues[1], 7L * MINUTES_PER_DAY)
        }

        parseClock(value.removePrefix("今天").trim())?.let { clock ->
            return elapsedMinutes(now.with(clock), now, rollBackFutureByDays = 1)
        }
        if (value.startsWith("昨天")) {
            val clock = parseClock(value.removePrefix("昨天").trim()) ?: LocalTime.MIDNIGHT
            return elapsedMinutes(now.toLocalDate().minusDays(1).atTime(clock), now)
        }
        if (value.startsWith("前天")) {
            val clock = parseClock(value.removePrefix("前天").trim()) ?: LocalTime.MIDNIGHT
            return elapsedMinutes(now.toLocalDate().minusDays(2).atTime(clock), now)
        }

        parseWeekday(value)?.let { (dayOfWeek, clock) ->
            var daysAgo = (now.dayOfWeek.value - dayOfWeek.value + 7) % 7
            if (daysAgo == 0) daysAgo = 7
            return elapsedMinutes(
                now.toLocalDate().minusDays(daysAgo.toLong()).atTime(clock),
                now,
            )
        }

        parseDate(value, now.toLocalDate())?.let { date ->
            return elapsedMinutes(date.atStartOfDay(), now)
        }

        return null
    }

    private fun parseClock(value: String): LocalTime? {
        val match = Regex("""^(?:(凌晨|早上|上午|中午|下午|晚上)\s*)?(\d{1,2}):(\d{2})$""")
            .matchEntire(value) ?: return null
        val period = match.groupValues[1]
        var hour = match.groupValues[2].toIntOrNull() ?: return null
        val minute = match.groupValues[3].toIntOrNull() ?: return null
        hour = when {
            period in setOf("下午", "晚上") && hour in 1..11 -> hour + 12
            period == "中午" && hour in 1..10 -> hour + 12
            period == "凌晨" && hour == 12 -> 0
            else -> hour
        }
        return runCatching { LocalTime.of(hour, minute) }.getOrNull()
    }

    private fun parseWeekday(value: String): Pair<DayOfWeek, LocalTime>? {
        val match = Regex("""^(?:星期|周)([一二三四五六日天])(?:\s*(\d{1,2}:\d{2}))?$""")
            .matchEntire(value) ?: return null
        val day = when (match.groupValues[1]) {
            "一" -> DayOfWeek.MONDAY
            "二" -> DayOfWeek.TUESDAY
            "三" -> DayOfWeek.WEDNESDAY
            "四" -> DayOfWeek.THURSDAY
            "五" -> DayOfWeek.FRIDAY
            "六" -> DayOfWeek.SATURDAY
            else -> DayOfWeek.SUNDAY
        }
        return day to (parseClock(match.groupValues[2]) ?: LocalTime.MIDNIGHT)
    }

    private fun parseDate(value: String, today: LocalDate): LocalDate? {
        Regex("""^(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})日?$""")
            .matchEntire(value)?.let { match ->
                return validDate(
                    match.groupValues[1].toInt(),
                    match.groupValues[2].toInt(),
                    match.groupValues[3].toInt(),
                )
            }
        Regex("""^(\d{1,2})(?:月|-|/|\.)(\d{1,2})日?$""")
            .matchEntire(value)?.let { match ->
                val month = match.groupValues[1].toInt()
                val day = match.groupValues[2].toInt()
                var date = validDate(today.year, month, day) ?: return null
                if (date.isAfter(today)) date = validDate(today.year - 1, month, day) ?: return null
                return date
            }
        return null
    }

    private fun validDate(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()

    private fun safeMultiply(number: String, multiplier: Long): Long? =
        number.toLongOrNull()?.let { value ->
            runCatching { Math.multiplyExact(value, multiplier) }.getOrNull()
        }

    private fun elapsedMinutes(
        from: LocalDateTime,
        now: LocalDateTime,
        rollBackFutureByDays: Long = 0,
    ): Long {
        val adjusted = if (from.isAfter(now) && rollBackFutureByDays > 0) {
            from.minusDays(rollBackFutureByDays)
        } else {
            from
        }
        return ChronoUnit.MINUTES.between(adjusted, now).coerceAtLeast(0L)
    }
}
