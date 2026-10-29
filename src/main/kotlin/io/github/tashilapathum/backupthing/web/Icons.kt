// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.web

import kotlinx.html.FlowOrPhrasingContent
import kotlinx.html.span
import kotlinx.html.unsafe

/** Phosphor icons (MIT), vendored under `static/icons/`; inlined so they take the text colour. */
object Icons {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun svg(name: String): String =
        cache.getOrPut(name) {
            val text =
                requireNotNull(
                    Icons::class.java.getResource("/static/icons/$name.svg"),
                ) { "Unknown icon $name" }.readText()
            text.replaceFirst("<svg ", "<svg aria-hidden=\"true\" focusable=\"false\" ")
        }

    fun FlowOrPhrasingContent.icon(name: String) = span(classes = "icon") { unsafe { +svg(name) } }
}
