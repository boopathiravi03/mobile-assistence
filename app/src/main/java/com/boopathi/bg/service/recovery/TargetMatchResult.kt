package com.boopathi.bg.service.recovery

import com.boopathi.bg.service.agent.UiElementInfo

/**
 * Result of safely matching a query against visible UI elements.
 */
sealed class TargetMatchResult {
    data class SingleMatch(val element: UiElementInfo) : TargetMatchResult()
    data class MultipleMatches(val candidates: List<UiElementInfo>, val distinctLabels: List<String>) : TargetMatchResult()
    object NoMatch : TargetMatchResult()
}

/**
 * Safe target matching logic supporting exact match preference, unique normalized matching,
 * and unambiguous candidate detection.
 */
object SafeTargetMatcher {

    fun matchTarget(elements: List<UiElementInfo>, query: String): TargetMatchResult {
        val q = query.trim()
        if (q.isEmpty() || elements.isEmpty()) {
            return TargetMatchResult.NoMatch
        }

        // 1. Exact match on text, contentDescription, or viewId
        val exactMatches = elements.filter { el ->
            el.text.equals(q, ignoreCase = false) ||
            el.contentDescription.equals(q, ignoreCase = false) ||
            el.viewId.equals(q, ignoreCase = true) ||
            el.viewId.substringAfterLast(":id/").equals(q, ignoreCase = true)
        }

        if (exactMatches.size == 1) {
            return TargetMatchResult.SingleMatch(exactMatches.first())
        }
        if (exactMatches.size > 1) {
            // Check if they are actionable vs non-actionable
            val actionableExact = exactMatches.filter { it.isActionable }
            if (actionableExact.size == 1) {
                return TargetMatchResult.SingleMatch(actionableExact.first())
            }
            val distinctLabels = exactMatches.map { it.displayLabel }.distinct()
            return TargetMatchResult.MultipleMatches(exactMatches, distinctLabels)
        }

        // 2. Case-insensitive exact match
        val caseInsensitiveMatches = elements.filter { el ->
            el.text.equals(q, ignoreCase = true) ||
            el.contentDescription.equals(q, ignoreCase = true)
        }

        if (caseInsensitiveMatches.size == 1) {
            return TargetMatchResult.SingleMatch(caseInsensitiveMatches.first())
        }
        if (caseInsensitiveMatches.size > 1) {
            val actionable = caseInsensitiveMatches.filter { it.isActionable }
            if (actionable.size == 1) {
                return TargetMatchResult.SingleMatch(actionable.first())
            }
            val distinctLabels = caseInsensitiveMatches.map { it.displayLabel }.distinct()
            return TargetMatchResult.MultipleMatches(caseInsensitiveMatches, distinctLabels)
        }

        // 3. Normalized / partial substring matching
        val normalizedMatches = elements.filter { el ->
            el.matchesQuery(q, normalized = true)
        }

        if (normalizedMatches.size == 1) {
            return TargetMatchResult.SingleMatch(normalizedMatches.first())
        }
        if (normalizedMatches.size > 1) {
            val actionable = normalizedMatches.filter { it.isActionable }
            if (actionable.size == 1) {
                return TargetMatchResult.SingleMatch(actionable.first())
            }
            val distinctLabels = normalizedMatches.map { it.displayLabel }.distinct()
            return TargetMatchResult.MultipleMatches(normalizedMatches, distinctLabels)
        }

        return TargetMatchResult.NoMatch
    }
}

