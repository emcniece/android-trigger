package com.emcniece.trigger.engine

import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

/**
 * Replaces `{name}` placeholders with event variables.
 * `{name:json}` escapes the value for use inside a JSON string; `{name:url}` URL-encodes it.
 * Unknown placeholders and anything that isn't a bare identifier (e.g. JSON braces) are left untouched.
 */
object Templates {
    private val PLACEHOLDER = Regex("""\{([A-Za-z0-9_]+)(?::(json|url))?\}""")

    fun render(template: String, vars: Map<String, String>): String =
        PLACEHOLDER.replace(template) { m ->
            val value = vars[m.groupValues[1]] ?: return@replace m.value
            when (m.groupValues[2]) {
                "json" -> JsonPrimitive(value).toString().let { it.substring(1, it.length - 1) }
                "url" -> URLEncoder.encode(value, "UTF-8")
                else -> value
            }
        }
}
