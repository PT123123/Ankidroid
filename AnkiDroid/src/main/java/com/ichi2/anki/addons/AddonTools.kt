// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Anki Plus · second add-on wave (B23-B28), 2026

package com.ichi2.anki.addons

/**
 * Pure decision logic for the second add-on wave, mirroring anki-qt/src/addons.h
 * so desktop and Android make the same choices. No collection, no Android APIs:
 * every function is unit-testable without Robolectric.
 */
object AddonTools {
    // ---- B23 · TTS batch generation ----

    /** Strip HTML and collapse entities before synthesizing a field. */
    fun ttsPlainText(html: String): String =
        html
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("</(?:div|p|li)>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Stable, filesystem-safe media name; note id prevents collisions. */
    fun ttsMediaName(
        plainText: String,
        noteId: Long,
        maxChars: Int = 40,
    ): String {
        val slug =
            plainText
                .lowercase()
                .replace(Regex("[^\\p{L}\\p{N}]+"), "")
                .take(maxChars.coerceAtLeast(8))
                .ifEmpty { "tts" }
        return "tts_${noteId}_$slug.mp3"
    }

    fun ttsSoundTag(mediaName: String): String = if (mediaName.isEmpty()) "" else "[sound:$mediaName]"

    /** Split text into chunks no longer than [maxChars], on word boundaries. */
    fun ttsChunks(
        plainText: String,
        maxChars: Int,
    ): List<String> {
        val text = plainText.trim()
        if (text.isEmpty() || maxChars <= 0) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            if (text.length - start <= maxChars) {
                result.add(text.substring(start))
                break
            }
            var split = start + maxChars
            val space = text.lastIndexOf(' ', split - 1)
            if (space > start) split = space
            result.add(text.substring(start, split).trim())
            start = split
            while (start < text.length && text[start] == ' ') start++
        }
        return result
    }

    // ---- B24 · FSRS Helper scheduling tools ----

    /** Parse "+3d"/"-2w"/"1m"/"10" into an absolute day offset. */
    fun parseDueOffset(text: String): Int? {
        val match = Regex("^([+-]?)(\\d+)([dwmy])?$").matchEntire(text.trim()) ?: return null
        val value = match.groupValues[2].toInt()
        val factor =
            when (match.groupValues[3]) {
                "w" -> 7
                "m" -> 30
                "y" -> 365
                else -> 1
            }
        return (if (match.groupValues[1] == "-") -value else value) * factor
    }

    /** Shift each due by [offsetDays]; clamp at 0 unless [allowPast]. */
    fun dueOffsets(
        currentDue: List<Int>,
        offsetDays: Int,
        allowPast: Boolean = false,
    ): List<Int> =
        currentDue.map { due ->
            maxOf(if (allowPast) -365 else 0, due + offsetDays)
        }

    /** Distribute [totalCards] over [days] buckets as evenly as possible. */
    fun loadBalancePlan(
        totalCards: Int,
        days: Int,
    ): List<Int> {
        if (totalCards <= 0 || days <= 0) return emptyList()
        val base = totalCards / days
        val remainder = totalCards % days
        return (0 until days).map { base + if (it < remainder) 1 else 0 }
    }

    /** Seven weekday weights (0=Sun), with [weekdaysOff] reduced by [reduction]. */
    fun easyDayPercentages(
        weekdaysOff: Int,
        reduction: Double,
    ): List<Double> {
        val result = MutableList(7) { 1.0 }
        if (weekdaysOff <= 0 || weekdaysOff >= 7) return result
        val r = reduction.coerceIn(0.0, 1.0)
        for (i in 0 until weekdaysOff) result[i] = 1.0 - r
        return result
    }

    // ---- B25 · Corner Review timer ----

    data class CornerPolicy(
        val enabled: Boolean = false,
        val intervalMin: Int = 10,
        val activeFromHour: Int = 8,
        val activeToHour: Int = 22,
        val maxPerDay: Int = 20,
    )

    fun cornerDue(
        policy: CornerPolicy,
        hour: Int,
        shownToday: Int,
        normalReviewActive: Boolean,
    ): Boolean =
        policy.enabled &&
            !normalReviewActive &&
            shownToday < policy.maxPerDay &&
            hour in policy.activeFromHour until policy.activeToHour

    fun cornerCountdownLabel(secondsRemaining: Int): String =
        when {
            secondsRemaining <= 0 -> "即将弹出"
            secondsRemaining < 60 -> "$secondsRemaining 秒后复习"
            else -> "${secondsRemaining / 60} 分钟后复习"
        }

    // ---- B26 · Exam Planner ----

    data class ExamPlan(
        val dailyNew: Int = 0,
        val viable: Boolean = false,
        val studyDays: Int = 0,
    )

    fun examPlan(
        pendingNew: Int,
        daysUntilExam: Int,
        studyDaysPerWeek: Int,
    ): ExamPlan {
        if (pendingNew <= 0 || daysUntilExam <= 0 || studyDaysPerWeek !in 1..7) return ExamPlan()
        val sessions = maxOf(1, Math.ceil(daysUntilExam / 7.0 * studyDaysPerWeek).toInt())
        return ExamPlan(
            dailyNew = (pendingNew + sessions - 1) / sessions,
            viable = sessions <= daysUntilExam,
            studyDays = sessions,
        )
    }

    // ---- B28 · Clickable tags ----

    /** Quote the whole tag value: spaces and regex chars are data, not operators. */
    fun tagSearchQuery(tag: String): String = "tag:\"${tag.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
