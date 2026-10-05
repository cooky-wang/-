package com.example.fastautoclicker

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.lang.ref.WeakReference

class FastClickAccessibilityService : AccessibilityService() {
    private lateinit var clickEngine: ClickEngine
    private lateinit var overlay: OverlayController
    private var receiverRegistered = false
    private var connected = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        releaseResources()

        // Keep the in-flight guard when Android reconnects this same service instance.
        if (!::clickEngine.isInitialized) clickEngine = ClickEngine(
            service = this,
            onRunningStateChanged = { running ->
                if (::overlay.isInitialized) overlay.setRunning(running)
            },
            onBenchmarkFinished = { results ->
                val best = results.maxByOrNull { it.averageCps }
                if (best != null) {
                    Toast.makeText(
                        this,
                        "Benchmark complete: best ${best.durationMs} ms, ${"%.1f".format(best.averageCps)} CPS",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )

        overlay = OverlayController(
            context = this,
            onStart = { startClicking() },
            onStop = { stopClicking() },
            onBenchmark = { startBenchmark() },
            onModeChanged = { setMode(it) },
            onTargetChanged = { x, y -> setTarget(x, y) },
            snapshotProvider = { snapshot() }
        )

        registerSafetyReceiver()
        connected = true
        showOverlay()
        serviceRef = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        stopClicking()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        stopClicking()
        if (::overlay.isInitialized) overlay.updateForConfigurationChange()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        releaseResources()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        releaseResources()
        super.onDestroy()
    }

    private fun releaseResources() {
        connected = false
        if (::overlay.isInitialized) overlay.hide()
        stopClicking()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(safetyReceiver) }
            receiverRegistered = false
        }
        if (serviceRef?.get() === this) {
            serviceRef?.clear()
            serviceRef = null
        }
    }

    fun startClicking() {
        if (!connected || !::clickEngine.isInitialized) return
        if (!getSystemService(PowerManager::class.java).isInteractive) return
        if (!clickEngine.start()) {
            Toast.makeText(this, "Previous gesture is finishing; tap START again", Toast.LENGTH_SHORT).show()
        }
    }

    fun stopClicking() {
        if (!::clickEngine.isInitialized) return
        clickEngine.stop()
    }

    fun startBenchmark() {
        if (!connected || !::clickEngine.isInitialized) return
        if (!getSystemService(PowerManager::class.java).isInteractive) return
        if (!clickEngine.startBenchmark()) {
            Toast.makeText(this, "Stop clicking and wait for the last gesture before Benchmark", Toast.LENGTH_SHORT).show()
        }
    }

    fun setTarget(x: Int, y: Int) {
        if (::clickEngine.isInitialized) clickEngine.setTarget(x, y)
    }

    internal fun setMode(mode: ClickMode) {
        if (::clickEngine.isInitialized) clickEngine.setMode(mode)
        if (::overlay.isInitialized) overlay.setMode(mode)
    }

    fun showOverlay() {
        if (!connected || !::overlay.isInitialized) return
        try {
            overlay.show()
        } catch (e: WindowManager.BadTokenException) {
            overlay.hide()
            stopClicking()
            Toast.makeText(this, "Reconnect the accessibility service to show controls", Toast.LENGTH_LONG).show()
        }
    }

    internal fun snapshot(): StatsSnapshot {
        return if (::clickEngine.isInitialized) {
            clickEngine.snapshot()
        } else {
            StatsSnapshot(
                running = false,
                benchmarkRunning = false,
                mode = ClickMode.MAX,
                targetX = 0,
                targetY = 0,
                totalCompleted = 0,
                recentCps = 0.0,
                averageCps = 0.0,
                peakCps = 0.0,
                cancelled = 0,
                dispatchFalse = 0,
                runtimeMs = 0,
                benchmarkSummary = ""
            )
        }
    }

    private fun registerSafetyReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(safetyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(safetyReceiver, filter)
        }
        receiverRegistered = true
    }

    private val safetyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_SHUTDOWN -> stopClicking()
            }
        }
    }

    companion object {
        private var serviceRef: WeakReference<FastClickAccessibilityService>? = null

        fun instance(): FastClickAccessibilityService? = serviceRef?.get()
    }
}
