// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.web

import io.github.tashilapathum.backupthing.web.Icons.icon
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.MAIN
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.id
import kotlinx.html.img
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.title

object Layout {
    /**
     * Common page shell. The CSRF token goes into a meta tag that app.js attaches to every htmx request;
     * htmx's injected indicator styles are disabled because the CSP forbids inline `<style>`.
     *
     * @param pill renders the live status badge in the top bar (null on pages without the service, e.g. login).
     */
    fun page(
        html: HTML,
        pageTitle: String,
        csrf: String,
        active: String?,
        pill: (FlowContent.() -> Unit)? = null,
        content: MAIN.() -> Unit,
    ) = with(html) {
        attributes["lang"] = "en"
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            meta(name = "htmx-config", content = """{"includeIndicatorStyles":false,"allowEval":false}""")
            // app.js adds this to every htmx request (independent of htmx attribute inheritance).
            meta(name = "csrf-token", content = csrf)
            title(if (pageTitle == "Backupthing") pageTitle else "$pageTitle · Backupthing")
            link(rel = "preload", href = "/static/fonts/Geist-Variable.woff2") {
                attributes["as"] = "font"
                attributes["type"] = "font/woff2"
                attributes["crossorigin"] = "anonymous"
            }
            link(rel = "stylesheet", href = "/static/style.css")
            link(rel = "icon", href = "/static/icon.svg")
            // Not deferred: sets the saved light/dark theme before anything is drawn.
            script(src = "/static/theme.js") {}
            script(src = "/static/htmx.min.js") {}
            script(src = "/static/app.js") { defer = true }
        }
        body {
            header(classes = "topbar") {
                a(href = "/", classes = "wordmark") {
                    img(src = "/static/icon.svg", alt = "")
                    span { +"Backupthing" }
                }
                if (active != null) {
                    nav(classes = "tabs") {
                        attributes["aria-label"] = "Main"
                        tab("/", "Overview", active)
                        tab("/settings", "Settings", active)
                        tab("/about", "About", active)
                    }
                }
                div(classes = "spacer") {}
                pill?.invoke(this)
                themeToggle()
            }
            main { content() }
            div(classes = "connection") {
                attributes["id"] = "connection"
                attributes["role"] = "status"
                icon("arrows-clockwise")
                +"Reconnecting to live updates…"
            }
        }
    }

    /** Light/dark switch (app.js); CSS shows the moon in light mode and the sun in dark mode. */
    private fun FlowContent.themeToggle() =
        button(type = ButtonType.button, classes = "quiet theme-toggle") {
            id = "theme-toggle"
            attributes["aria-label"] = "Dark theme"
            attributes["title"] = "Switch between light and dark"
            span(classes = "moon") { icon("moon") }
            span(classes = "sun") { icon("sun") }
        }

    private fun FlowContent.tab(
        href: String,
        label: String,
        active: String,
    ) = a(href = href, classes = if (active == href) "active" else null) {
        if (active == href) attributes["aria-current"] = "page"
        +label
    }
}
