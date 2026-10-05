package com.example.fastautoclicker

enum class ClickMode(val displayName: String, val durationMs: Long) {
    MAX("MAX", 1L),
    MS_1("1 ms", 1L),
    MS_2("2 ms", 2L),
    MS_5("5 ms", 5L),
    MS_10("10 ms", 10L),
    MS_20("20 ms", 20L),
    MS_50("50 ms", 50L);

    companion object {
        fun fromOrdinalSafe(value: Int): ClickMode = entries.getOrElse(value) { MAX }
    }
}
