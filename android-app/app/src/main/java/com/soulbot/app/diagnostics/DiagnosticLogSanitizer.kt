package com.soulbot.app.diagnostics
import com.soulbot.app.*
import com.soulbot.app.accessibility.*
import com.soulbot.app.app.*
import com.soulbot.app.automation.*
import com.soulbot.app.conversation.*
import com.soulbot.app.data.*
import com.soulbot.app.diagnostics.*
import com.soulbot.app.model.*
import com.soulbot.app.policy.*
import com.soulbot.app.workflow.greeting.*
import com.soulbot.app.workflow.match.*
import com.soulbot.app.workflow.privatechat.*
import com.soulbot.app.workflow.qiyu.*
import com.soulbot.app.workflow.square.*

/** Pure Kotlin credential redaction used by runtime diagnostics and unit tests. */
object DiagnosticLogSanitizer {
    private const val REDACTED = "[REDACTED]"

    private val basicAuthUrl = Regex(
        pattern = "(?i)(https?://)([^/\\s:@]+):([^@/\\s]+)@",
    )
    private val authorization = Regex(
        pattern = "(?i)([\"']?authorization[\"']?\\s*[:=]\\s*[\"']?)(?:(?:bearer|basic)\\s+)?([^\"'\\s,;}]+)",
    )
    private val bearer = Regex(
        pattern = "(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{4,}",
    )
    private val namedSecret = Regex(
        pattern = "(?i)([\"']?(?:api[\\s_-]*key|access[\\s_-]*token|refresh[\\s_-]*token|token|secret)[\"']?\\s*[:=]\\s*[\"']?)([^\"'\\s,;&}]{4,})",
    )
    private val openAiStyleKey = Regex(
        pattern = "(?i)\\bsk-[A-Za-z0-9_-]{4,}",
    )
    private val queryKey = Regex(
        pattern = "(?i)([?&](?:api[._-]?)?key=)([^&#\\s]{4,})",
    )

    fun redactSecrets(value: String): String {
        var safe = value
        safe = basicAuthUrl.replace(safe) { match -> "${match.groupValues[1]}$REDACTED@" }
        safe = authorization.replace(safe) { match -> "${match.groupValues[1]}$REDACTED" }
        safe = bearer.replace(safe) { "Bearer $REDACTED" }
        safe = namedSecret.replace(safe) { match -> "${match.groupValues[1]}$REDACTED" }
        safe = openAiStyleKey.replace(safe, REDACTED)
        safe = queryKey.replace(safe) { match -> "${match.groupValues[1]}$REDACTED" }
        return safe
    }

    fun singleLine(value: String, maxChars: Int = 1_000): String {
        val normalized = redactSecrets(value)
            .replace('\u0000', ' ')
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace(Regex("[\\p{Cc}&&[^\\t]]"), " ")
            .trim()
        return truncate(normalized, maxChars)
    }

    fun multiLine(value: String, maxChars: Int = 8_192): String {
        val normalized = redactSecrets(value)
            .replace('\u0000', ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        return truncate(normalized, maxChars)
    }

    private fun truncate(value: String, maxChars: Int): String {
        val limit = maxChars.coerceAtLeast(0)
        if (value.length <= limit) return value
        if (limit == 0) return ""
        if (limit == 1) return "…"
        return value.take(limit - 1) + "…"
    }
}
