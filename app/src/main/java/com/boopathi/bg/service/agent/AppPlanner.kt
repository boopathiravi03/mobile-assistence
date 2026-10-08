package com.boopathi.bg.service.agent

/**
 * Deterministic planner that converts natural language intents (English, Tamil, Tanglish)
 * into structured, verifiable AppPlan instances.
 */
class AppPlanner {

    private fun formatAppTitle(name: String): String {
        return when (name.lowercase()) {
            "youtube" -> "YouTube"
            "whatsapp" -> "WhatsApp"
            "settings" -> "Settings"
            "camera" -> "Camera"
            "chrome" -> "Chrome"
            "maps" -> "Maps"
            "gmail" -> "Gmail"
            else -> name.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
    }

    private fun formatTargetTitle(target: String): String {
        return target.replaceFirstChar { it.uppercase() }
    }

    fun createPlan(userSpeech: String): AppPlan? {
        val clean = userSpeech.trim().lowercase()
            .removeSuffix(".")
            .removeSuffix("?")
            .removeSuffix("!")
            .trim()

        if (clean.isEmpty()) return null

        // 1. MULTI-STEP COMPOUND PATTERNS:
        // Pattern 1A: "Open <app> and tap <target>" / "Launch <app> and click <target>"
        // Tanglish: "<app> open panni <target> button-a tap pannu" / "<app> open panni <target> tap pannu"
        val openAndTapEnglish = Regex("^(?:open|launch)\\s+([a-z0-9\\s]+?)\\s+and\\s+(?:tap|click|press)(?:\\s+on)?\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(clean)
        if (openAndTapEnglish != null) {
            val app = openAndTapEnglish.groupValues[1].trim()
            val target = openAndTapEnglish.groupValues[2].trim().removeSuffix(" button").trim()
            if (isValidTarget(target)) {
                val appTitle = formatAppTitle(app)
                val targetTitle = formatTargetTitle(target)
                return AppPlan(
                    goal = "Open $appTitle and tap $targetTitle",
                    actions = listOf(
                        AppAction.OpenApp(appTitle),
                        AppAction.WaitForWindow(app, timeoutMs = 6000L),
                        AppAction.InspectUi,
                        AppAction.Tap(targetTitle)
                    )
                )
            }
        }

        val openAndTapTanglish = listOf(
            Regex("^(.+?)\\s+open\\s+panni\\s+(.+?)\\s+button-a\\s+tap\\s+pannu$", RegexOption.IGNORE_CASE),
            Regex("^(.+?)\\s+open\\s+panni\\s+(.+?)\\s+tap\\s+pannu$", RegexOption.IGNORE_CASE),
            Regex("^(.+?)\\s+open\\s+panni\\s+(.+?)\\s+click\\s+pannu$", RegexOption.IGNORE_CASE),
            Regex("^(.+?)\\s+open\\s+pannitu\\s+(.+?)\\s+button-a\\s+tap\\s+pannu$", RegexOption.IGNORE_CASE),
            Regex("^(.+?)\\s+open\\s+pannitu\\s+(.+?)\\s+tap\\s+pannu$", RegexOption.IGNORE_CASE)
        )
        for (pattern in openAndTapTanglish) {
            val match = pattern.matchEntire(clean)
            if (match != null) {
                val app = match.groupValues[1].trim()
                val target = match.groupValues[2].trim().removeSuffix(" button").trim()
                if (isValidTarget(target)) {
                    val appTitle = formatAppTitle(app)
                    val targetTitle = formatTargetTitle(target)
                    return AppPlan(
                        goal = "Open $appTitle and tap $targetTitle",
                        actions = listOf(
                            AppAction.OpenApp(appTitle),
                            AppAction.WaitForWindow(app, timeoutMs = 6000L),
                            AppAction.InspectUi,
                            AppAction.Tap(targetTitle)
                        )
                    )
                }
            }
        }

        // Pattern 1B: "Open <app> and inspect the screen" / "<app> open panni screen inspect pannu"
        val compoundInspectEnglish = Regex("^(?:open|launch)\\s+([a-z0-9\\s]+?)\\s+and\\s+(?:inspect|read)\\s+(?:the\\s+)?screen$", RegexOption.IGNORE_CASE).matchEntire(clean)
        if (compoundInspectEnglish != null) {
            val app = compoundInspectEnglish.groupValues[1].trim()
            val appTitle = formatAppTitle(app)
            return AppPlan(
                goal = "Open $appTitle and inspect screen",
                actions = listOf(
                    AppAction.OpenApp(appTitle),
                    AppAction.WaitForWindow(app, timeoutMs = 6000L),
                    AppAction.InspectUi
                )
            )
        }

        val compoundInspectTanglish = listOf(
            Regex("^(.+?)\\s+open\\s+panni\\s+(?:the\\s+)?screen(?:-a)?\\s+(?:inspect|padikavum|sollu).*$", RegexOption.IGNORE_CASE),
            Regex("^(.+?)\\s+open\\s+panni\\s+screen-la\\s+enna\\s+irukku\\s+sollu$", RegexOption.IGNORE_CASE),
            Regex("^(.+?)\\s+open\\s+pannitu\\s+screen\\s+inspect\\s+pannu$", RegexOption.IGNORE_CASE)
        )
        for (pattern in compoundInspectTanglish) {
            val match = pattern.matchEntire(clean)
            if (match != null) {
                val app = match.groupValues[1].trim()
                val appTitle = formatAppTitle(app)
                return AppPlan(
                    goal = "Open $appTitle and inspect screen",
                    actions = listOf(
                        AppAction.OpenApp(appTitle),
                        AppAction.WaitForWindow(app, timeoutMs = 6000L),
                        AppAction.InspectUi
                    )
                )
            }
        }

        // Pattern 1C: "Go to Settings and open <option>" / "Settings open panni <option> open pannu"
        val settingsSubOption = Regex("^(?:go to |open )?settings\\s+and\\s+open\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(clean)
        if (settingsSubOption != null) {
            val option = settingsSubOption.groupValues[1].trim()
            val optionTitle = formatTargetTitle(option)
            return AppPlan(
                goal = "Open Settings and tap $optionTitle",
                actions = listOf(
                    AppAction.OpenApp("Settings"),
                    AppAction.WaitForWindow("settings", timeoutMs = 6000L),
                    AppAction.InspectUi,
                    AppAction.Tap(optionTitle)
                )
            )
        }

        val settingsSubOptionTanglish = Regex("^settings\\s+open\\s+panni\\s+(.+?)\\s+open\\s+pannu$", RegexOption.IGNORE_CASE).matchEntire(clean)
        if (settingsSubOptionTanglish != null) {
            val option = settingsSubOptionTanglish.groupValues[1].trim()
            val optionTitle = formatTargetTitle(option)
            return AppPlan(
                goal = "Open Settings and tap $optionTitle",
                actions = listOf(
                    AppAction.OpenApp("Settings"),
                    AppAction.WaitForWindow("settings", timeoutMs = 6000L),
                    AppAction.InspectUi,
                    AppAction.Tap(optionTitle)
                )
            )
        }

        // Pattern 1D: "Open <app> and scroll down" / "<app> open panni keela scroll pannu"
        val openAndScrollEnglish = Regex("^(?:open|launch)\\s+([a-z0-9\\s]+?)\\s+and\\s+scroll\\s+(down|up)$", RegexOption.IGNORE_CASE).matchEntire(clean)
        if (openAndScrollEnglish != null) {
            val app = openAndScrollEnglish.groupValues[1].trim()
            val appTitle = formatAppTitle(app)
            val direction = if (openAndScrollEnglish.groupValues[2].equals("up", ignoreCase = true)) ScrollDirection.UP else ScrollDirection.DOWN
            return AppPlan(
                goal = "Open $appTitle and scroll ${direction.name.lowercase()}",
                actions = listOf(
                    AppAction.OpenApp(appTitle),
                    AppAction.WaitForWindow(app, timeoutMs = 6000L),
                    AppAction.Scroll(direction)
                )
            )
        }

        val openAndScrollTanglish = Regex("^(.+?)\\s+open\\s+panni\\s+(keela|mela)\\s+scroll\\s+pannu$", RegexOption.IGNORE_CASE).matchEntire(clean)
        if (openAndScrollTanglish != null) {
            val app = openAndScrollTanglish.groupValues[1].trim()
            val appTitle = formatAppTitle(app)
            val direction = if (openAndScrollTanglish.groupValues[2].equals("mela", ignoreCase = true)) ScrollDirection.UP else ScrollDirection.DOWN
            return AppPlan(
                goal = "Open $appTitle and scroll ${direction.name.lowercase()}",
                actions = listOf(
                    AppAction.OpenApp(appTitle),
                    AppAction.WaitForWindow(app, timeoutMs = 6000L),
                    AppAction.Scroll(direction)
                )
            )
        }

        // 2. INSPECT UI COMMANDS
        // English: "inspect this screen", "inspect screen", "read visible screen text", "what is on the screen"
        // Tanglish: "screen-la enna irukku sollu", "screen-a inspect pannu", "screen padikavum", "screen enna irukku"
        if (clean in listOf(
                "inspect this screen", "inspect screen", "read visible screen text",
                "read the screen", "what is on my screen", "what is on the screen",
                "describe screen", "show screen elements",
                "screen-la enna irukku sollu", "screen la enna irukku sollu",
                "screen-la enna irukku", "screen la enna irukku",
                "screen-a inspect pannu", "screen inspect pannu", "screen padikavum"
            ) || clean.contains("screen-la enna irukku") || clean.contains("screen la enna irukku")
            || clean.contains("inspect this screen") || clean.contains("inspect the screen")
        ) {
            return AppPlan(
                goal = "Inspect visible UI elements",
                actions = listOf(AppAction.InspectUi)
            )
        }

        // 3. SCROLL COMMANDS
        // Scroll down
        if (clean in listOf(
                "scroll down", "scroll downward", "scroll page down",
                "keela scroll pannu", "keela scroll", "kizhe scroll pannu", "down scroll pannu"
            ) || clean.contains("keela scroll") || clean.contains("scroll down")
        ) {
            return AppPlan(
                goal = "Scroll down",
                actions = listOf(AppAction.Scroll(ScrollDirection.DOWN))
            )
        }

        // Scroll up
        if (clean in listOf(
                "scroll up", "scroll upward", "scroll page up",
                "mela scroll pannu", "mela scroll", "mele scroll pannu", "up scroll pannu"
            ) || clean.contains("mela scroll") || clean.contains("scroll up")
        ) {
            return AppPlan(
                goal = "Scroll up",
                actions = listOf(AppAction.Scroll(ScrollDirection.UP))
            )
        }

        // 4. BACK NAVIGATION
        // English: "go back", "back", "navigate back"
        // Tanglish: "back po", "back-ku po", "pinnadi po", "pinnadi ponga"
        if (clean in listOf(
                "go back", "navigate back", "press back",
                "back po", "back-ku po", "back ku po", "pinnadi po", "pinnadi ponga"
            )
        ) {
            return AppPlan(
                goal = "Navigate Back",
                actions = listOf(AppAction.Back)
            )
        }

        // 5. HOME NAVIGATION
        // English: "go home", "home screen"
        // Tanglish: "home-ku po", "home po"
        if (clean in listOf(
                "go to home screen", "open home screen", "take me home",
                "home-ku po", "home ku po", "home po"
            )
        ) {
            return AppPlan(
                goal = "Navigate Home",
                actions = listOf(AppAction.Home)
            )
        }

        // 6. TAP / CLICK COMMANDS
        // English: "tap <target>", "click <target>", "press <target>", "tap on <target>", "click on <target>"
        // Tanglish: "<target> button-a tap pannu", "<target>-a tap pannu", "<target> tap pannu", "<target> click pannu"
        val tapTarget = extractTapTarget(userSpeech)
        if (tapTarget != null) {
            return AppPlan(
                goal = "Tap on $tapTarget",
                actions = listOf(AppAction.Tap(targetQuery = tapTarget))
            )
        }

        // 7. TYPE TEXT COMMANDS
        // "type <text> into <field>" / "enter <text> in <field>"
        val typePlan = extractTypePlan(userSpeech)
        if (typePlan != null) {
            return typePlan
        }

        return null
    }

    private fun extractTapTarget(rawText: String): String? {
        val trimmed = rawText.trim()
            .removeSuffix(".")
            .removeSuffix("?")
            .removeSuffix("!")
            .trim()

        // Tanglish: "<target> button-a tap pannu", "<target>-a tap pannu", "<target> tap pannu"
        val tanglishPatterns = listOf(
            Regex("^(.+?)\\s+button-a\\s+tap\\s+pannu$", RegexOption.IGNORE_CASE),
            Regex("^(.+?)\\s+button\\s+tap\\s+pannu$", RegexOption.IGNORE_CASE),
            Regex("^(.+?)-a\\s+tap\\s+pannu$", RegexOption.IGNORE_CASE),
            Regex("^(.+?)\\s+tap\\s+pannu$", RegexOption.IGNORE_CASE),
            Regex("^(.+?)\\s+button-a\\s+click\\s+pannu$", RegexOption.IGNORE_CASE),
            Regex("^(.+?)\\s+click\\s+pannu$", RegexOption.IGNORE_CASE)
        )

        for (pattern in tanglishPatterns) {
            val match = pattern.matchEntire(trimmed)
            if (match != null) {
                val target = match.groupValues[1].trim()
                if (isValidTarget(target)) return target
            }
        }

        // English: "tap <target>", "tap on <target>", "click <target>", "click on <target>", "press <target>"
        val englishPrefixes = listOf("tap on ", "tap ", "click on ", "click ", "press ")
        for (prefix in englishPrefixes) {
            if (trimmed.startsWith(prefix, ignoreCase = true)) {
                val target = trimmed.substring(prefix.length).trim()
                val cleanTarget = target.removeSuffix(" button").trim()
                if (isValidTarget(cleanTarget)) return cleanTarget
            }
        }

        return null
    }

    private fun extractTypePlan(rawText: String): AppPlan? {
        val trimmed = rawText.trim()
            .removeSuffix(".")
            .removeSuffix("?")
            .removeSuffix("!")
            .trim()

        val typeRegex = Regex("^(?:type|enter|write)\\s+\"?(.*?)\"?\\s+(?:into|in)\\s+(.+)$", RegexOption.IGNORE_CASE)
        val match = typeRegex.matchEntire(trimmed)
        if (match != null) {
            val textToType = match.groupValues[1].trim()
            val field = match.groupValues[2].trim()
            if (textToType.isNotEmpty() && field.isNotEmpty()) {
                return AppPlan(
                    goal = "Type '$textToType' into $field",
                    actions = listOf(AppAction.TypeText(targetQuery = field, textToType = textToType))
                )
            }
        }

        return null
    }

    fun createTask(userSpeech: String): AgentTask? {
        val plan = createPlan(userSpeech) ?: return null
        val steps = plan.actions.mapIndexed { idx, action ->
            val description = when (action) {
                is AppAction.OpenApp -> "Open ${action.appName}"
                is AppAction.WaitForWindow -> "Wait for ${action.expectedPackage} window"
                is AppAction.InspectUi -> "Inspect visible screen"
                is AppAction.Tap -> "Tap '${action.targetQuery}'"
                is AppAction.TypeText -> "Type '${action.textToType}' into '${action.targetQuery}'"
                is AppAction.Scroll -> "Scroll ${action.direction.name.lowercase()}"
                is AppAction.Back -> "Navigate Back"
                is AppAction.Home -> "Navigate Home"
            }
            AgentStep(
                action = action,
                description = description
            )
        }.toMutableList()

        return AgentTask(
            originalCommand = userSpeech,
            normalizedGoal = plan.goal,
            steps = steps
        )
    }

    private fun isValidTarget(target: String): Boolean {
        val lower = target.lowercase()
        return target.isNotBlank() &&
                lower !in listOf("the screen", "screen", "something", "anything", "what", "who", "why", "how")
    }
}

