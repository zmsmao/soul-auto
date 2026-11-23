package com.soulbot.app.model
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

import okhttp3.Call
import java.util.concurrent.CancellationException

/** Cancellation belongs to one request owner; never cancels the automation's other calls. */
class ModelCallCancellation {
    @Volatile private var cancelled = false
    private var call: Call? = null

    @Synchronized fun cancel() {
        cancelled = true
        call?.cancel()
    }

    fun isCancelled(): Boolean = cancelled

    fun throwIfCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted) {
            throw CancellationException("模型请求已取消")
        }
    }

    @Synchronized internal fun attach(value: Call) {
        if (cancelled) value.cancel() else call = value
    }

    @Synchronized internal fun detach(value: Call) {
        if (call === value) call = null
    }
}
