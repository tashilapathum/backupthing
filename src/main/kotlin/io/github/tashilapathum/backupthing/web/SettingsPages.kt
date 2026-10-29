// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.web

import io.github.tashilapathum.backupthing.bandwidth.UploadBudget
import io.github.tashilapathum.backupthing.config.CredentialStore
import io.github.tashilapathum.backupthing.config.DestinationMode
import io.github.tashilapathum.backupthing.config.MotionPhotoMode
import io.github.tashilapathum.backupthing.config.ScheduleMode
import io.github.tashilapathum.backupthing.config.Settings
import io.github.tashilapathum.backupthing.config.ValidationError
import io.github.tashilapathum.backupthing.config.VideoBackend
import io.github.tashilapathum.backupthing.config.WebSettings
import io.github.tashilapathum.backupthing.service.BackupService
import io.github.tashilapathum.backupthing.service.LoginState
import io.github.tashilapathum.backupthing.service.SampleResult
import io.github.tashilapathum.backupthing.upload.AuthStatus
import io.github.tashilapathum.backupthing.web.Icons.icon
import io.github.tashilapathum.backupthing.web.Tabs.tabPanel
import io.github.tashilapathum.backupthing.web.Tabs.tabbed
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.b
import kotlinx.html.button
import kotlinx.html.code
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.figcaption
import kotlinx.html.figure
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.id
import kotlinx.html.img
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.option
import kotlinx.html.output
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.textArea
import kotlinx.html.ul
import java.nio.file.Files
import java.nio.file.Path

/** Context for rendering the settings form. */
class FormContext(
    val service: BackupService,
    val errors: List<ValidationError> = emptyList(),
    /** "Saved" etc. */
    val notice: String? = null,
    /** Interface settings the running server was started with (changes need a restart). */
    val runningWeb: WebSettings,
    val wizard: Boolean = false,
    /** Settings tab to show (anchor of a section); null or unknown shows the first. */
    val tab: String? = null,
) {
    private val byField = errors.groupBy { it.field.substringBefore('[') }

    fun errorsFor(name: String) = byField[name].orEmpty().map { it.message }
}

object SettingsPages {
    private val fieldNames =
        mapOf(
            "sources.roots" to "Folders to back up",
            "sources.extensions" to "File types",
            "sources.settleSeconds" to "Settle time",
            "destination.remoteRoot" to "Remote folder",
            "compression.photoQuality" to "Photo quality",
            "compression.photoSpeed" to "Photo speed",
            "schedule.dailyTime" to "Time",
            "schedule.intervalHours" to "Hours between runs",
            "web.port" to "Port",
            "web.bindAddress" to "Listen on",
            "web.accessToken" to "Access token",
            "web.allowedHosts" to "Extra host names",
            "system.stagingDir" to "Staging folder",
            "bandwidth.uploadLimitKBps" to "Upload speed limit",
            "bandwidth.dailyCapMB" to "Daily cap",
            "bandwidth.monthlyCapMB" to "Monthly cap",
            "bandwidth.monthResetDay" to "Month starts on day",
            "concurrency.photos" to "Photos at once",
            "concurrency.videos" to "Videos at once",
            "concurrency.uploads" to "Uploads at once",
            "concurrency.maxAttempts" to "Attempts per file",
        )

    /** A section of the settings page, shown as one tab. */
    private class Tab(
        val anchor: String,
        val title: String,
        val icon: String,
        val description: String,
        val danger: Boolean = false,
        val body: FlowContent.(Settings, FormContext) -> Unit,
    )

    private val tabs =
        listOf(
            Tab(
                "sources",
                "Sources",
                "folder-simple",
                "Where your phone's photos and videos land, for example a Syncthing folder.",
            ) { s, ctx -> sources(s, ctx) },
            Tab(
                "destination",
                "Destination",
                "cloud-arrow-up",
                "Where the compressed copies go, and your Proton sign-in.",
            ) { s, ctx -> destination(s, ctx) },
            Tab(
                "compression",
                "Compression",
                "sliders-horizontal",
                "Quality settings. Defaults are tuned to look the same as the original.",
            ) { s, ctx -> compression(s, ctx) },
            Tab("schedule", "Schedule", "clock", "When backups run on their own.") { s, ctx -> schedule(s, ctx) },
            Tab(
                "uploads",
                "Uploads",
                "gauge",
                "How many files upload at once, how fast and how much.",
            ) { s, ctx -> uploads(s, ctx) },
            Tab("interface", "Interface", "browser", "How this dashboard is reached.") { s, ctx -> web(s, ctx) },
            Tab("system", "System", "gear-six", "Tools, logging and file locations.") { s, ctx -> system(s, ctx) },
            Tab(
                "maintenance",
                "Maintenance",
                "wrench",
                "Actions that change what Backupthing remembers.",
                danger = true,
            ) { _, _ -> maintenance() },
        )

    private fun tab(anchor: String) = tabs.first { it.anchor == anchor }

