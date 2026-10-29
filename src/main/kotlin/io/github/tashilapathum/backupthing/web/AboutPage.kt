// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.web

import io.github.tashilapathum.backupthing.BuildInfo
import io.github.tashilapathum.backupthing.service.BackupService
import io.github.tashilapathum.backupthing.web.Icons.icon
import io.github.tashilapathum.backupthing.web.Tabs.tabPanel
import io.github.tashilapathum.backupthing.web.Tabs.tabbed
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.b
import kotlinx.html.blockQuote
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.img
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.ul

/** Who made Backupthing and why, privacy, terms, license and contact. */
object AboutPage {
    private const val REPO = "https://github.com/tashilapathum/backupthing"
    private const val EMAIL = "tashila.pathum@gmail.com"
    private const val NEOMUSIC = "https://neomusic.tashila.me"
    private const val PORTFOLIO = "https://tashila.me"

    private val tabs =
        listOf(
            Tabs.Item("project", "Project", "info"),
            Tabs.Item("privacy", "Privacy", "shield-check"),
            Tabs.Item("terms", "Terms", "file-text"),
            Tabs.Item("license", "License", "scales"),
        )

    fun page(
        html: HTML,
        service: BackupService,
        csrf: String,
        tab: String?,
    ) = Layout.page(
        html,
        "About",
        csrf,
        active = "/about",
        pill = { Pages.run { live("pill") { pill(Snapshot(service)) } } },
    ) {
        val active = tabs.firstOrNull { it.anchor == tab }?.anchor ?: tabs.first().anchor
        div(classes = "about") {
            head()
            tabbed("About sections", tabs, active) {
                panel("project", active) {
                    // Why it exists, in the author's words (no heading: it opens the page).
                    section {
                        attributes["id"] = "why"
                        attributes["aria-label"] = "Why I made it"
                        blockQuote(classes = "note") {
                            p {
                                +"I made Backupthing for personal use, so I could save my Proton Drive space by adding "
                                +"a compression layer. Made this public hoping it would be helpful for someone. I use "
                                +"this as a daily driver so I'll keep working on this actively, although other "
                                +"platform or cloud drive support won't be added unless I get some PRs. :)"
                            }
                        }
                        p {
                            +"Pull requests are welcome. "
                            link("$REPO/blob/main/CONTRIBUTING.md", "Read how to contribute")
                            +"."
                        }
                    }
                    part("what", "What it does") {
                        ul {
                            li {
                                +"Compresses photos to AVIF and videos to AV1, "
                                +"tuned to look the same as the originals."
                            }
                            li {
                                +"Uploads only the compressed copies to Proton Drive, "
                                +"with the official proton-drive tool."
                            }
                            li { +"Never changes or deletes your originals." }
                            li {
                                +"Runs on its own schedule and picks up where it left off "
                                +"after a reboot or a lost connection."
                            }
                        }
                    }
                    part("contact", "Contact") {
                        div(classes = "links") {
                            card("mailto:$EMAIL", "envelope-simple", "Email", EMAIL)
                            card("$REPO/issues/new?template=bug_report.yml", "bug", "Report a bug", "On GitHub")
                            card(
                                "$REPO/issues/new?template=feature_request.yml",
                                "info",
                                "Suggest a feature",
                                "On GitHub",
                            )
                        }
                    }
                    part("support", "Support") {
                        p { +"If Backupthing saves you some space, here is how you can help." }
                        div(classes = "links") {
                            card(NEOMUSIC, "music-notes", "NeoMusic", "Check out my other project")
                            card(PORTFOLIO, "globe-simple", "tashila.me", "My portfolio")
                            card(REPO, "heart", "Star on GitHub", "Helps others find it")
                        }
                    }
                }
                panel("privacy", active, "Privacy") {
                    ul {
                        li {
                            +"No accounts, analytics, telemetry or update checks. Backupthing itself makes no internet "
                            +"connections, and this dashboard loads nothing from other sites."
                        }
                        li {
                            +"Your files leave this computer only as uploads to your own Proton account, made by the "
                            +"proton-drive tool. "
                            link("https://proton.me/legal/privacy", "Proton's privacy policy")
                            +" covers what is stored there."
                        }
                        li {
                            +"Signing in is handled by proton-drive, which keeps the session in your system keychain "
                            +"or pass. Backupthing never sees your Proton password."
                        }
                        li {
                            +"Kept on this computer: the settings file, a database of file paths, sizes and backup "
                            +"status, compressed copies waiting to upload (deleted once uploaded), and logs."
                        }
                        li {
                            +"The dashboard is only reachable from this computer unless you change "
                            b { +"Listen on" }
                            +" in Settings, and then it asks for an access token."
                        }
                    }
                }
                panel("terms", active, "Terms") {
                    ul {
                        li {
                            +"Backupthing is free software: you can use, study, share and change it under the GNU "
                            +"General Public License, version 3 or later."
                        }
                        li {
                            +"It comes without any warranty, to the extent the law allows (sections 15 and 16 of the "
                            +"license). It never changes your originals, but keep them anyway and check now and then "
                            +"that your backups open."
                        }
                        li {
                            +"Your use of Proton Drive is covered by "
                            link("https://proton.me/legal/terms", "Proton's terms of service")
                            +"."
                        }
                        li {
                            +"Backupthing is an independent project, not affiliated with or endorsed by Proton AG. "
                            +"Proton and Proton Drive are trademarks of Proton AG."
                        }
                    }
                }
                panel("license", active, "License") {
                    p {
                        +"© 2026 Tashila Pathum. Released under "
                        link("$REPO/blob/main/LICENSE", "GPL-3.0-or-later")
                        +"."
                    }
                    p { +"Built with:" }
                    ul(classes = "credits") {
                        li { credit("htmx", "0BSD") }
                        li { credit("Geist and Geist Mono fonts", "SIL Open Font License 1.1") }
                        li { credit("Phosphor Icons", "MIT") }
                        li { credit("Kotlin, Ktor, SQLDelight and other Java libraries", "Apache 2.0 and others") }
                    }
                    p {
                        +"It runs "
                        code { +"ffmpeg" }
                        +", "
                        code { +"avifenc" }
                        +" and "
                        code { +"proton-drive" }
                        +", which you install yourself. Full details are in "
                        link("$REPO/blob/main/THIRD_PARTY_NOTICES", "the third-party notices")
                        +"."
                    }
                }
            }
        }
    }

