package com.boopathi.bg.service.agent

/**
 * Deterministic planner that converts natural language intents (English, Tamil, Tanglish)
 * into structured, verifiable AppPlan instances.
 */
class AppPlanner {

    fun createPlan(userSpeech: String): AppPlan? {
        val clean = userSpeech.trim().lowercase()
            .removeSuffix(".")
            .removeSuffix("?")
            .removeSuffix("!")
            .trim()

        if (clean.isEmpty()) return null

        // 1. COMPOUND: "Open <app> and inspect the screen" / "<app> open pannu screen-la enna irukku sollu"
        val compoundMatch = Regex("^(?:open|launch)\\s+([a-z0-9\\s]+?)\\s+and\\s+(?:inspect|read)\\s+(?:the\\s+)?screen$", RegexOption.IGNORE_CASE).matchEntire(clean)
        if (compoundMatch != null) {
            val app = compoundMatch.groupValues[1].trim()
            return AppPlan(
                goal = "Open $app and inspect screen",
                actions = listOf(
                    AppAction.OpenApp(app),
                    AppAction.WaitForWindow(app, timeoutMs = 6000L),
                    AppAction.InspectUi
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

    private fun isValidTarget(target: String): Boolean {
        val lower = target.lowercase()
        return target.isNotBlank() &&
                lower !in listOf("the screen", "screen", "something", "anything", "what", "who", "why", "how")
    }
}