    /** The tab that shows a field. */
    private fun tabOf(field: String): String {
        val name = field.substringBefore('[')
        return when {
            name == "concurrency.photos" || name == "concurrency.videos" -> "compression"
            name.startsWith("concurrency.") || name.startsWith("bandwidth.") -> "uploads"
            name.startsWith("web.") -> "interface"
            else -> name.substringBefore('.')
        }
    }

    /** After a failed save: stay on [current] if it shows a problem, otherwise go to the first tab that does. */
    fun tabForErrors(
        current: String?,
        errors: List<ValidationError>,
    ): String? {
        val withErrors = errors.map { tabOf(it.field) }.toSet()
        if (current in withErrors) return current
        return tabs.firstOrNull { it.anchor in withErrors }?.anchor ?: current
    }

    fun settingsPage(
        html: HTML,
        settings: Settings,
        ctx: FormContext,
        csrf: String,
    ) = Layout.page(html, "Settings", csrf, active = "/settings", pill = { pill(ctx.service) }) {
        div(classes = "page-head") {
            h1 { +"Settings" }
            p { +"Changes apply when you save. Interface changes need a restart." }
        }
        settingsForm(this, settings, ctx)
    }

    fun setupPage(
        html: HTML,
        settings: Settings,
        ctx: FormContext,
        csrf: String,
    ) = Layout.page(html, "Set up", csrf, active = null) {
        div(classes = "page-head") {
            h1 { +"Welcome to Backupthing" }
            p {
                +"Backupthing compresses the photos and videos in your folders and backs up only the compressed copies "
                +"to Proton Drive. Your originals are never changed. Four steps to get going."
            }
        }
        settingsForm(this, settings, ctx)
    }

    fun loginPage(
        html: HTML,
        csrf: String,
        error: String?,
    ) = Layout.page(html, "Log in", csrf, active = null) {
        section(classes = "narrow") {
            h1 { +"Enter your access token" }
            p(classes = "muted") {
                +"This dashboard is reachable from your network, so it asks for the token set in Settings."
            }
            form {
                attributes["hx-post"] = "/login"
                attributes["hx-target"] = "#login-error"
                input(type = InputType.password, name = "token") {
                    attributes["autocomplete"] = "current-password"
                    attributes["autofocus"] = "true"
                    attributes["aria-label"] = "Access token"
                }
                button(type = ButtonType.submit, classes = "primary") { +"Log in" }
            }
            div(classes = "error") {
                attributes["id"] = "login-error"
                attributes["role"] = "alert"
                error?.let { +it }
            }
        }
    }

    fun formFragment(
        settings: Settings,
        ctx: FormContext,
    ): String = createHTML().div(classes = "fragment") { settingsForm(this, settings, ctx) }

    private fun FlowContent.pill(service: BackupService) = with(Pages) { live("pill") { pill(Snapshot(service)) } }

    private fun settingsForm(
        parent: FlowContent,
        s: Settings,
        ctx: FormContext,
    ) = with(parent) {
        form(classes = "settings") {
            attributes["id"] = "settings-form"
            attributes["hx-post"] = "/settings/validate" + if (ctx.wizard) "?wizard=1" else ""
            attributes["hx-trigger"] = "change delay:250ms"
            attributes["hx-target"] = "this"
            attributes["hx-swap"] = "outerHTML"
            attributes["hx-select"] = "#settings-form"
            // htmx inherits these by default: without this, the live panels inside the form would
            // target the form and replace it with their own (form-less) fragment.
            attributes["hx-disinherit"] = "hx-target hx-swap hx-select hx-trigger"
            // The server validates; the browser's own check would make htmx silently drop the request.
            attributes["novalidate"] = "novalidate"

            notice(ctx)
            if (ctx.wizard) wizardSteps(s, ctx) else settingsTabs(s, ctx)
        }
    }

    /** First run: the few settings needed to start, as numbered steps on one page. */
    private fun FlowContent.wizardSteps(
        s: Settings,
        ctx: FormContext,
    ) {
        group("requirements", 1, "Check requirements", "Backupthing uses these tools to compress and upload.") {
            Pages.run { live("deps") { deps(this, ctx.service) } }
        }
        group("sources", 2, "Source folders", tab("sources").description) { sources(s, ctx) }
        group("destination", 3, "Destination", tab("destination").description) { destination(s, ctx) }
        group("schedule", 4, "Schedule", tab("schedule").description) { schedule(s, ctx) }
        savebar(ctx)
    }

    /**
     * Every section stays in the form (inactive ones hidden), so all fields are validated and saved together.
     * The shown tab is posted as `ui.tab` and rendered again after each swap; app.js switches tabs in place.
     */
    private fun FlowContent.settingsTabs(
        s: Settings,
        ctx: FormContext,
    ) {
        val active = tabs.firstOrNull { it.anchor == ctx.tab } ?: tabs.first()
        val problems = ctx.errors.groupingBy { tabOf(it.field) }.eachCount()
        input(type = InputType.hidden, name = "ui.tab") {
            id = "ui-tab"
            value = active.anchor
        }
        val items = tabs.map { Tabs.Item(it.anchor, it.title, it.icon, problems[it.anchor] ?: 0) }
        tabbed("Settings sections", items, active.anchor) {
            tabs.forEach { t ->
                group(t.anchor, null, t.title, t.description, t.danger, shown = t == active) { t.body(this, s, ctx) }
            }
            savebar(ctx)
        }
    }

