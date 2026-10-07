package com.boopathi.bg.service.agent

import android.graphics.Rect

/**
 * Safe, immutable data class representing a visible UI element extracted from an accessibility node.
 * Prevents leaking raw AccessibilityNodeInfo objects to UI, AI providers, or persistence layers.
 */
data class UiElementInfo(
    val text: String = "",
    val contentDescription: String = "",
    val className: String = "",
    val viewId: String = "",
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isEnabled: Boolean = true,
    val bounds: Rect = Rect(),
    val depth: Int = 0,
    val packageName: String = ""
) {
    val displayLabel: String
        get() = when {
            text.isNotBlank() -> text.trim()
            contentDescription.isNotBlank() -> contentDescription.trim()
            viewId.isNotBlank() -> viewId.substringAfterLast(":id/").trim()
            else -> className.substringAfterLast(".").trim()
        }

    val isActionable: Boolean
        get() = isEnabled && (isClickable || isEditable || isScrollable)

    fun matchesQuery(query: String, normalized: Boolean = false): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return false

        // 1. Exact text match
        if (text.equals(q, ignoreCase = false)) return true

        // 2. Exact content description match
        if (contentDescription.equals(q, ignoreCase = false)) return true

        // 3. Exact view id match
        if (viewId.equals(q, ignoreCase = true) || viewId.substringAfterLast(":id/").equals(q, ignoreCase = true)) return true

        // 4. Case-insensitive text or description
        if (text.equals(q, ignoreCase = true)) return true
        if (contentDescription.equals(q, ignoreCase = true)) return true

        // 5. Normalized / partial match
        if (normalized) {
            val lowerQ = q.lowercase()
            if (text.lowercase().contains(lowerQ)) return true
            if (contentDescription.lowercase().contains(lowerQ)) return true

            // Clean punctuation (e.g., "wifi" vs "wi-fi")
            val cleanQ = lowerQ.replace(Regex("[^a-z0-9]"), "")
            val cleanText = text.lowercase().replace(Regex("[^a-z0-9]"), "")
            val cleanDesc = contentDescription.lowercase().replace(Regex("[^a-z0-9]"), "")

            if (cleanQ.isNotEmpty()) {
                if (cleanText.contains(cleanQ)) return true
                if (cleanDesc.contains(cleanQ)) return true
            }
        }

        return false
    }
}

