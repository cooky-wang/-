package com.example.fastautoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class StatsSnapshot(
    val running: Boolean,
    val benchmarkRunning: Boolean,
    val mode: ClickMode,
    val targetX: Int,
    val targetY: Int,
    val totalCompleted: Long,
    val recentCps: Double,
    val averageCps: Double,
    val peakCps: Double,
    val cancelled: Long,
    val dispatchFalse: Long,
    val runtimeMs: Long,
    val benchmarkSummary: String
)

internal data class BenchmarkResult(
    val durationMs: Long,
    val completed: Long,
    val averageCps: Double,
    val peakCps: Double,
    val cancelled: Long,
    val dispatchFalse: Long
)

/**
 * Callback-driven click engine.
 *
 * It never queues multiple gestures ahead. A new tap is dispatched only after the previous
 * gesture's onCompleted callback. This keeps STOP responsive and avoids unbounded work queues.
 */
internal class ClickEngine(
    private val service: AccessibilityService,
    private val onRunningStateChanged: (Boolean) -> Unit,
    private val onBenchmarkFinished: (List<BenchmarkResult>) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    private val totalCompleted = AtomicLong(0L)
    private val cancelled = AtomicLong(0L)
    private val dispatchFalse = AtomicLong(0L)
    // STOP invalidates the session but cannot cancel an already injected Android gesture.
    private var gestureInFlight = false

    @Volatile private var mode: ClickMode = ClickMode.MAX
    @Volatile private var targetX: Int = 300
    @Volatile private var targetY: Int = 500
    @Volatile private var startedAtNs: Long = 0L
    @Volatile private var endedAtNs: Long = 0L
    @Volatile private var stoppedRecentCps: Double = 0.0
    @Volatile private var benchmarkRunning: Boolean = false
    @Volatile private var benchmarkSummary: String = ""

    private val recentLock = Any()
    private val recentCompletionsNs = ArrayDeque<Long>()
    @Volatile private var peakCps: Double = 0.0

    private val benchmarkDurations = longArrayOf(1L, 2L, 5L, 10L, 20L)
    private var benchmarkIndex = 0
    private var benchmarkStageStartedNs = 0L
    private var benchmarkStageCompleted = 0L
    private var benchmarkStagePeak = 0.0
    private var benchmarkStageCancelledStart = 0L
    private var benchmarkStageDispatchFalseStart = 0L
    private val benchmarkResults = mutableListOf<BenchmarkResult>()

    fun setTarget(x: Int, y: Int) {
        requireMainThread()
        targetX = x.coerceAtLeast(0)
        targetY = y.coerceAtLeast(0)
    }

    fun setMode(newMode: ClickMode) {
        requireMainThread()
        mode = newMode
    }

    fun getMode(): ClickMode = mode

    fun start(): Boolean {
        requireMainThread()
        if (running.get()) return true
        if (gestureInFlight) return false
        benchmarkRunning = false
        benchmarkSummary = ""
        resetCounters()
        startedAtNs = SystemClock.elapsedRealtimeNanos()
        endedAtNs = 0L
        stoppedRecentCps = 0.0
        running.set(true)
        val token = generation.incrementAndGet()
        onRunningStateChanged(true)
        dispatchNext(token)
        return true
    }

    fun startBenchmark(): Boolean {
        requireMainThread()
        if (running.get() || gestureInFlight) return false

        resetCounters()
        benchmarkResults.clear()
        benchmarkIndex = 0
        benchmarkSummary = "Benchmark running…"
        benchmarkRunning = true
        startedAtNs = SystemClock.elapsedRealtimeNanos()
        endedAtNs = 0L
        stoppedRecentCps = 0.0
        running.set(true)
        startBenchmarkStage()

        val token = generation.incrementAndGet()
        onRunningStateChanged(true)
        dispatchNext(token)
        return true
    }

    fun stop() {
        requireMainThread()
        if (!running.getAndSet(false)) return
        val nowNs = SystemClock.elapsedRealtimeNanos()
        stoppedRecentCps = currentRecentCps(nowNs)
        endedAtNs = nowNs
        if (benchmarkRunning) benchmarkSummary = "Benchmark stopped."
        benchmarkRunning = false
        generation.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(RETRY_TOKEN)
        onRunningStateChanged(false)
    }

    fun snapshot(): StatsSnapshot {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val effectiveNowNs = if (!running.get() && endedAtNs > 0L) endedAtNs else nowNs
        val runtimeNs = if (startedAtNs == 0L) 0L else (effectiveNowNs - startedAtNs).coerceAtLeast(0L)
        val total = totalCompleted.get()
        val recent = if (running.get()) currentRecentCps(nowNs) else stoppedRecentCps
        val avg = if (runtimeNs > 0) total / (runtimeNs / 1_000_000_000.0) else 0.0

        return StatsSnapshot(
            running = running.get(),
            benchmarkRunning = benchmarkRunning,
            mode = mode,
            targetX = targetX,
            targetY = targetY,
            totalCompleted = total,
            recentCps = recent,
            averageCps = avg,
            peakCps = peakCps,
            cancelled = cancelled.get(),
            dispatchFalse = dispatchFalse.get(),
            runtimeMs = runtimeNs / 1_000_000L,
            benchmarkSummary = benchmarkSummary
        )
    }

    private fun resetCounters() {
        totalCompleted.set(0L)
        cancelled.set(0L)
        dispatchFalse.set(0L)
        synchronized(recentLock) {
            recentCompletionsNs.clear()
        }
        peakCps = 0.0
        endedAtNs = 0L
        stoppedRecentCps = 0.0
    }

    private fun dispatchNext(token: Long) {
        requireMainThread()
        if (!running.get() || token != generation.get()) return
        if (gestureInFlight) return

        val x = targetX.toFloat()
        val y = targetY.toFloat()
        val duration = currentDurationMs()

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        gestureInFlight = true
        val accepted = try {
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        gestureInFlight = false
                        if (token != generation.get() || !running.get()) {
                            return
                        }
                        recordCompleted()

                        if (benchmarkRunning && maybeAdvanceBenchmarkStage()) {
                            return
                        }

                        if (running.get()) {
                            dispatchNext(token)
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription) {
                        gestureInFlight = false
                        if (token != generation.get() || !running.get()) {
                            return
                        }
                        cancelled.incrementAndGet()

                        if (benchmarkRunning && maybeAdvanceBenchmarkStage()) {
                            return
                        }

                        if (running.get()) {
                            // Cancellation can occur when the user interacts or Android replaces a gesture.
                            // Post instead of recursing synchronously.
                            scheduleRetry(token)
                        }
                    }
                },
                mainHandler
            )
        } catch (e: RuntimeException) {
            Log.e("FastAutoClicker", "Gesture dispatch failed; stopping", e)
            gestureInFlight = false
            stop()
            return
        }

        if (!accepted) {
            gestureInFlight = false
            dispatchFalse.incrementAndGet()

            if (benchmarkRunning && maybeAdvanceBenchmarkStage()) {
                return
            }

            if (running.get() && token == generation.get()) {
                // Avoid a tight CPU spin if Android temporarily refuses dispatch.
                scheduleRetry(token)
            }
        }
    }

    private fun scheduleRetry(token: Long) {
        mainHandler.postAtTime(
            { dispatchNext(token) }, RETRY_TOKEN, SystemClock.uptimeMillis() + 1L
        )
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Click engine must run on the main thread" }
    }

    private fun currentDurationMs(): Long {
        return if (benchmarkRunning) {
            benchmarkDurations[benchmarkIndex]
        } else {
            mode.durationMs
        }
    }

    private fun recordCompleted() {
        totalCompleted.incrementAndGet()
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val recent = synchronized(recentLock) {
            recentCompletionsNs.addLast(nowNs)
            trimOldRecentEntries(nowNs)
            recentCompletionsNs.size.toDouble()
        }
        if (recent > peakCps) peakCps = recent

        if (benchmarkRunning) {
            benchmarkStageCompleted++
            if (recent > benchmarkStagePeak) benchmarkStagePeak = recent
        }
    }

    private fun currentRecentCps(nowNs: Long): Double {
        return synchronized(recentLock) {
            trimOldRecentEntries(nowNs)
            recentCompletionsNs.size.toDouble()
        }
    }

    private fun trimOldRecentEntries(nowNs: Long) {
        val cutoff = nowNs - 1_000_000_000L
        while (recentCompletionsNs.isNotEmpty() && recentCompletionsNs.first() < cutoff) {
            recentCompletionsNs.removeFirst()
        }
    }

    /**
     * @return true when the benchmark ended and normal dispatch recursion must stop.
     */
    private fun maybeAdvanceBenchmarkStage(): Boolean {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val elapsedNs = nowNs - benchmarkStageStartedNs
        if (elapsedNs < BENCHMARK_STAGE_NS) return false

        val avg = if (elapsedNs > 0) {
            benchmarkStageCompleted / (elapsedNs / 1_000_000_000.0)
        } else 0.0

        benchmarkResults += BenchmarkResult(
            durationMs = benchmarkDurations[benchmarkIndex],
            completed = benchmarkStageCompleted,
            averageCps = avg,
            peakCps = benchmarkStagePeak,
            cancelled = cancelled.get() - benchmarkStageCancelledStart,
            dispatchFalse = dispatchFalse.get() - benchmarkStageDispatchFalseStart
        )

        benchmarkIndex++
        if (benchmarkIndex >= benchmarkDurations.size) {
            finishBenchmark()
            return true
        }

        startBenchmarkStage()
        return false
    }

    private fun startBenchmarkStage() {
        benchmarkStageStartedNs = SystemClock.elapsedRealtimeNanos()
        benchmarkStageCompleted = 0L
        benchmarkStagePeak = 0.0
        synchronized(recentLock) {
            recentCompletionsNs.clear()
        }
        benchmarkStageCancelledStart = cancelled.get()
        benchmarkStageDispatchFalseStart = dispatchFalse.get()
    }

    private fun finishBenchmark() {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        stoppedRecentCps = currentRecentCps(nowNs)
        endedAtNs = nowNs
        running.set(false)
        benchmarkRunning = false
        generation.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(RETRY_TOKEN)

        val best = benchmarkResults.maxByOrNull { it.averageCps }
        benchmarkSummary = buildString {
            benchmarkResults.forEach {
                append("${it.durationMs} ms: ${"%.1f".format(it.averageCps)} CPS")
                append(" (peak ${"%.0f".format(it.peakCps)})\n")
            }
            if (best != null) {
                if (best.durationMs == 1L) {
                    append("Recommended: 1 ms / MAX")
                } else {
                    append("Recommended: ${best.durationMs} ms")
                }
            }
        }.trim()

        onRunningStateChanged(false)
        onBenchmarkFinished(benchmarkResults.toList())
    }

    companion object {
        private const val BENCHMARK_STAGE_NS = 3_000_000_000L
        private val RETRY_TOKEN = Any()
    }
}
