package com.boopathi.bg.service.messaging

/**
 * Resolves recipient names from spoken user requests without accessing or harvesting
 * the entire Android contacts database. Handles English, Tamil, and Tanglish suffixes.
 */
class MessagingTargetResolver {

    /**
     * Normalizes a raw spoken name query by stripping common honorifics and dative suffixes.
     * Examples:
     * - "Karthi-ku" -> "Karthi"
     * - "Karthi ku" -> "Karthi"
     * - "Karthik-ku" -> "Karthik"
     * - "Karthi anna-ku" -> "Karthi"
     * - "send to Karthi" -> "Karthi"
     */
    fun cleanRecipientName(rawQuery: String): String {
        var clean = rawQuery.trim()
            .removePrefix("send to ")
            .removePrefix("to ")
            .trim()

        val suffixes = listOf(
            "-anna-ku", " anna-ku", "-anna ku", " anna ku",
            "-thambi-ku", " thambi-ku", "-thambi ku", " thambi ku",
            "-bro-ku", " bro-ku", "-bro ku", " bro ku",
            "-ku", " ku", "-kku", " kku"
        )

        for (suffix in suffixes) {
            if (clean.endsWith(suffix, ignoreCase = true)) {
                clean = clean.substring(0, clean.length - suffix.length).trim()
                break
            }
        }

        return clean.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    /**
     * Resolves target from query against known candidates (e.g. from recent conversation or UI elements).
     */
    fun resolveTarget(
        recipientQuery: String,
        availableContacts: List<String> = emptyList(),
        defaultApp: String = "WhatsApp"
    ): TargetResolutionResult {
        val normalized = cleanRecipientName(recipientQuery)
        if (normalized.isBlank()) {
            return TargetResolutionResult(
                status = TargetResolutionStatus.NOT_FOUND,
                reason = "Recipient name is empty."
            )
        }

        if (availableContacts.isEmpty()) {
            // When contacts are not pre-indexed, accept the normalized query for dynamic UI lookup
            return TargetResolutionResult(
                status = TargetResolutionStatus.FOUND,
                target = MessagingTarget(displayName = normalized, sourceApp = defaultApp)
            )
        }

        // Check for exact match
        val exactMatches = availableContacts.filter { it.equals(normalized, ignoreCase = true) }
        if (exactMatches.size == 1) {
            return TargetResolutionResult(
                status = TargetResolutionStatus.FOUND,
                target = MessagingTarget(displayName = exactMatches.first(), sourceApp = defaultApp)
            )
        }

        // Check for partial/prefix matches
        val partialMatches = availableContacts.filter {
            it.contains(normalized, ignoreCase = true) || normalized.contains(it, ignoreCase = true)
        }

        return when {
            partialMatches.size == 1 -> {
                TargetResolutionResult(
                    status = TargetResolutionStatus.FOUND,
                    target = MessagingTarget(displayName = partialMatches.first(), sourceApp = defaultApp)
                )
            }
            partialMatches.size > 1 -> {
                TargetResolutionResult(
                    status = TargetResolutionStatus.AMBIGUOUS,
                    candidateMatches = partialMatches,
                    reason = "Found multiple contacts matching '$normalized'."
                )
            }
            else -> {
                TargetResolutionResult(
                    status = TargetResolutionStatus.NOT_FOUND,
                    reason = "No contact found matching '$normalized'."
                )
            }
        }
    }
}