    private fun FlowContent.savebar(ctx: FormContext) =
        div(classes = "savebar") {
            button(type = ButtonType.button, classes = "primary") {
                attributes["hx-post"] = "/settings" + if (ctx.wizard) "?wizard=1" else ""
                attributes["hx-target"] = "#settings-form"
                attributes["hx-swap"] = "outerHTML"
                attributes["hx-select"] = "#settings-form"
                +(if (ctx.wizard) "Save and start" else "Save settings")
            }
            if (ctx.errors.isNotEmpty()) {
                val n = ctx.errors.size
                span(classes = "error") { +"$n ${if (n == 1) "problem" else "problems"} to fix" }
            }
        }

    private fun FlowContent.notice(ctx: FormContext) {
        ctx.notice?.let {
            div(classes = "notice ok") {
                attributes["role"] = "status"
                icon("check-circle")
                span { +it }
            }
        }
        // Every problem is listed here too, so errors on fields hidden by the current choices are never invisible.
        if (ctx.errors.isNotEmpty()) {
            div(classes = "notice bad") {
                attributes["role"] = "alert"
                icon("warning-circle")
                div {
                    +"Not saved. Please fix:"
                    ul { ctx.errors.forEach { li { +"${fieldLabel(it.field)}: ${it.message}" } } }
                }
            }
        }
        val saved = ctx.service.settings.web
        if (saved.bindAddress != ctx.runningWeb.bindAddress || saved.port != ctx.runningWeb.port) {
            div(classes = "notice warn") {
                icon("warning-circle")
                span { +"Interface changes take effect after restarting Backupthing." }
            }
        }
    }

    private fun fieldLabel(field: String): String {
        val base = field.substringBefore('[')
        val index =
            Regex("\\[(\\d+)]")
                .find(field)
                ?.groupValues
                ?.get(1)
                ?.toInt()
        val name =
            fieldNames[base] ?: base
                .substringAfter('.')
                .replace(Regex("([a-z])([A-Z])"), "$1 $2")
                .lowercase()
                .replaceFirstChar { it.uppercase() }
        return if (index != null) "$name, line ${index + 1}" else name
    }

    private fun FlowContent.group(
        anchor: String,
        number: Int?,
        title: String,
        description: String,
        danger: Boolean = false,
        /** Set for a tab panel: whether it is the one shown. */
        shown: Boolean? = null,
        body: FlowContent.() -> Unit,
    ) = section(
        classes =
            listOfNotNull(
                "group",
                "panel".takeIf { shown != null },
                "danger".takeIf { danger },
            ).joinToString(" "),
    ) {
        if (shown != null) {
            tabPanel(anchor, shown)
        } else {
            attributes["id"] = anchor
            attributes["aria-labelledby"] = "$anchor-title"
        }
        div(classes = "group-head") {
            h2 {
                attributes["id"] = "$anchor-title"
                number?.let { span(classes = "step") { +"$it" } }
                +title
            }
            p { +description }
        }
        div(classes = "group-body") { body() }
    }

    private fun FlowContent.field(
        ctx: FormContext,
        name: String,
        labelText: String,
        help: String? = null,
        control: FlowContent.(id: String) -> Unit,
    ) {
        val id = name.replace('.', '-')
        val errs = ctx.errorsFor(name)
        div(classes = if (errs.isEmpty()) "field" else "field invalid") {
            label {
                attributes["for"] = id
                +labelText
            }
            control(id)
            help?.let { div(classes = "help") { +it } }
            errs.forEach { div(classes = "error") { +it } }
        }
    }

    private fun FlowContent.text(
        ctx: FormContext,
        name: String,
        labelText: String,
        value: String,
        help: String? = null,
        type: InputType = InputType.text,
        placeholder: String? = null,
    ) = field(ctx, name, labelText, help) { id ->
        input(type = type, name = name) {
            this.id = id
            this.value = value
            placeholder?.let { this.placeholder = it }
            attributes["spellcheck"] = "false"
            attributes["autocomplete"] = "off"
        }
    }

    private fun FlowContent.number(
        ctx: FormContext,
        name: String,
        labelText: String,
        value: Int,
        range: IntRange,
        help: String? = null,
    ) = field(ctx, name, labelText, help) { id ->
        input(type = InputType.number, name = name) {
            this.id = id
            this.value = value.toString()
            attributes["min"] = range.first.toString()
            attributes["max"] = range.last.toString()
            attributes["inputmode"] = "numeric"
        }
    }

