package com.boopathi.bg.service.tool

data class ToolIntent(
    val toolName: String,
    val parameters: Map<String, Any?> = emptyMap(),
    val rawQuery: String
)

object ToolIntentDetector {

    fun detectIntent(userSpeech: String): ToolIntent? {
        val clean = userSpeech.trim().lowercase()
            .removeSuffix(".")
            .removeSuffix("?")
            .removeSuffix("!")
            .trim()

        if (clean.isEmpty()) return null

        // 1. FLASHLIGHT COMMANDS
        // Turn on
        if (clean in listOf(
                "turn on flashlight", "turn on the flashlight", "turn on torch", "turn on the torch",
                "switch on flashlight", "switch on the flashlight", "switch on torch", "switch on the torch",
                "flashlight on", "torch on", "flashlight on pannu", "flashlight on pannunga",
                "torch on pannu", "torch on pannunga", "flashlight podu", "torch podu"
            ) || clean.startsWith("turn on flashlight") || clean.startsWith("turn on torch")
            || clean.contains("flashlight on pannu") || clean.contains("torch on pannu")
        ) {
            return ToolIntent(
                toolName = FlashlightTool.NAME,
                parameters = mapOf("enable" to true),
                rawQuery = userSpeech
            )
        }

        // Turn off
        if (clean in listOf(
                "turn off flashlight", "turn off the flashlight", "turn off torch", "turn off the torch",
                "switch off flashlight", "switch off the flashlight", "switch off torch", "switch off the torch",
                "flashlight off", "torch off", "flashlight off pannu", "flashlight off pannunga",
                "torch off pannu", "torch off pannunga", "flashlight amathu", "torch amathu"
            ) || clean.startsWith("turn off flashlight") || clean.startsWith("turn off torch")
            || clean.contains("flashlight off pannu") || clean.contains("torch off pannu")
        ) {
            return ToolIntent(
                toolName = FlashlightTool.NAME,
                parameters = mapOf("enable" to false),
                rawQuery = userSpeech
            )
        }

        // 2. VOLUME COMMANDS
        // Volume percent e.g. "set volume to 50 percent" / "set media volume to 50 percent"
        val percentRegex = Regex("""(?:set\s+)?(?:media\s+)?volume\s+(?:to\s+)?(\d{1,3})\s*(?:percent|%)?""")
        val percentMatch = percentRegex.find(clean)
        if (percentMatch != null) {
            val level = percentMatch.groupValues[1].toIntOrNull()
            if (level != null) {
                return ToolIntent(
                    toolName = VolumeTool.NAME,
                    parameters = mapOf("percent" to level),
                    rawQuery = userSpeech
                )
            }
        }

        // Volume Increase
        if (clean in listOf(
                "increase volume", "volume increase", "volume up", "turn up volume", "raise volume",
                "volume increase pannu", "volume increase pannunga", "volume ethu", "volume athigari",
                "volume up pannu", "volume up pannunga"
            ) || clean.contains("volume increase") || clean.contains("increase volume")
            || clean.contains("volume up") || clean.contains("volume ethu")
        ) {
            return ToolIntent(
                toolName = VolumeTool.NAME,
                parameters = mapOf("direction" to "increase"),
                rawQuery = userSpeech
            )
        }

        // Volume Decrease
        if (clean in listOf(
                "decrease volume", "volume decrease", "volume down", "turn down volume", "lower volume", "reduce volume",
                "volume decrease pannu", "volume decrease pannunga", "volume kora", "volume kura",
                "volume down pannu", "volume down pannunga"
            ) || clean.contains("volume decrease") || clean.contains("decrease volume")
            || clean.contains("volume down") || clean.contains("volume kora") || clean.contains("volume kura")
        ) {
            return ToolIntent(
                toolName = VolumeTool.NAME,
                parameters = mapOf("direction" to "decrease"),
                rawQuery = userSpeech
            )
        }

        // 3. SETTINGS COMMANDS (Sub-settings checked before generic app opening)
        if (clean.contains("wifi") || clean.contains("wi-fi")) {
            if (clean.contains("settings") || clean.contains("open") || clean.contains("pannu")) {
                return ToolIntent(
                    toolName = OpenSettingsTool.NAME,
                    parameters = mapOf("target" to "wifi"),
                    rawQuery = userSpeech
                )
            }
        }

        if (clean.contains("bluetooth")) {
            if (clean.contains("settings") || clean.contains("open") || clean.contains("pannu")) {
                return ToolIntent(
                    toolName = OpenSettingsTool.NAME,
                    parameters = mapOf("target" to "bluetooth"),
                    rawQuery = userSpeech
                )
            }
        }

        if (clean.contains("display settings") || clean.contains("screen settings")) {
            return ToolIntent(
                toolName = OpenSettingsTool.NAME,
                parameters = mapOf("target" to "display"),
                rawQuery = userSpeech
            )
        }

        if (clean in listOf(
                "open settings", "settings open", "settings open pannu", "settings open pannunga",
                "go to settings", "launch settings"
            ) || clean == "settings"
        ) {
            return ToolIntent(
                toolName = OpenSettingsTool.NAME,
                parameters = mapOf("target" to "general"),
                rawQuery = userSpeech
            )
        }

        // 4. GO HOME COMMANDS
        if (clean in listOf(
                "go home", "go to home", "go to home screen", "open home screen", "home screen",
                "home-ku po", "home ku po", "home po", "home pogavum", "take me home"
            ) || clean.endsWith("home-ku po") || clean.endsWith("home ku po")
        ) {
            return ToolIntent(
                toolName = GoHomeTool.NAME,
                parameters = emptyMap(),
                rawQuery = userSpeech
            )
        }

        // 5. OPEN APP COMMANDS
        // English: "open <app>", "launch <app>", "start <app>"
        // Tanglish: "<app> open pannu", "<app> open pannunga", "<app> thora", "<app> open panu"
        val rawTrimmed = userSpeech.trim()
            .removeSuffix(".")
            .removeSuffix("?")
            .removeSuffix("!")
            .trim()
        val appName = extractAppName(rawTrimmed)
        if (appName != null) {
            return ToolIntent(
                toolName = OpenAppTool.NAME,
                parameters = mapOf("appName" to appName),
                rawQuery = userSpeech
            )
        }

        return null
    }

    private fun extractAppName(query: String): String? {
        val prefixes = listOf("open ", "launch ", "start ")
        for (prefix in prefixes) {
            if (query.startsWith(prefix, ignoreCase = true)) {
                val app = query.substring(prefix.length).trim()
                if (app.isNotEmpty() && !isDisallowedAppQuery(app)) {
                    return app
                }
            }
        }

        val suffixes = listOf(
            " open pannu", " open pannunga", " open panu", " open panunga",
            " open", " thora", " thoranga"
        )
        for (suffix in suffixes) {
            if (query.endsWith(suffix, ignoreCase = true)) {
                val app = query.substring(0, query.length - suffix.length).trim()
                if (app.isNotEmpty() && !isDisallowedAppQuery(app)) {
                    return app
                }
            }
        }

        return null
    }

    private fun isDisallowedAppQuery(app: String): Boolean {
        // Disallow phrases that are conversation queries, memory queries, or questions
        val lower = app.lowercase().trim()
        return lower in listOf(
            "settings", "wifi settings", "bluetooth settings", "display settings",
            "the door", "window", "gate", "mind", "mouth"
        ) || lower.startsWith("what ") || lower.startsWith("who ") || lower.startsWith("how ")
    }
}

