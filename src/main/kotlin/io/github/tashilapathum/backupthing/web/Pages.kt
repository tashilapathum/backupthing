// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.web

import io.github.tashilapathum.backupthing.pipeline.Stage
import io.github.tashilapathum.backupthing.service.BackupService
import io.github.tashilapathum.backupthing.service.Coverage
import io.github.tashilapathum.backupthing.service.CoverageRow
import io.github.tashilapathum.backupthing.service.Part
import io.github.tashilapathum.backupthing.web.Icons.icon
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.b
import kotlinx.html.button
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.ul
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Server-rendered overview. Live parts are fragments that htmx re-fetches whenever the server sends an
 * SSE update; each fragment renders the whole block, so the page never drifts from the server state.
 */
object Pages {
    fun dashboard(
        html: HTML,
        service: BackupService,
        csrf: String,
    ) = Layout.page(html, "Backupthing", csrf, active = "/", pill = { live("pill") { pill(Snapshot(service)) } }) {
        live("warnings", "banners") { warnings(Snapshot(service)) }
        div(classes = "overview") {
            div(classes = "primary-col") {
                live("status") { hero(Snapshot(service)) }
                live("current") { current(Snapshot(service)) }
                live("failed") { failed(service) }
                section {
                    attributes["aria-labelledby"] = "runs-title"
                    div(classes = "section-head") {
                        h2 {
                            attributes["id"] = "runs-title"
                            +"Recent runs"
                        }
                    }
                    live("runs") { runs(service) }
                }
            }
            aside(classes = "rail") {
                attributes["aria-label"] = "Totals"
                live("totals") { totals(Snapshot(service)) }
            }
        }
    }

    /** A block refreshed from `/fragments/{name}` on every server update. */
    fun FlowContent.live(
        name: String,
        classes: String? = null,
        content: FlowContent.() -> Unit,
    ) = div(classes = listOfNotNull("live", classes).joinToString(" ")) {
        attributes["id"] = name
        attributes["hx-get"] = "/fragments/$name"
        attributes["hx-trigger"] = "bt-update from:body"
        content()
    }

    fun fragment(
        name: String,
        service: BackupService,
    ): String? {
        val render: (FlowContent.() -> Unit) =
            when (name) {
                "pill" -> {
                    { pill(Snapshot(service)) }
                }

                "status" -> {
                    { hero(Snapshot(service)) }
                }

                "warnings" -> {
                    { warnings(Snapshot(service)) }
                }

                "totals" -> {
                    { totals(Snapshot(service)) }
                }

                "current" -> {
                    { current(Snapshot(service)) }
                }

                "failed" -> {
                    { failed(service) }
                }

                "runs" -> {
                    { runs(service) }
                }

                "login" -> {
                    { SettingsPages.loginPanel(this, service) }
                }

                "deps" -> {
                    { SettingsPages.deps(this, service) }
                }

                else -> {
                    return null
                }
            }
        return createHTML().div(classes = "fragment") { render() }
    }

    /** Top-bar badge, shown on every page. */
    fun FlowContent.pill(s: Snapshot) {
        val (cls, iconName, text) =
            when (s.phase) {
                Phase.RUNNING -> {
                    Triple("running", "arrows-clockwise", "Backing up ${s.progressPercent ?: 0}%")
                }

                Phase.LOGIN -> {
                    Triple("login", "sign-in", "Sign-in needed")
                }

                Phase.OFFLINE -> {
                    Triple("offline", "warning-circle", "Offline")
                }

                Phase.LIMITED -> {
                    Triple("limited", "gauge", "Cap reached")
                }

                Phase.WARNING -> {
                    Triple("warning", "warning-circle", "Needs attention")
                }

                Phase.IDLE -> {
                    if (s.totals.doneFiles >
                        0
                    ) {
                        Triple("ok", "check-circle", "Up to date")
                    } else {
                        Triple("", "check-circle", "Idle")
                    }
                }
            }
        span(classes = "badge $cls") {
            attributes["role"] = "status"
            icon(iconName)
            +text
        }
    }

    private fun scheduleLine(s: Snapshot): String {
        val next =
            s.nextRun?.let { "Next run ${Format.time(it)}, ${Format.relative(it)}." } ?: "Runs only when you start one."
        val last = s.lastRun?.let { " Last run ${Format.relative(Instant.ofEpochMilli(it.started_at))}." }.orEmpty()
        return next + last
    }