    /** Range input with its live value (kept in sync by app.js). */
    private fun FlowContent.slider(
        ctx: FormContext,
        name: String,
        labelText: String,
        value: Int,
        range: IntRange,
        help: String,
    ) = field(ctx, name, labelText, help) { id ->
        div(classes = "slider") {
            input(type = InputType.range, name = name) {
                this.id = id
                this.value = value.toString()
                attributes["min"] = range.first.toString()
                attributes["max"] = range.last.toString()
                attributes["data-output"] = "$id-value"
            }
            output {
                attributes["id"] = "$id-value"
                attributes["for"] = id
                +value.toString()
            }
        }
    }

    /** Two or three options: a segmented radio group instead of a dropdown. */
    private fun <E : Enum<E>> FlowContent.segmented(
        ctx: FormContext,
        name: String,
        labelText: String,
        value: E,
        options: List<Pair<E, String>>,
        help: String? = null,
    ) {
        val id = name.replace('.', '-')
        val errs = ctx.errorsFor(name)
        div(classes = if (errs.isEmpty()) "field" else "field invalid") {
            span(classes = "label") {
                attributes["id"] = "$id-label"
                +labelText
            }
            div(classes = "segmented") {
                attributes["role"] = "radiogroup"
                attributes["aria-labelledby"] = "$id-label"
                options.forEach { (opt, text) ->
                    label {
                        input(type = InputType.radio, name = name) {
                            this.value = opt.wire
                            checked = opt == value
                            this.id = "$id-${opt.wire}"
                        }
                        span { +text }
                    }
                }
            }
            help?.let { div(classes = "help") { +it } }
            errs.forEach { div(classes = "error") { +it } }
        }
    }

    private fun <E : Enum<E>> FlowContent.choice(
        ctx: FormContext,
        name: String,
        labelText: String,
        value: E,
        options: List<Pair<E, String>>,
        help: String? = null,
    ) = field(ctx, name, labelText, help) { id ->
        select {
            this.id = id
            this.name = name
            options.forEach { (opt, text) ->
                option {
                    this.value = opt.wire
                    selected = opt == value
                    +text
                }
            }
        }
    }

    private fun FlowContent.checkbox(
        name: String,
        labelText: String,
        checked: Boolean,
        help: String,
    ) = div(classes = "field") {
        input(type = InputType.hidden, name = "$name.present") { value = "1" }
        label(classes = "check") {
            input(type = InputType.checkBox, name = name) {
                id = name.replace('.', '-')
                this.checked = checked
            }
            span {
                +labelText
                span(classes = "help") { +help }
            }
        }
    }

    private fun FlowContent.sources(
        s: Settings,
        ctx: FormContext,
    ) {
        field(ctx, "sources.roots", "Folders to back up", "One folder per line. Sub-folders are included.") { id ->
            textArea(rows = "3") {
                this.id = id
                name = "sources.roots"
                attributes["spellcheck"] = "false"
                +s.sources.roots.joinToString("\n")
            }
        }
        div(classes = "browse") {
            button(type = ButtonType.button, classes = "small") {
                val start = s.sources.roots.firstOrNull() ?: System.getProperty("user.home")
                attributes["hx-get"] = "/fs/dirs?path=" + java.net.URLEncoder.encode(start, Charsets.UTF_8)
                attributes["hx-target"] = "#dir-browser"
                attributes["hx-trigger"] = "click"
                icon("folder-open")
                +"Browse folders"
            }
            div(classes = "dir-browser") {
                attributes["id"] = "dir-browser"
                attributes["hx-preserve"] = "true"
            }
        }
        if (!ctx.wizard) {
            text(
                ctx,
                "sources.photoExtensions",
                "Photos, compressed to AVIF",
                s.sources.photoExtensions.joinToString(", "),
            )
            text(
                ctx,
                "sources.videoExtensions",
                "Videos, compressed to AV1",
                s.sources.videoExtensions.joinToString(", "),
            )
            text(
                ctx,
                "sources.passthroughExtensions",
                "Uploaded unchanged",
                s.sources.passthroughExtensions.joinToString(", "),
            )
            ctx.errorsFor("sources.extensions").forEach { div(classes = "error") { +it } }
            number(
                ctx,
                "sources.settleSeconds",
                "Settle time in seconds",
                s.sources.settleSeconds,
                0..86_400,
                "Files changed more recently than this wait for the next run, so half-synced files are skipped.",
            )
        }
    }

    private fun FlowContent.destination(
        s: Settings,
        ctx: FormContext,
    ) {
        segmented(
            ctx,
            "destination.mode",
            "Back up to",
            s.destination.mode,
            listOf(DestinationMode.DRIVE to "Drive folder", DestinationMode.PHOTOS to "Photos timeline"),
            if (s.destination.mode == DestinationMode.DRIVE) {
                "A folder in My files that mirrors your source folders."
            } else {
                "Your Proton Photos timeline, dated from each photo. " +
                    "Types Photos can't take (such as DNG) are reported as failed."
            },
        )
        if (s.destination.mode == DestinationMode.DRIVE) {
            text(
                ctx,
                "destination.remoteRoot",
                "Remote folder",
                s.destination.remoteRoot,
                "Created if it doesn't exist.",
            )
        }
        div(classes = "field") {
            span(classes = "label") { +"Proton account" }
            Pages.run { live("login") { loginPanel(this, ctx.service) } }
        }
        if (!ctx.wizard) {
            segmented(
                ctx,
                "destination.credentialStore",
                "Keep the sign-in in",
                s.destination.credentialStore,
                listOf(CredentialStore.KEYCHAIN to "System keychain", CredentialStore.PASS to "pass"),
            )
        }
    }

