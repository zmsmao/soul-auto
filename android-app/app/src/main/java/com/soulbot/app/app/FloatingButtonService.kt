package com.soulbot.app.app
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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private val overlayViewFactory: FloatingOverlayViewFactory by lazy(LazyThreadSafetyMode.NONE) {
        FloatingOverlayViewFactory(object : FloatingOverlayViewHost {
            override val context: android.content.Context get() = this@FloatingButtonService
            override fun dp(value: Int): Int = this@FloatingButtonService.dp(value)
            override fun safeOverlayRegion(): UiRegion = this@FloatingButtonService.safeOverlayRegion()
            override fun overlayParams(x: Int, y: Int): WindowManager.LayoutParams =
                this@FloatingButtonService.overlayParams(x, y)
        })
    }

    private val overlayMotionController: FloatingOverlayMotionController by lazy(LazyThreadSafetyMode.NONE) {
        FloatingOverlayMotionController(object : FloatingOverlayMotionHost {
            override val windowManager: WindowManager get() = this@FloatingButtonService.windowManager
            override val handler: Handler get() = this@FloatingButtonService.handler
            override val floatView: LinearLayout? get() = this@FloatingButtonService.floatView
            override val windowParams: WindowManager.LayoutParams? get() = this@FloatingButtonService.windowParams
            override val expandedView: LinearLayout? get() = this@FloatingButtonService.expandedView
            override val collapsedView: LinearLayout? get() = this@FloatingButtonService.collapsedView
            override fun dp(value: Int): Int = this@FloatingButtonService.dp(value)
            override fun safeOverlayRegion(): UiRegion = this@FloatingButtonService.safeOverlayRegion()
            override fun recordUiEvent(event: String, error: Throwable?) =
                this@FloatingButtonService.recordUiEvent(event, error)
        })
    }

    private var floatView: LinearLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var expandedView: LinearLayout? = null
    private var collapsedView: LinearLayout? = null
    private var statusTitleTv: TextView? = null
    private var nextActionTv: TextView? = null
    private var actionTv: TextView? = null
    private var statusDot: View? = null
    private var collapsedActionIcon: ImageView? = null
    private var lastRenderedRunning: Boolean? = null
    private var cachedOverlayRegion: UiRegion? = null
    // Logging must not block touch dispatch behind file rotation or a SAF export.
    private val uiLogWorker = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(32),
        { task -> Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "floating-diagnostics") },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateStatusPanel()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        recordUiEvent(
            "floating service start command; startId=$startId; flags=$flags",
        )
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        recordUiEvent("floating service created")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(1, buildNotification())
        val shown = runCatching { showFloat() }
        if (shown.isFailure) {
            recordUiEvent(
                "floating window creation failed",
                shown.exceptionOrNull(),
            )
            stopSelf()
            return
        }
        recordUiEvent("floating window shown")
        handler.post(refreshRunnable)
        if (Prefs.getTaskShouldRun(applicationContext)) {
            val service = SoulBotService.instance
            if (service == null) {
                recordUiEvent("floating restore deferred; accessibility service unavailable")
            } else {
                recordUiEvent("floating restore requested; restarting automation")
                service.start()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun safeOverlayRegion(): UiRegion {
        cachedOverlayRegion?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            val safe = UiRegion(
                bounds.left + insets.left,
                bounds.top + insets.top,
                bounds.right - insets.right,
                bounds.bottom - insets.bottom,
            )
            if (safe.isValid) return safe.also { cachedOverlayRegion = it }
        }
        val metrics = resources.displayMetrics
        return UiRegion(0, dp(24), metrics.widthPixels, metrics.heightPixels - dp(24))
            .also { cachedOverlayRegion = it }
    }

    private fun buildNotification(): Notification {
        val channelId = "soulbot_fg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "SoulBot", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("SoulBot 运行中")
            .setContentText("悬浮窗可查看下一步操作与倒计时")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .build()
    }

    private fun overlayParams(x: Int, y: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

    private fun showFloat() {
        if (floatView != null) return
        val views = overlayViewFactory.create()
        val root = views.root
        val lp = views.layoutParams
        windowManager.addView(root, lp)

        floatView = root
        windowParams = lp
        expandedView = views.expanded
        collapsedView = views.collapsed
        statusTitleTv = views.statusTitle
        nextActionTv = views.nextAction
        actionTv = views.action
        statusDot = views.statusDot
        collapsedActionIcon = views.collapsedActionIcon

        // Window overlays dispatch a tap to the deepest child under the finger.
        // Bind the whole information cluster as a drag surface. A plain tap keeps
        // the panel open and restarts its five-second edge timer.
        listOf<View>(root, views.expanded, views.statusArea, views.statusDot, views.textColumn, views.statusTitle, views.nextAction)
            .forEach { target ->
                if (target !== views.statusArea) {
                    target.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                overlayMotionController.attachDragAndClick(target)
            }
        overlayMotionController.attachDragAndClick(views.action) { toggle() }
        listOf<View>(views.collapsed, views.collapsedActionIcon).forEach { target ->
            if (target !== views.collapsed) {
                target.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            overlayMotionController.attachDragAndClick(target) { overlayMotionController.setCollapsed(false) }
        }

        overlayMotionController.onInitialLayoutReady()
        updateStatusPanel()
    }


    private fun recordUiEvent(event: String, error: Throwable? = null) {
        if (uiLogWorker.isShutdown) return
        val app = applicationContext
        uiLogWorker.execute { RuntimeLog.record(app, event, error) }
    }

    private fun toggle() {
        val service = SoulBotService.instance
        if (service == null) {
            recordUiEvent("floating toggle ignored; accessibility service unavailable")
            updateStatusPanel()
            return
        }
        if (SoulBotService.running) {
            service.stop()
        } else {
            service.start()
        }
        updateStatusPanel()
    }

    private fun updateStatusPanel() {
        // Keep the hit area and window size stable until this gesture finishes.
        if (overlayMotionController.dragInProgress) return
        val serviceAvailable = SoulBotService.instance != null
        val running = SoulBotService.running
        val rawStatus = SoulBotService.statusText
        val deadline = SoulBotService.statusDeadline
        val now = System.currentTimeMillis()

        val title = FloatingStatusFormatter.title(running, serviceAvailable, rawStatus)
        val detail = FloatingStatusFormatter.detail(
            running,
            serviceAvailable,
            rawStatus,
            deadline,
            now,
        )

        if (statusTitleTv?.text?.toString() != title) statusTitleTv?.text = title
        if (nextActionTv?.text?.toString() != detail) nextActionTv?.text = detail
        val description = "$title，点击展开悬浮窗"
        if (collapsedView?.contentDescription != description) collapsedView?.contentDescription = description

        if (lastRenderedRunning != running) {
            val activeColor = if (running) Color.parseColor("#22C55E") else Color.parseColor("#8B5CF6")
            statusDot?.background = overlayViewFactory.dotDrawable(activeColor)
            actionTv?.apply {
                text = if (running) "停止" else "启动"
                contentDescription = if (running) "停止自动助手" else "启动自动助手"
                background = overlayViewFactory.roundedRipple(
                    if (running) Color.parseColor("#B4232D") else Color.parseColor("#6D28D9"),
                    24,
                )
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    ActionIconDrawable(running, dp(18)),
                    null,
                    null,
                    null,
                )
            }
            collapsedActionIcon?.setImageDrawable(ActionIconDrawable(running, dp(18)))
            lastRenderedRunning = running
        }
    }

    override fun onDestroy() {
        recordUiEvent("floating service destroying")
        handler.removeCallbacks(refreshRunnable)
        overlayMotionController.onDestroy()
        floatView?.let {
            try {
                windowManager.removeView(it)
            } catch (error: Exception) {
                recordUiEvent("floating window removal failed", error)
            }
        }
        floatView = null
        windowParams = null
        expandedView = null
        collapsedView = null
        statusTitleTv = null
        nextActionTv = null
        actionTv = null
        statusDot = null
        collapsedActionIcon = null
        uiLogWorker.shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cachedOverlayRegion = null
        recordUiEvent(
            "device configuration changed; orientation=${newConfig.orientation}; screen=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}",
        )

        overlayMotionController.onConfigurationChanged()
    }



}