    private fun FlowContent.hero(s: Snapshot) {
        val t = s.totals
        val p = s.pipeline

        data class Hero(
            val tone: String,
            val iconName: String,
            val title: String,
            val sub: String,
        )
        val h =
            when (s.phase) {
                Phase.RUNNING -> {
                    val started =
                        s.lastRun
                            ?.let {
                                "Started ${Format.relative(
                                    Instant.ofEpochMilli(it.started_at),
                                )}. "
                            }.orEmpty()
                    Hero(
                        "ok running",
                        "arrows-clockwise",
                        "Backing up",
                        started + "You can close this page, the backup keeps going.",
                    )
                }

                Phase.LOGIN -> {
                    Hero(
                        "warn",
                        "sign-in",
                        "Sign in to Proton to resume uploads",
                        "Compressed copies are kept and upload after you sign in.",
                    )
                }

                Phase.OFFLINE -> {
                    Hero(
                        "warn",
                        "warning-circle",
                        "Can't reach Proton right now",
                        p.uploadsPaused ?: "Encoded files will upload on the next run.",
                    )
                }

                Phase.LIMITED -> {
                    val u = s.usage
                    // The run message names the cap that stopped it; usage tells whether it is still in force.
                    val monthly = s.lastRun?.message?.startsWith("Monthly") == true
                    val resets = if (monthly) Format.day(u.monthResetsAt.toInstant()) else "midnight"
                    val n = t.pendingFiles
                    Hero(
                        "ok",
                        "gauge",
                        "${if (monthly) "Monthly" else "Daily"} upload cap reached",
                        "$n ${if (n == 1L) "file is" else "files are"} ready and upload on the first run " +
                            "after the cap resets ${if (monthly) "on $resets" else "at $resets"}.",
                    )
                }

                Phase.WARNING -> {
                    if (t.failedFiles > 0) {
                        val n = t.failedFiles
                        Hero(
                            "bad",
                            "x-circle",
                            "$n ${if (n == 1L) "file" else "files"} couldn't be backed up",
                            "Details are below. Retry once the cause is fixed.",
                        )
                    } else {
                        Hero(
                            "warn",
                            "warning-circle",
                            "Setup needs attention",
                            s.warnings.firstOrNull() ?: scheduleLine(s),
                        )
                    }
                }