    private fun FlowContent.compression(
        s: Settings,
        ctx: FormContext,
    ) {
        val c = s.compression
        checkbox(
            "compression.keepOriginalIfSmaller",
            "Upload the original when compressing doesn't make it smaller",
            c.keepOriginalIfSmaller,
            "Recommended.",
        )
        h3(classes = "subhead") { +"Photos" }
        div(classes = "row2") {
            slider(
                ctx,
                "compression.photoQuality",
                "Photo quality",
                c.photoQuality,
                0..100,
                "70 looks the same as the original.",
            )
            slider(
                ctx,
                "compression.photoSpeed",
                "Photo encoding speed",
                c.photoSpeed,
                0..10,
                "Higher is faster, lower is smaller.",
            )
        }
        div(classes = "row2") {
            segmented(
                ctx,
                "compression.motionPhotoMode",
                "Motion photos",
                c.motionPhotoMode,
                listOf(MotionPhotoMode.STILL_ONLY to "Still only", MotionPhotoMode.AS_IS to "Unchanged"),
                "Still only drops the short clip from the backup copy. The original keeps it.",
            )
            number(
                ctx,
                "concurrency.photos",
                "Photos at once",
                s.concurrency.photos,
                0..64,
                "0 uses half the CPU cores.",
            )
        }
        h3(classes = "subhead") { +"Videos" }
        val working =
            ctx.service.deps.value
                ?.workingVideoBackends
        val label = { b: VideoBackend, text: String ->
            if (working == null || b in working) text else "$text (not available here)"
        }
        div(classes = "row2") {
            choice(
                ctx,
                "compression.videoBackend",
                "Video encoder",
                c.videoBackend,
                listOf(
                    VideoBackend.AUTO to "Automatic (${working?.firstOrNull()?.wire ?: "best available"})",
                    VideoBackend.NVENC to label(VideoBackend.NVENC, "NVIDIA NVENC"),
                    VideoBackend.QSV to label(VideoBackend.QSV, "Intel Quick Sync"),
                    VideoBackend.VAAPI to label(VideoBackend.VAAPI, "VA-API"),
                    VideoBackend.SVT_AV1 to label(VideoBackend.SVT_AV1, "SVT-AV1 on the CPU"),
                ),
                "If a hardware encoder fails on a file, it is retried on the CPU.",
            )
            number(ctx, "concurrency.videos", "Videos at once", s.concurrency.videos, 1..16)
        }
        div(classes = "field") {
            span(classes = "label") { +"Video quality per encoder" }
            div(classes = "row4") {
                number(ctx, "compression.nvencCq", "NVENC", c.nvencCq, 0..63)
                number(ctx, "compression.svtAv1Crf", "SVT-AV1", c.svtAv1Crf, 0..63)
                number(ctx, "compression.vaapiQp", "VA-API", c.vaapiQp, 0..63)
                number(ctx, "compression.qsvQuality", "Quick Sync", c.qsvQuality, 0..63)
            }
            div(classes = "help") {
                +"Lower is better quality. The NVENC and SVT-AV1 defaults look the same as the original."
            }
        }
        samplePreview(ctx)
    }

    private fun FlowContent.samplePreview(ctx: FormContext) {
        val recent = runCatching { ctx.service.recentSources(30) }.getOrDefault(emptyList())
        div(classes = "preview") {
            h3 { +"Try it on one of your files" }
            p(classes = "help") { +"Encodes a test copy with the values above, before you save. Nothing is uploaded." }
            div(classes = "inline") {
                select {
                    id = "sample-path"
                    name = "sample.path"
                    attributes["aria-label"] = "File to test"
                    if (recent.isEmpty()) {
                        option {
                            value = ""
                            +"No files scanned yet. Run a backup first."
                        }
                    }
                    recent.forEach { path ->
                        option {
                            value = path.toString()
                            +path.fileName.toString()
                        }
                    }
                }
                button(type = ButtonType.button) {
                    attributes["hx-post"] = "/sample"
                    attributes["hx-target"] = "#sample-result"
                    attributes["hx-swap"] = "innerHTML"
                    attributes["hx-indicator"] = "#sample-result"
                    if (recent.isEmpty()) attributes["disabled"] = "disabled"
                    +"Test"
                }
            }
            div(classes = "sample-result") {
                attributes["id"] = "sample-result"
                attributes["hx-preserve"] = "true"
                attributes["aria-live"] = "polite"
            }
        }
    }

