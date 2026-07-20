package net.devemperor.dictate.companion.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The single time-formatting rule for the whole companion UI. Every screen that renders an
 * epoch-milli timestamp (history rows, peer "last reached", offer "last fetched") formats it through
 * here so the app never shows two clashing time styles side by side. Backed by java.time
 * ([DateTimeFormatter]) — the legacy [java.text.DateFormat] + [java.util.Date] pair is deliberately
 * not used.
 */
internal fun Long.asTime(): String =
    COMPANION_TIME_FORMAT.format(Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()))

private val COMPANION_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM. HH:mm")
