package com.example.fastautoclicker

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowInsets
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var modeSpinner: Spinner

    private val refresh = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
            setBackgroundColor(0xFF101114.toInt())
        }

        // Android 16 targets edge-to-edge. Apply actual system bar insets to our root padding.
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(
                    dp(20) + bars.left,
                    dp(20) + bars.top,
                    dp(20) + bars.right,
                    dp(28) + bars.bottom
                )
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    dp(20) + insets.systemWindowInsetLeft,
                    dp(20) + insets.systemWindowInsetTop,
                    dp(20) + insets.systemWindowInsetRight,
                    dp(28) + insets.systemWindowInsetBottom
                )
            }
            insets
        }

        root.addView(TextView(this).apply {
            text = "Fast Auto Clicker"
            textSize = 28f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(8))
        })

        root.addView(TextView(this).apply {
            text = "Android 16 / API 36 • offline • no Internet permission\nThe target marker is hidden while clicking so it cannot block taps."
            textSize = 14f
            setTextColor(0xFFB7BBC5.toInt())
            setPadding(0, 0, 0, dp(14))
        })

        status = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFE5E7EB.toInt())
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(status)

        root.addView(makeButton("1. Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        root.addView(makeButton("2. Show floating controls") {
            val service = FastClickAccessibilityService.instance()
            if (service == null) {
                Toast.makeText(this, "Enable the accessibility service first", Toast.LENGTH_SHORT).show()
            } else {
                service.showOverlay()
            }
        })

        root.addView(TextView(this).apply {
            text = "Click mode"
            textSize = 14f
            setTextColor(0xFFB7BBC5.toInt())
            setPadding(0, dp(12), 0, dp(6))
        })

        modeSpinner = Spinner(this).apply {
            val labels = ClickMode.entries.map { it.displayName }
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                labels
            )
            setSelection(ClickMode.MAX.ordinal)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    FastClickAccessibilityService.instance()?.setMode(
                        ClickMode.fromOrdinalSafe(position)
                    )
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }
        root.addView(modeSpinner)

        root.addView(makeButton("START") {
            FastClickAccessibilityService.instance()?.startClicking()
                ?: Toast.makeText(this, "Accessibility service is not connected", Toast.LENGTH_SHORT).show()
        })

        root.addView(makeButton("STOP") {
            FastClickAccessibilityService.instance()?.stopClicking()
        }.apply {
            setOnLongClickListener {
                FastClickAccessibilityService.instance()?.stopClicking()
                true
            }
        })

        root.addView(makeButton("Benchmark: 1 / 2 / 5 / 10 / 20 ms") {
            FastClickAccessibilityService.instance()?.startBenchmark()
                ?: Toast.makeText(this, "Accessibility service is not connected", Toast.LENGTH_SHORT).show()
        })

        stats = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFE5E7EB.toInt())
            setPadding(0, dp(18), 0, 0)
        }
        root.addView(stats)

        root.addView(TextView(this).apply {
            text = "Emergency stop: tap or long-press STOP. Clicking also stops when the screen turns off or the accessibility service is destroyed."
            textSize = 13f
            setTextColor(0xFFFFCC80.toInt())
            setPadding(0, dp(18), 0, 0)
        })

        val scroll = ScrollView(this).apply {
            addView(root)
            isFillViewport = true
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun refreshUi() {
        val enabled = isOurAccessibilityServiceEnabled()
        val service = FastClickAccessibilityService.instance()
        status.text = buildString {
            append("Accessibility: ${if (enabled) "Enabled" else "Disabled"}\n")
            append("Service: ${if (service != null) "Connected" else "Not connected"}\n")
            append("Overlay permission: not required (TYPE_ACCESSIBILITY_OVERLAY)")
        }

        if (service == null) {
            stats.text = "Enable the service, then drag the red ◎ target over the point you want to tap."
            return
        }

        val s = service.snapshot()
        if (modeSpinner.selectedItemPosition != s.mode.ordinal) {
            modeSpinner.setSelection(s.mode.ordinal)
        }
        stats.text = buildString {
            append("State: ${if (s.benchmarkRunning) "BENCHMARK" else if (s.running) "RUNNING" else "STOPPED"}\n")
            append("Target: X=${s.targetX}, Y=${s.targetY}\n")
            append("Mode: ${s.mode.displayName}\n")
            append("Recent CPS: ${"%.1f".format(s.recentCps)}\n")
            append("Average CPS: ${"%.1f".format(s.averageCps)}\n")
            append("Peak 1s CPS: ${"%.0f".format(s.peakCps)}\n")
            append("Completed: ${s.totalCompleted}\n")
            append("Cancelled: ${s.cancelled}\n")
            append("dispatchGesture(false): ${s.dispatchFalse}\n")
            append("Runtime: ${s.runtimeMs} ms")
            if (s.benchmarkSummary.isNotBlank()) {
                append("\n\n${s.benchmarkSummary}")
            }
        }
    }

    private fun isOurAccessibilityServiceEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                serviceInfo.packageName == packageName &&
                    serviceInfo.name == FastClickAccessibilityService::class.java.name
            }
    }

    private fun makeButton(label: String, click: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            gravity = Gravity.CENTER
            setOnClickListener { click() }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()
}