                Phase.IDLE -> {
                    when {
                        t.pendingFiles > 0 -> {
                            Hero("ok", "cloud-arrow-up", "${t.pendingFiles} files waiting to back up", scheduleLine(s))
                        }

                        t.doneFiles > 0 -> {
                            Hero("ok", "shield-check", "All ${t.doneFiles} files are backed up", scheduleLine(s))
                        }

                        else -> {
                            Hero(
                                "ok",
                                "cloud-arrow-up",
                                "Nothing backed up yet",
                                "Start a run now, or wait for the schedule. " + scheduleLine(s),
                            )
                        }
                    }
                }
            }
        section(classes = "hero ${h.tone}") {
            span(classes = "state-icon") { icon(h.iconName) }
            h1 { +h.title }
            p(classes = "sub") { +h.sub }
            div(classes = "actions") {
                button(type = ButtonType.button, classes = "primary") {
                    attributes["hx-post"] = "/run"
                    attributes["hx-swap"] = "none"
                    if (p.running) attributes["disabled"] = "disabled"
                    icon("play")
                    +"Run now"
                }
                if (s.phase == Phase.LOGIN) {
                    a(href = "/settings?tab=destination", classes = "button") { +"Sign in" }
                }
            }
        }
    }

    private fun FlowContent.warnings(s: Snapshot) {
        s.warnings.distinct().forEach { w ->
            div(classes = "banner") {
                attributes["role"] = "alert"
                icon("warning-circle")
                span { +w }
            }
        }
    }

    private fun FlowContent.totals(s: Snapshot) {
        val t = s.totals
        coverage(s.coverage)
        div(classes = "saving") {
            val saved = Format.savedPercent(t.doneSourceBytes, t.doneStoredBytes)
            if (saved == null) {
                p(classes = "empty") { +"Space savings show up here after the first backup." }
            } else {
                div(classes = "big") { +"$saved%" }
                p(classes = "caption") { +"smaller than the originals" }
                val storedPct =
                    if (t.doneSourceBytes >
                        0
                    ) {
                        (t.doneStoredBytes * 100 / t.doneSourceBytes).coerceIn(1, 100)
                    } else {
                        0
                    }
                div(classes = "compare-bars") {
                    compareRow("Originals", Format.bytes(t.doneSourceBytes), 100, stored = false)
                    compareRow("In Proton", Format.bytes(t.doneStoredBytes), storedPct.toInt(), stored = true)
                }
            }
        }
        dl(classes = "stats") {
            val u = s.usage
            if (u.dailyCap > 0) stat("Today", "${Format.bytes(u.today)} of ${Format.bytes(u.dailyCap)}")
            if (u.monthlyCap > 0) stat("This month", "${Format.bytes(u.thisMonth)} of ${Format.bytes(u.monthlyCap)}")
            stat("Next run", s.nextRun?.let { Format.time(it) } ?: "Manual")
        }
    }

    /** How much of the library is backed up: a stacked bar by original size, a legend, and a bar per folder. */
    private fun FlowContent.coverage(c: Coverage) {
        val t = c.total
        div(classes = "coverage") {
            if (t.total.files == 0L) {
                p(classes = "empty") { +"Backup progress shows up after the first scan." }
                return@div
            }
            div(classes = "big") { +"${t.percentDone}%" }
            p(classes = "caption") { +"of your library is backed up" }
            p(classes = "caption") {
                b { +Format.bytes(t.done.bytes) }
                +" of ${Format.bytes(t.total.bytes)}"
            }
            coverageBar(t, "Library")
            dl(classes = "legend") {
                legendRow("done", "Backed up", t.done)
                if (t.ready.files > 0) legendRow("ready", "Ready to upload", t.ready)
                if (t.todo.files > 0) legendRow("todo", "Not compressed yet", t.todo)
                if (t.failed.files > 0) legendRow("fail", "Failed", t.failed, bad = true)
            }
            if (c.folders.isNotEmpty()) {
                div(classes = "folders") {
                    attributes["role"] = "list"
                    attributes["aria-label"] = "By folder"
                    c.folders.forEach { row ->
                        div(classes = "row") {
                            attributes["role"] = "listitem"
                            div(classes = "lab") {
                                span {
                                    attributes["title"] = row.label
                                    +row.label
                                }
                                b { +"${row.percentDone}% of ${Format.bytes(row.total.bytes)}" }
                            }
                            coverageBar(row, row.label)
                        }
                    }
                }
            }
        }
    }

    /** Segments sized by original bytes; hover shows each one's size (the legend has the same numbers). */
    private fun FlowContent.coverageBar(
        row: CoverageRow,
        name: String,
    ) = div(classes = "cov") {
        attributes["role"] = "img"
        attributes["aria-label"] =
            "$name: ${row.percentDone}% backed up, ${Format.bytes(row.done.bytes)} of ${Format.bytes(row.total.bytes)}"
        listOf(
            Triple("done", "Backed up", row.done),
            Triple("ready", "Ready to upload", row.ready),
            Triple("todo", "Not compressed yet", row.todo),
            Triple("fail", "Failed", row.failed),
        ).filter { it.third.files > 0 }.forEach { (cls, label, part) ->
            span(classes = "seg $cls") {
                attributes["style"] = "flex: ${part.bytes.coerceAtLeast(1)} 1 0"
                attributes["data-tip"] = "$label · ${Format.bytes(part.bytes)}"
            }
        }
    }

    private fun kotlinx.html.DL.legendRow(
        cls: String,
        label: String,
        part: Part,
        bad: Boolean = false,
    ) {
        val list = this
        div {
            list.dt {
                span(classes = "sw $cls") {}
                +label
            }
            list.dd(classes = if (bad) "bad" else null) {
                +Format.bytes(part.bytes)
                span(classes = "n") { +" · ${"%,d".format(Locale.ENGLISH, part.files)}" }
            }
        }
    }

    private fun FlowContent.compareRow(
        label: String,
        value: String,
        percent: Int,
        stored: Boolean,
    ) = div(classes = "row") {
        div(classes = "label") {
            span { +label }
            b { +value }
        }
        div(classes = if (stored) "bar stored" else "bar") { attributes["style"] = "width: $percent%" }
    }

    private fun kotlinx.html.DL.stat(
        label: String,
        value: String,
        bad: Boolean = false,
    ) {
        val list = this
        // <div> groups a dt/dd pair (valid HTML); the builder streams tags in call order.
        div {
            list.dt { +label }
            list.dd(classes = if (bad) "bad" else null) { +value }
        }
    }

    private fun FlowContent.current(s: Snapshot) {
        val p = s.pipeline
        if (!p.running) return
        section(classes = "run") {
            attributes["aria-label"] = "Current run"
            div(classes = "meter") {
                span {
                    attributes["id"] = "run-meter"
                    attributes["style"] = "width: ${s.progressPercent ?: 0}%"
                }
            }
            div(classes = "counts") {
                span {
                    b { +"${p.settled}" }
                    +" of ${s.runTotal} processed"
                }
                span {
                    b { +"${p.done}" }
                    +" uploaded"
                }
                span {
                    b { +"${p.toEncode}" }
                    +" to encode"
                }
                span {
                    b { +"${p.toUpload}" }
                    +" to upload"
                }
                if (p.failed > 0) {
                    span {
                        b { +"${p.failed}" }
                        +" failed"
                    }
                }
            }
            if (p.active.isNotEmpty()) {
                ul(classes = "jobs") {
                    p.active.values.sortedBy { it.id }.forEach { f ->
                        val uploading = f.stage == Stage.UPLOADING
                        li(classes = if (uploading) "uploading" else null) {
                            attributes["title"] = if (uploading) "Uploading" else "Encoding"
                            icon(if (uploading) "arrow-up" else "gear-six")
                            span(classes = "path") { +f.relPath }
                            div(classes = "meter") {
                                f.progress?.let { pr -> span { attributes["style"] = "width: ${(pr * 100).toInt()}%" } }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun FlowContent.failed(service: BackupService) {
        val rows = service.failedFiles()
        if (rows.isEmpty()) return
        section(classes = "failures") {
            div(classes = "section-head") {
                h2 { +"Couldn't back up" }
                button(type = ButtonType.button, classes = "small") {
                    attributes["hx-post"] = "/retry-failed"
                    attributes["hx-swap"] = "none"
                    icon("arrows-clockwise")
                    +"Retry all"
                }
            }
            ul {
                rows.forEach { r ->
                    li {
                        span(classes = "path") { +r.rel_path }
                        button(type = ButtonType.button, classes = "small quiet") {
                            attributes["hx-post"] = "/retry/${r.id}"
                            attributes["hx-swap"] = "none"
                            attributes["aria-label"] = "Retry ${r.rel_path}"
                            +"Retry"
                        }
                        span(classes = "error") { +(r.error ?: "Unknown error") }
                    }
                }
            }
        }
    }

    private fun triggerLabel(trigger: String) =
        when (trigger) {
            "web" -> "Started manually"
            "schedule" -> "Scheduled"
            "cli" -> "Command line"
            "retry" -> "Retry"
            else -> trigger.replaceFirstChar { it.uppercase() }
        }

    private fun statusLabel(status: String) =
        if (status ==
            "CAPPED"
        ) {
            "Cap reached"
        } else {
            status.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        }

    private fun FlowContent.runs(service: BackupService) {
        val rows = service.recentRuns()
        if (rows.isEmpty()) {
            p(classes = "empty") { +"No runs yet. Your first run will show up here." }
            return
        }
        div(classes = "table-wrap") {
            table(classes = "runs") {
                thead {
                    tr {
                        th { +"Started" }
                        th { +"Result" }
                        th(classes = "num") { +"Files" }
                        th(classes = "num") { +"Size" }
                        th(classes = "num") { +"Took" }
                    }
                }
                tbody {
                    rows.forEach { r ->
                        tr {
                            td {
                                +Format.time(Instant.ofEpochMilli(r.started_at))
                                span(classes = "message") { +triggerLabel(r.trigger) }
                            }
                            td {
                                val nothingNew = r.status == "SUCCESS" && r.files_done == 0L && r.files_failed == 0L
                                if (nothingNew) {
                                    span(classes = "badge small") { +"Nothing new" }
                                } else {
                                    span(classes = "badge small ${r.status.lowercase()}") { +statusLabel(r.status) }
                                }
                                r.message?.let { span(classes = "message") { +it } }
                            }
                            td(classes = "num") {
                                +"${r.files_done}"
                                if (r.files_failed > 0) span(classes = "message") { +"${r.files_failed} failed" }
                            }
                            td(classes = "num") {
                                +(
                                    if (r.bytes_in >
                                        0
                                    ) {
                                        "${Format.bytes(r.bytes_in)} → ${Format.bytes(r.bytes_out)}"
                                    } else {
                                        "-"
                                    }
                                )
                            }
                            td(classes = "num") {
                                +(
                                    r.finished_at?.let {
                                        Format.duration(
                                            Duration.ofMillis(it - r.started_at),
                                        )
                                    }
                                        ?: "…"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