    private fun FlowContent.head() {
        div(classes = "about-head") {
            img(src = "/static/icon.svg", alt = "")
            div {
                h1 {
                    +"Backupthing"
                    val v = BuildInfo.version
                    span(classes = "badge") { +(if (v.first().isDigit()) "v$v" else v) }
                }
                p {
                    +"Backs up the photos and videos your phone syncs to your Linux PC, compressed, to Proton Drive."
                }
            }
        }
        div(classes = "about-actions") {
            button(REPO, "github-logo", "Source code")
            button("$REPO/releases", "arrow-square-out", "Releases")
            button("$REPO/blob/main/CHANGELOG.md", "arrow-square-out", "Changelog")
        }
    }

    private fun FlowContent.part(
        anchor: String,
        title: String,
        body: FlowContent.() -> Unit,
    ) = section {
        attributes["id"] = anchor
        attributes["aria-labelledby"] = "$anchor-title"
        h2 {
            attributes["id"] = "$anchor-title"
            +title
        }
        body()
    }

    /** A tab panel; [title] is its heading (the project panel has headed parts instead). */
    private fun FlowContent.panel(
        anchor: String,
        active: String,
        title: String? = null,
        body: FlowContent.() -> Unit,
    ) = div(classes = "panel") {
        tabPanel(anchor, anchor == active)
        title?.let { h2 { +it } }
        body()
    }

    /** Links to other sites open in a new tab. */
    private fun FlowContent.link(
        href: String,
        text: String,
    ) = a(href = href, target = "_blank") {
        attributes["rel"] = "noopener noreferrer"
        +text
    }

    private fun FlowContent.button(
        href: String,
        iconName: String,
        text: String,
    ) = a(href = href, target = "_blank", classes = "button") {
        attributes["rel"] = "noopener noreferrer"
        icon(iconName)
        +text
    }

    private fun FlowContent.card(
        href: String,
        iconName: String,
        title: String,
        subtitle: String,
    ) = a(href = href, classes = "link-card") {
        if (href.startsWith("http")) {
            target = "_blank"
            attributes["rel"] = "noopener noreferrer"
        }
        icon(iconName)
        span {
            b { +title }
            span {
                // An address may only wrap before its "@".
                val at = subtitle.indexOf('@')
                if (at > 0) {
                    span(classes = "keep") { +subtitle.substring(0, at) }
                    span(classes = "keep") { +subtitle.substring(at) }
                } else {
                    +subtitle
                }
            }
        }
    }

    private fun FlowContent.credit(
        name: String,
        license: String,
    ) {
        span { +name }
        span(classes = "muted") { +license }
    }
}
