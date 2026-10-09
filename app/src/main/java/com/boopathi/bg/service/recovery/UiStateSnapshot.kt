package com.boopathi.bg.service.recovery

/**
 * Lightweight, safe snapshot of visible UI state extracted from AccessibilityBridge.
 * Retains no references to raw AccessibilityNodeInfo objects to prevent memory leaks and unsafe operations.
 */
data class UiStateSnapshot(
    val packageName: String?,
    val visibleLabels: List<String>,
    val actionableLabels: List<String>,
    val editableLabels: List<String>,
    val scrollableLabels: List<String>,
    val capturedAtMillis: Long = System.currentTimeMillis(),
    val isAvailable: Boolean = true
) {
    companion object {
        fun unavailable(reason: String? = null): UiStateSnapshot = UiStateSnapshot(
            packageName = null,
            visibleLabels = emptyList(),
            actionableLabels = emptyList(),
            editableLabels = emptyList(),
            scrollableLabels = emptyList(),
            isAvailable = false
        )
    }

    fun hasVisibleLabel(query: String, normalized: Boolean = true): Boolean {
        return findMatchingLabels(visibleLabels, query, normalized).isNotEmpty()
    }

    fun hasActionableLabel(query: String, normalized: Boolean = true): Boolean {
        return findMatchingLabels(actionableLabels, query, normalized).isNotEmpty()
    }

    fun hasEditableLabel(query: String, normalized: Boolean = true): Boolean {
        return findMatchingLabels(editableLabels, query, normalized).isNotEmpty()
    }

    private fun findMatchingLabels(labels: List<String>, query: String, normalized: Boolean): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        if (!normalized) {
            return labels.filter { it.equals(q, ignoreCase = false) }
        }
        val cleanQ = q.lowercase().replace(Regex("[^a-z0-9]"), "")
        return labels.filter { label ->
            val cleanL = label.lowercase().replace(Regex("[^a-z0-9]"), "")
            label.contains(q, ignoreCase = true) || (cleanQ.isNotEmpty() && cleanL.contains(cleanQ))
        }
    }
}

