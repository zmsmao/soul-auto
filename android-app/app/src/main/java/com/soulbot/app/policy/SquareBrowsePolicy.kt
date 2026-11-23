package com.soulbot.app.policy
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

object SquareBrowsePolicy {
    const val MAX_TRANSIENT_ATTEMPTS = 2

    /**
     * The square can keep the previous viewport partially visible after a short swipe.
     * Use the profile name as a session-level guard so that overlapping cards do not
     * open the same person's private-chat entry twice in one automation run.
     */
    fun profileKey(name: String): String = comparableText(name).take(120)

    fun viewportKey(text: String, cardTop: Int, bucketSizePx: Int): String {
        val safeBucket = bucketSizePx.coerceAtLeast(1)
        return "${comparableText(text).take(120)}@${cardTop / safeBucket}"
    }

    fun shouldRetryTransientFailure(attemptCount: Int): Boolean =
        attemptCount < MAX_TRANSIENT_ATTEMPTS
}
