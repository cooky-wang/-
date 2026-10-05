package com.example.fastautoclicker

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

internal class OverlayController(
    private val context: Context,
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
    private val onBenchmark: () -> Unit,
    private val onModeChanged: (ClickMode) -> Unit,
    private val onTargetChanged: (Int, Int) -> Unit,
    private val snapshotProvider: () -> StatsSnapshot
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var targetView: View? = null
    private var targetParams: WindowManager.LayoutParams? = null
    private var statsText: TextView? = null
    private var modeButton: Button? = null

    private var targetX: Int = 300
    private var targetY: Int = 500
    private var currentMode: ClickMode = ClickMode.MAX
    private var running = false

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStats()
            handler.postDelayed(this, 250L)
        }
    }

    fun show() {
        if (panelView != null) return
        currentMode = ClickMode.fromOrdinalSafe(prefs.getInt(KEY_MODE, ClickMode.MAX.ordinal))
        val bounds = currentBounds()
        targetX = prefs.getInt(KEY_X, bounds.width() / 2).coerceIn(0, bounds.width().coerceAtLeast(1) - 1)
        targetY = prefs.getInt(KEY_Y, bounds.height() / 2).coerceIn(0, bounds.height().coerceAtLeast(1) - 1)
        onTargetChanged(targetX, targetY)
        onModeChanged(currentMode)

        addPanel()
        addTarget()
        handler.post(refreshRunnable)
    }

    fun hide() {
        running = false
        handler.removeCallbacks(refreshRunnable)
        panelView?.let { safeRemove(it) }
        targetView?.let { safeRemove(it) }
        panelView = null
        panelParams = null
        targetView = null
        targetParams = null
        statsText = null
        modeButton = null
    }

    fun setRunning(isRunning: Boolean) {
        running = isRunning
        if (isRunning) {
            // Hide the target overlay entirely so it can never intercept the injected tap.
            targetView?.let { safeRemove(it) }
            targetView = null
            targetParams = null
        } else if (panelView != null && targetView == null) {
            addTarget()
        }
        refreshStats()
    }

    fun setMode(mode: ClickMode) {
        currentMode = mode
        prefs.edit().putInt(KEY_MODE, mode.ordinal).apply()
        modeButton?.text = "Mode: ${mode.displayName}"
    }

    fun updateForConfigurationChange() {
        val bounds = currentBounds()
        targetX = targetX.coerceIn(0, bounds.width().coerceAtLeast(1) - 1)
        targetY = targetY.coerceIn(0, bounds.height().coerceAtLeast(1) - 1)
        persistTarget()
        onTargetChanged(targetX, targetY)

        panelParams?.let { p ->
            p.x = p.x.coerceIn(0, (bounds.width() - p.width).coerceAtLeast(0))
            p.y = p.y.coerceIn(0, (bounds.height() - (panelView?.height ?: 0)).coerceAtLeast(0))
            panelView?.let { safeUpdate(it, p) }
        }

        targetParams?.let { p ->
            val size = dp(52)
            p.x = targetX - size / 2
            p.y = targetY - size / 2
            targetView?.let { safeUpdate(it, p) }
        }
    }

    private fun addPanel() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(0xDD191B20.toInt(), dp(14).toFloat())
        }

        val dragHandle = TextView(context).apply {
            text = "FAST CLICKER  •  drag"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(6), dp(4), dp(6), dp(6))
        }
        root.addView(dragHandle)

        statsText = TextView(context).apply {
            setTextColor(0xFFE5E7EB.toInt())
            textSize = 12f
            setPadding(dp(6), dp(2), dp(6), dp(6))
        }.also { root.addView(it) }

        val start = Button(context).apply {
            text = "START"
            setOnClickListener { onStart() }
        }
        root.addView(start)

        val stop = Button(context).apply {
            text = "STOP"
            setOnClickListener { onStop() }
            setOnLongClickListener {
                onStop()
                true
            }
        }
        root.addView(stop)

        modeButton = Button(context).apply {
            text = "Mode: ${currentMode.displayName}"
            setOnClickListener {
                val modes = ClickMode.entries
                val next = modes[(currentMode.ordinal + 1) % modes.size]
                currentMode = next
                text = "Mode: ${next.displayName}"
                prefs.edit().putInt(KEY_MODE, next.ordinal).apply()
                onModeChanged(next)
            }
        }.also { root.addView(it) }

        val benchmark = Button(context).apply {
            text = "BENCHMARK"
            setOnClickListener { onBenchmark() }
        }
        root.addView(benchmark)

        val params = baseParams(
            width = dp(178),
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            touchable = true
        ).apply {
            x = prefs.getInt(KEY_PANEL_X, dp(12))
                .coerceIn(0, (currentBounds().width() - dp(178)).coerceAtLeast(0))
            y = prefs.getInt(KEY_PANEL_Y, dp(100))
                .coerceIn(0, (currentBounds().height() - dp(80)).coerceAtLeast(0))
        }

        installDrag(dragHandle, params, root, isPanel = true)

        panelView = root
        panelParams = params
        wm.addView(root, params)
    }

    private fun addTarget() {
        if (running || targetView != null) return

        val size = dp(52)
        val target = TextView(context).apply {
            text = "◎"
            gravity = Gravity.CENTER
            textSize = 34f
            setTextColor(Color.WHITE)
            background = roundedBackground(0xAAE53935.toInt(), size / 2f)
        }

        val params = baseParams(size, size, touchable = true).apply {
            x = targetX - size / 2
            y = targetY - size / 2
        }

        installTargetDrag(target, params, size)

        targetView = target
        targetParams = params
        wm.addView(target, params)
    }

    private fun installTargetDrag(view: View, params: WindowManager.LayoutParams, size: Int) {
        view.setOnTouchListener(object : View.OnTouchListener {
            var downRawX = 0f
            var downRawY = 0f
            var startX = 0
            var startY = 0

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                if (running) return true
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = params.x
                        startY = params.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val bounds = currentBounds()
                        params.x = (startX + event.rawX - downRawX).roundToInt()
                            .coerceIn(-size / 2, bounds.width().coerceAtLeast(1) - 1 - size / 2)
                        params.y = (startY + event.rawY - downRawY).roundToInt()
                            .coerceIn(-size / 2, bounds.height().coerceAtLeast(1) - 1 - size / 2)
                        safeUpdate(v, params)
                        targetX = params.x + size / 2
                        targetY = params.y + size / 2
                        onTargetChanged(targetX, targetY)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        targetX = params.x + size / 2
                        targetY = params.y + size / 2
                        persistTarget()
                        onTargetChanged(targetX, targetY)
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun installDrag(
        handle: View,
        params: WindowManager.LayoutParams,
        movedView: View,
        isPanel: Boolean
    ) {
        handle.setOnTouchListener(object : View.OnTouchListener {
            var downRawX = 0f
            var downRawY = 0f
            var startX = 0
            var startY = 0

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = params.x
                        startY = params.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val bounds = currentBounds()
                        params.x = (startX + event.rawX - downRawX).roundToInt()
                            .coerceIn(0, (bounds.width() - movedView.width).coerceAtLeast(0))
                        params.y = (startY + event.rawY - downRawY).roundToInt()
                            .coerceIn(0, (bounds.height() - movedView.height).coerceAtLeast(0))
                        safeUpdate(movedView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isPanel) {
                            prefs.edit()
                                .putInt(KEY_PANEL_X, params.x)
                                .putInt(KEY_PANEL_Y, params.y)
                                .apply()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun refreshStats() {
        val s = snapshotProvider()
        statsText?.text = buildString {
            append("${if (s.benchmarkRunning) "BENCH" else if (s.running) "RUN" else "STOP"}")
            append("  ${s.mode.displayName}\n")
            append("CPS ${"%.0f".format(s.recentCps)}  avg ${"%.1f".format(s.averageCps)}\n")
            append("Total ${s.totalCompleted}\n")
            append("Peak ${"%.0f".format(s.peakCps)} CPS\n")
            append("Target ${s.targetX}, ${s.targetY}")
            if (s.benchmarkSummary.isNotBlank() && !s.benchmarkRunning) {
                append("\n\n${s.benchmarkSummary}")
            }
        }
    }

    private fun baseParams(width: Int, height: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Absolute display coordinates must not be mirrored in RTL or shifted by system bars.
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
    }

    private fun currentBounds(): Rect {
        return if (Build.VERSION.SDK_INT >= 30) {
            Rect(wm.currentWindowMetrics.bounds)
        } else {
            @Suppress("DEPRECATION")
            val display = wm.defaultDisplay
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            Rect(0, 0, size.x, size.y)
        }
    }

    private fun persistTarget() {
        prefs.edit().putInt(KEY_X, targetX).putInt(KEY_Y, targetY).apply()
    }

    private fun safeUpdate(view: View, params: WindowManager.LayoutParams) {
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun safeRemove(view: View) {
        runCatching { wm.removeViewImmediate(view) }
    }

    private fun roundedBackground(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    companion object {
        private const val PREFS = "fast_clicker"
        private const val KEY_X = "target_x"
        private const val KEY_Y = "target_y"
        private const val KEY_PANEL_X = "panel_x"
        private const val KEY_PANEL_Y = "panel_y"
        private const val KEY_MODE = "mode"
    }
}