    private fun FlowContent.schedule(
        s: Settings,
        ctx: FormContext,
    ) {
        segmented(
            ctx,
            "schedule.mode",
            "Run",
            s.schedule.mode,
            listOf(
                ScheduleMode.DAILY to "Daily",
                ScheduleMode.INTERVAL to "Every few hours",
                ScheduleMode.MANUAL to "Only manually",
            ),
            when (s.schedule.mode) {
                ScheduleMode.DAILY -> "If the computer is off at that time, the run starts as soon as it's back."
                ScheduleMode.INTERVAL -> "Counted from the start of the last run."
                ScheduleMode.MANUAL -> "Runs start only from Run now."
            },
        )
        when (s.schedule.mode) {
            ScheduleMode.DAILY -> {
                text(ctx, "schedule.dailyTime", "Time", s.schedule.dailyTime, type = InputType.time)
            }

            ScheduleMode.INTERVAL -> {
                number(
                    ctx,
                    "schedule.intervalHours",
                    "Hours between runs",
                    s.schedule.intervalHours,
                    1..168,
                )
            }

            ScheduleMode.MANUAL -> {}
        }
    }

    /**
     * Number plus unit picker, fused into one control. Empty means no limit. The value is shown in the
     * largest unit it fills, exactly (factors are 1000, so three decimals always suffice).
     */
    private fun FlowContent.quantity(
        ctx: FormContext,
        name: String,
        labelText: String,
        value: Int,
        units: List<QuantityUnit>,
        help: String? = null,
    ) = field(ctx, name, labelText, help) { id ->
        val unit = if (value == 0) units.last() else units.last { it.factor <= value }
        div(classes = "quantity") {
            input(type = InputType.text, name = name) {
                this.id = id
                this.value =
                    if (value == 0) {
                        ""
                    } else {
                        value
                            .toBigDecimal()
                            .divide(unit.factor.toBigDecimal())
                            .stripTrailingZeros()
                            .toPlainString()
                    }
                placeholder = "No limit"
                attributes["inputmode"] = "decimal"
                attributes["autocomplete"] = "off"
            }
            select {
                this.name = "$name.unit"
                attributes["aria-label"] = "Unit for ${labelText.lowercase()}"
                units.forEach { u ->
                    option {
                        this.value = u.wire
                        selected = u == unit
                        +u.label
                    }
                }
            }
        }
    }

    /** How much of a cap is used, as a meter and a line of text. */
    private fun FlowContent.usage(
        used: Long,
        cap: Long,
        caption: String,
    ) {
        val full = used >= cap
        div(classes = "usage") {
            div(classes = if (full) "meter full" else "meter") {
                attributes["role"] = "meter"
                attributes["aria-valuemin"] = "0"
                attributes["aria-valuemax"] = cap.toString()
                attributes["aria-valuenow"] = used.coerceAtMost(cap).toString()
                attributes["aria-label"] = caption
                span { attributes["style"] = "width: ${(used * 100 / cap).coerceIn(0, 100)}%" }
            }
            span { +caption }
        }
    }

    private fun FlowContent.uploads(
        s: Settings,
        ctx: FormContext,
    ) {
        val b = s.bandwidth
        div(classes = "row2") {
            number(ctx, "concurrency.uploads", "Uploads at once", s.concurrency.uploads, 1..8)
            number(
                ctx,
                "concurrency.maxAttempts",
                "Attempts per file",
                s.concurrency.maxAttempts,
                1..20,
                "Tries before a file counts as failed.",
            )
        }
        quantity(
            ctx,
            "bandwidth.uploadLimitKBps",
            "Upload speed limit",
            b.uploadLimitKBps,
            SPEED_UNITS,
            "Shared by all parallel uploads. Leave empty for no limit.",
        )
        val usage = runCatching { ctx.service.bandwidthUsage(b) }.getOrNull()
        val mb = UploadBudget.MB
        div(classes = "row2") {
            div(classes = "stack") {
                quantity(ctx, "bandwidth.dailyCapMB", "Daily cap", b.dailyCapMB, CAP_UNITS)
                if (usage != null && b.dailyCapMB > 0) {
                    usage(usage.today, b.dailyCapMB * mb, "${Format.bytes(usage.today)} used today")
                }
            }
            div(classes = "stack") {
                quantity(ctx, "bandwidth.monthlyCapMB", "Monthly cap", b.monthlyCapMB, CAP_UNITS)
                if (usage != null && b.monthlyCapMB > 0) {
                    val resets = Format.day(usage.monthResetsAt.toInstant())
                    usage(usage.thisMonth, b.monthlyCapMB * mb, "${Format.bytes(usage.thisMonth)} used, resets $resets")
                }
                if (b.monthlyCapMB > 0) {
                    number(
                        ctx,
                        "bandwidth.monthResetDay",
                        "Month starts on day",
                        b.monthResetDay,
                        1..28,
                        "Match the day your internet plan's allowance starts over.",
                    )
                }
            }
        }
        p(classes = "help") {
            +"Files that would go over a cap wait for the next day or month. Caps count the size of uploaded "
            +"files. Retries and Proton's small overhead are not included, so leave some headroom."
        }
    }

