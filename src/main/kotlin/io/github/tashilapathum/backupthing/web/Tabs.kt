// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.web

import io.github.tashilapathum.backupthing.web.Icons.icon
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.Tag
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.span

/**
 * Sections shown one at a time: a vertical rail beside the panel on wide screens, a horizontal strip on narrow
 * ones. The server renders the active tab (from `?tab=`); app.js switches tabs in place.
 */
object Tabs {
    class Item(
        val anchor: String,
        val title: String,
        val icon: String,
        /** Problems to fix in this section, shown as a count. */
        val problems: Int = 0,
    )

    /** The tab list and a `.panels` column; [panels] renders one element per item marked with [tabPanel]. */
    fun FlowContent.tabbed(
        label: String,
        items: List<Item>,
        active: String,
        panels: FlowContent.() -> Unit,
    ) = div(classes = "tabbed") {
        div(classes = "tablist") {
            attributes["role"] = "tablist"
            attributes["aria-label"] = label
            attributes["aria-orientation"] = "vertical"
            items.forEach { t ->
                val selected = t.anchor == active
                button(type = ButtonType.button, classes = "tab") {
                    id = "tab-${t.anchor}"
                    attributes["role"] = "tab"
                    attributes["aria-controls"] = t.anchor
                    attributes["aria-selected"] = selected.toString()
                    attributes["tabindex"] = if (selected) "0" else "-1"
                    attributes["data-tab"] = t.anchor
                    icon(t.icon)
                    span { +t.title }
                    if (t.problems > 0) {
                        span(classes = "tab-errors") {
                            +"${t.problems}"
                            span(classes = "visually-hidden") { +(if (t.problems == 1) " problem" else " problems") }
                        }
                    }
                }
            }
        }
        div(classes = "panels") { panels() }
    }

    /** Marks this element as the panel of tab [anchor]. */
    fun Tag.tabPanel(
        anchor: String,
        shown: Boolean,
    ) {
        attributes["id"] = anchor
        attributes["role"] = "tabpanel"
        attributes["aria-labelledby"] = "tab-$anchor"
        if (!shown) attributes["hidden"] = "hidden"
    }
}
