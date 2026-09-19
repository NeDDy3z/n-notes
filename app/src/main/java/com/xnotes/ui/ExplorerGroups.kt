package com.xnotes.ui

import com.xnotes.core.model.Rgba
import com.xnotes.settings.ExplorerSortKey
import com.xnotes.settings.GroupBy
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** What an explorer entry is, for badges, the kind filter and grouping by kind. */
internal enum class EntryKind(val plural: String) {
    FOLDER("Folders"),
    NOTE("Notes"),
    PDF("PDF notes"),
    CANVAS("Canvases"),
}

/** One heading's worth of entries; [color] is set when grouped by colour. An empty [label] means no heading. */
internal class EntryGroup(val key: String, val label: String, val items: List<BrowseEntry>, val color: Rgba? = null)

/** A date heading and the first day it covers, which orders the headings. */
internal class DateBucket(val label: String, val start: LocalDate)

private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

internal fun monthName(m: Month): String = MONTHS[m.value - 1]
private fun shortMonth(m: Month): String = MONTHS[m.value - 1].take(3)
private fun shortWeekday(d: DayOfWeek): String = WEEKDAYS[d.value - 1]

private fun dayOf(time: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()

/** The heading [time] files under, seen from [now]: Today, Yesterday, this week, last week, earlier this month, then by month. */
internal fun dateBucket(time: Long, now: Long, zone: ZoneId, weekStart: DayOfWeek = DayOfWeek.MONDAY): DateBucket {
    val today = dayOf(now, zone)
    val day = dayOf(time, zone).let { if (it.isAfter(today)) today else it }
    val thisWeek = today.with(TemporalAdjusters.previousOrSame(weekStart))
    val lastWeek = thisWeek.minusWeeks(1)
    return when {
        day == today -> DateBucket("Today", today)
        day == today.minusDays(1) -> DateBucket("Yesterday", day)
        !day.isBefore(thisWeek) -> DateBucket("Earlier this week", thisWeek)
        !day.isBefore(lastWeek) -> DateBucket("Last week", lastWeek)
        day.year == today.year && day.month == today.month -> DateBucket("Earlier in ${monthName(today.month)}", day.withDayOfMonth(1))
        day.year == today.year -> DateBucket(monthName(day.month), day.withDayOfMonth(1))
        else -> DateBucket("${monthName(day.month)} ${day.year}", day.withDayOfMonth(1))
    }
}

/**
 * Splits [items] (already sorted) under headings. Items keep their order within a heading. Date headings run
 * newest first unless the list is sorted by that date oldest first; they follow the created date when sorting
 * by it and the modified date otherwise.
 */
internal fun groupEntries(
    items: List<BrowseEntry>,
    by: GroupBy,
    sortKey: ExplorerSortKey,
    descending: Boolean,
    kindOf: (BrowseEntry) -> EntryKind,
    nameOf: (Rgba) -> String?,
    now: Long,
    zone: ZoneId,
    weekStart: DayOfWeek = DayOfWeek.MONDAY,
): List<EntryGroup> {
    if (items.isEmpty()) return emptyList()
    return when (by) {
        GroupBy.NONE -> listOf(EntryGroup("all", "", items))
        GroupBy.DATE -> {
            val byCreated = sortKey == ExplorerSortKey.CREATED
            val buckets = LinkedHashMap<String, Pair<DateBucket, MutableList<BrowseEntry>>>()
            for (e in items) {
                val b = dateBucket(if (byCreated) e.created else e.modified, now, zone, weekStart)
                buckets.getOrPut(b.label) { b to ArrayList() }.second.add(e)
            }
            val oldestFirst = !descending && (sortKey == ExplorerSortKey.MODIFIED || byCreated)
            val ordered = buckets.values.sortedBy { it.first.start }
            (if (oldestFirst) ordered else ordered.reversed()).map { (b, list) -> EntryGroup(b.label, b.label, list) }
        }
        GroupBy.KIND -> {
            val byKind = items.groupBy(kindOf)
            EntryKind.entries.mapNotNull { k -> byKind[k]?.let { EntryGroup(k.name, k.plural, it) } }
        }
        GroupBy.COLOUR -> {
            val byColor = items.groupBy { it.color }
            val named = byColor.keys.filterNotNull().mapNotNull { c -> nameOf(c)?.let { c to it } }.sortedBy { it.second.lowercase() }
            val unnamed = byColor.keys.filterNotNull().filter { nameOf(it) == null }
                .map { it to hueName(it) }.sortedWith(compareBy({ it.second }, { Rgba.toHex(it.first) }))
            (named + unnamed).map { (c, label) -> EntryGroup(Rgba.toHex(c), label, byColor.getValue(c), c) } +
                listOfNotNull(byColor[null]?.let { EntryGroup("none", "No colour", it) })
        }
    }
}

/** A plain name for a colour nobody has named, from its hue. */
internal fun hueName(c: Rgba): String {
    val r = c.r / 255.0
    val g = c.g / 255.0
    val b = c.b / 255.0
    val max = maxOf(r, g, b)
    val d = max - minOf(r, g, b)
    if (max < 0.15) return "Black"
    if (d / max < 0.15) return if (max > 0.85) "White" else "Grey"
    val h = when (max) {
        r -> 60 * ((g - b) / d)
        g -> 60 * ((b - r) / d + 2)
        else -> 60 * ((r - g) / d + 4)
    }.let { if (it < 0) it + 360 else it }
    return when {
        h < 15 || h >= 345 -> "Red"
        h < 40 -> "Orange"
        h < 65 -> "Yellow"
        h < 160 -> "Green"
        h < 195 -> "Teal"
        h < 255 -> "Blue"
        h < 290 -> "Purple"
        else -> "Pink"
    }
}

private fun clockText(t: LocalTime, clock24: Boolean): String =
    if (clock24) "%02d:%02d".format(Locale.ROOT, t.hour, t.minute)
    else "%d:%02d %s".format(Locale.ROOT, (t.hour + 11) % 12 + 1, t.minute, if (t.hour < 12) "AM" else "PM")

/**
 * [time] as the explorer writes it under a tile. [style] is "relative" (2 days ago), "day" (Wed 17:30, the
 * default) or "date" (16 Sep 2026); [withTime] adds the time of day where the style has room for it.
 */
internal fun formatWhen(time: Long, now: Long, style: String, withTime: Boolean, clock24: Boolean, zone: ZoneId): String {
    if (time <= 0) return ""
    val at = Instant.ofEpochMilli(time).atZone(zone)
    val today = dayOf(now, zone)
    val day = at.toLocalDate()
    val clock = clockText(at.toLocalTime(), clock24)
    val dayMonth = "${day.dayOfMonth} ${shortMonth(day.month)}" + if (day.year != today.year) " ${day.year}" else ""
    return when (style) {
        "relative" -> {
            val mins = (now - time) / 60_000
            when {
                mins < 1 -> "Just now"
                day == today && mins < 60 -> "$mins min ago"
                day == today -> "${mins / 60} h ago"
                day == today.minusDays(1) -> "Yesterday"
                day.isAfter(today.minusDays(7)) -> "${ChronoUnit.DAYS.between(day, today)} days ago"
                else -> dayMonth
            }
        }
        "date" -> "${day.dayOfMonth} ${shortMonth(day.month)} ${day.year}" + if (withTime) ", $clock" else ""
        else -> {
            val d = when {
                !day.isBefore(today) -> "Today"
                day == today.minusDays(1) -> "Yesterday"
                day.isAfter(today.minusDays(7)) -> shortWeekday(day.dayOfWeek)
                else -> return dayMonth
            }
            if (withTime) "$d $clock" else d
        }
    }
}

/** [time] in full, for details panes: 16 Sep 2026, 17:30. */
internal fun formatFull(time: Long, clock24: Boolean, zone: ZoneId): String {
    if (time <= 0) return ""
    val at = Instant.ofEpochMilli(time).atZone(zone)
    return "${at.dayOfMonth} ${shortMonth(at.month)} ${at.year}, ${clockText(at.toLocalTime(), clock24)}"
}

/** When a document was opened, for Recent: "Opened 25 min ago", "Opened yesterday", "Opened 16 Aug". */
internal fun openedLabel(time: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val rel = formatWhen(time, now, "relative", false, true, zone)
    return "Opened " + if (rel.firstOrNull()?.isLetter() == true) rel.replaceFirstChar { it.lowercase() } else rel
}

/** A file size the way the explorer shows it: 940 KB, 3.4 MB. */
internal fun formatSize(bytes: Long): String = when {
    bytes < 1000 -> "$bytes B"
    bytes < 1000L * 1024 -> "${(bytes + 1023) / 1024} KB"
    bytes < 1000L * 1024 * 1024 -> "%.1f MB".format(Locale.ROOT, bytes / 1048576.0)
    else -> "%.1f GB".format(Locale.ROOT, bytes / 1073741824.0)
}

/** "1 folder · 18 files", leaving out a count that is zero. */
internal fun countsLabel(folders: Int, files: Int): String = listOfNotNull(
    folders.takeIf { it > 0 }?.let { if (it == 1) "1 folder" else "$it folders" },
    files.takeIf { it > 0 }?.let { if (it == 1) "1 file" else "$it files" },
).joinToString(" · ")

/** "1 item" or "24 items". */
internal fun itemsLabel(n: Int): String = if (n == 1) "1 item" else "$n items"