    private fun FlowContent.web(
        s: Settings,
        ctx: FormContext,
    ) {
        div(classes = "row2") {
            text(ctx, "web.bindAddress", "Listen on", s.web.bindAddress, "127.0.0.1 keeps it on this computer.")
            number(ctx, "web.port", "Port", s.web.port, 1024..65535)
        }
        field(ctx, "web.accessToken", "Access token", "Needed when listening on the network. Keep it secret.") { id ->
            div(classes = "inline") {
                input(type = InputType.password, name = "web.accessToken") {
                    this.id = id
                    value = s.web.accessToken
                    attributes["autocomplete"] = "new-password"
                }
                button(type = ButtonType.button) {
                    attributes["data-generate-token"] = id
                    +"Generate"
                }
            }
        }
        text(
            ctx,
            "web.allowedHosts",
            "Extra host names",
            s.web.allowedHosts.joinToString(", "),
            "Names or addresses you use to open this page from other devices.",
        )
    }

    private fun FlowContent.system(
        s: Settings,
        ctx: FormContext,
    ) {
        div(classes = "field") {
            span(classes = "label") { +"Tools" }
            Pages.run { live("deps") { deps(this, ctx.service) } }
        }
        choice(
            ctx,
            "system.logLevel",
            "Log level",
            LogLevel.entries.firstOrNull { it.name == s.system.logLevel.uppercase() } ?: LogLevel.INFO,
            LogLevel.entries.map { it to it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
            "Applies immediately.",
        )
        text(
            ctx,
            "system.stagingDir",
            "Staging folder",
            s.system.stagingDir,
            "Where compressed copies wait until they are uploaded. Empty uses the cache folder.",
            placeholder = "Default",
        )
        div(classes = "field") {
            span(classes = "label") { +"Tool locations" }
            div(classes = "row2") {
                text(ctx, "system.avifencPath", "avifenc", s.system.avifencPath, placeholder = "Found on PATH")
                text(ctx, "system.ffmpegPath", "ffmpeg", s.system.ffmpegPath, placeholder = "Found on PATH")
                text(ctx, "system.ffprobePath", "ffprobe", s.system.ffprobePath, placeholder = "Found on PATH")
                text(
                    ctx,
                    "system.protonDrivePath",
                    "proton-drive",
                    s.system.protonDrivePath,
                    placeholder = "Found on PATH",
                )
            }
        }
    }

    private fun FlowContent.maintenance() {
        div(classes = "field") {
            span(classes = "label") { +"Retry failed files" }
            div(classes = "help") { +"Gives every failed file a fresh set of attempts and starts a run." }
            div {
                button(type = ButtonType.button) {
                    attributes["hx-post"] = "/retry-failed"
                    attributes["hx-swap"] = "none"
                    icon("arrows-clockwise")
                    +"Retry failed files"
                }
            }
        }
        div(classes = "field") {
            label {
                attributes["for"] = "reset-confirm"
                +"Reset database"
            }
            div(classes = "help") {
                +"Forgets which files were backed up. "
                +"The next run compresses everything again and checks it against the remote. "
                +"Type RESET to confirm."
            }
            div(classes = "inline") {
                input(type = InputType.text, name = "reset.confirm") {
                    id = "reset-confirm"
                    attributes["autocomplete"] = "off"
                }
                button(type = ButtonType.button, classes = "danger") {
                    attributes["hx-post"] = "/reset"
                    attributes["hx-include"] = "#reset-confirm"
                    attributes["hx-target"] = "#reset-result"
                    +"Reset database"
                }
            }
            div(classes = "help") {
                attributes["id"] = "reset-result"
                attributes["role"] = "status"
            }
        }
    }

    /** Log levels for the select. */
    enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR }

    fun loginPanel(
        parent: FlowContent,
        service: BackupService,
    ) = with(parent) {
        div(classes = "account") {
            when (val st = service.login.state.value) {
                is LoginState.Waiting -> {
                    div(classes = "signin") {
                        span { +"Open the sign-in page. It also works from another device. Keep this page open." }
                        a(href = st.url, target = "_blank") {
                            attributes["rel"] = "noopener noreferrer"
                            icon("arrow-square-out")
                            +" Sign in to Proton"
                        }
                    }
                    loginButton("Cancel", "/auth/login/cancel", quiet = true)
                }

                LoginState.Starting -> {
                    span(classes = "state") {
                        icon("arrows-clockwise")
                        +"Starting sign-in…"
                    }
                }

                LoginState.Succeeded -> {
                    span(classes = "state ok") {
                        icon("check-circle")
                        +"Signed in"
                    }
                }

                is LoginState.Failed -> {
                    span(classes = "state bad") {
                        icon("x-circle")
                        +"Sign-in failed: ${st.message}"
                    }
                    loginButton("Try again")
                }

                LoginState.Idle -> {
                    when (val auth = service.auth.value) {
                        AuthStatus.Ok -> {
                            span(classes = "state ok") {
                                icon("check-circle")
                                +"Logged in"
                            }
                            loginButton("Log in again", quiet = true)
                        }

                        is AuthStatus.Failed -> {
                            span(classes = "state bad") {
                                icon("warning-circle")
                                +auth.message
                            }
                            loginButton("Log in to Proton", primary = true)
                        }

                        null -> {
                            span(classes = "state") { +"Checking…" }
                        }
                    }
                }
            }
        }
    }

    private fun FlowContent.loginButton(
        text: String,
        path: String = "/auth/login",
        primary: Boolean = false,
        quiet: Boolean = false,
    ) = button(
        type = ButtonType.button,
        classes = listOfNotNull("small", "primary".takeIf { primary }, "quiet".takeIf { quiet }).joinToString(" "),
    ) {
        attributes["hx-post"] = path
        attributes["hx-swap"] = "none"
        +text
    }

    /** "ffmpeg version n9.0.2 Copyright…" → "9.0.2". */
    private fun shortVersion(raw: String?): String =
        raw?.let { Regex("""(\d+\.\d+(?:\.\d+)?)""").find(it)?.value } ?: raw.orEmpty()

    fun deps(
        parent: FlowContent,
        service: BackupService,
    ) = with(parent) {
        val report = service.deps.value
        if (report == null) {
            p(classes = "empty") { +"Checking tools…" }
            return@with
        }
        dl(classes = "tools") {
            val list = this
            report.tools.forEach { t ->
                div {
                    attributes["title"] = t.version ?: ""
                    span(classes = "icon ${if (t.found) "ok" else "bad"}") {
                        icon(if (t.found) "check-circle" else "x-circle")
                    }
                    list.dt { +t.name }
                    list.dd { +(if (t.found) "v" + shortVersion(t.version) else "Not found") }
                }
            }
            val none = report.workingVideoBackends.isEmpty()
            div {
                span(classes = "icon ${if (none) "bad" else "ok"}") { icon(if (none) "x-circle" else "check-circle") }
                list.dt { +"AV1 encoders" }
                list.dd { +report.workingVideoBackends.joinToString { it.wire }.ifEmpty { "None working" } }
            }
        }
        div {
            button(type = ButtonType.button, classes = "small quiet") {
                attributes["hx-post"] = "/deps/check"
                attributes["hx-swap"] = "none"
                icon("arrows-clockwise")
                +"Check again"
            }
        }
    }

    fun dirBrowser(path: Path): String =
        createHTML().div(classes = "fragment") {
            val dirs =
                runCatching {
                    Files.list(path).use { s ->
                        s
                            .filter { Files.isDirectory(it) && Files.isReadable(it) }
                            .filter { !it.fileName.toString().startsWith(".") }
                            .sorted()
                            .toList()
                    }
                }.getOrElse { emptyList() }
            div(classes = "dir-head") {
                code { +path.toString() }
                button(type = ButtonType.button, classes = "small primary") {
                    attributes["data-add-root"] = path.toString()
                    +"Add this folder"
                }
            }
            ul(classes = "dirs") {
                path.parent?.let { up -> li { dirLink(up, "..", "arrow-up") } }
                dirs.take(500).forEach { d -> li { dirLink(d, d.fileName.toString(), "folder-simple") } }
                if (dirs.isEmpty()) li(classes = "empty") { +"No sub-folders" }
            }
        }

    private fun FlowContent.dirLink(
        path: Path,
        text: String,
        iconName: String,
    ) = button(type = ButtonType.button) {
        attributes["hx-get"] = "/fs/dirs?path=" + java.net.URLEncoder.encode(path.toString(), Charsets.UTF_8)
        attributes["hx-target"] = "#dir-browser"
        icon(iconName)
        +text
    }

    fun sampleResult(r: Result<SampleResult>): String =
        createHTML().div(classes = "fragment") {
            r.fold(
                onSuccess = { res ->
                    val pct = if (res.inputBytes > 0) res.outputBytes * 100 / res.inputBytes else 0
                    div(classes = "sample-stats") {
                        div {
                            b { +"$pct%" }
                            span { +"of the original size" }
                        }
                        div {
                            b { +Format.bytes(res.outputBytes) }
                            span { +"from ${Format.bytes(res.inputBytes)}" }
                        }
                        div {
                            b { +"${"%.1f".format(res.encodeMs / 1000.0)} s" }
                            span { +(res.backend?.let { "on ${it.wire}" } ?: "to encode") }
                        }
                    }
                    if (res.hasPreview) {
                        div(classes = "compare") {
                            figure {
                                img(src = "/sample/${res.id}/before", alt = "Original, centre crop at full size")
                                figcaption { +"Original" }
                            }
                            figure {
                                img(src = "/sample/${res.id}/after", alt = "Compressed copy, same crop")
                                figcaption { +"Compressed" }
                            }
                        }
                    }
                },
                onFailure = { e -> p(classes = "error") { +(e.message ?: "The test failed.") } },
            )
        }
}
