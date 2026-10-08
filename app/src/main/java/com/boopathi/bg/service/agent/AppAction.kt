package com.boopathi.bg.service.agent

enum class ScrollDirection {
    UP,
    DOWN,
    FORWARD,
    BACKWARD
}

sealed class AppAction {
    abstract val type: AppActionType
    abstract val targetDescription: String

    data class OpenApp(val appName: String) : AppAction() {
        override val type: AppActionType = AppActionType.OPEN_APP
        override val targetDescription: String = "App: $appName"
    }

    data class WaitForWindow(
        val expectedPackage: String,
        val timeoutMs: Long = 6000L
    ) : AppAction() {
        override val type: AppActionType = AppActionType.WAIT_FOR_WINDOW
        override val targetDescription: String = "Window: $expectedPackage (timeout ${timeoutMs}ms)"
    }

    object InspectUi : AppAction() {
        override val type: AppActionType = AppActionType.INSPECT_UI
        override val targetDescription: String = "Current Screen UI"
    }

    data class Tap(
        val targetQuery: String,
        val isViewId: Boolean = false
    ) : AppAction() {
        override val type: AppActionType = AppActionType.TAP
        override val targetDescription: String = "Element: $targetQuery"
    }

    data class TypeText(
        val targetQuery: String,
        val textToType: String
    ) : AppAction() {
        override val type: AppActionType = AppActionType.TYPE_TEXT
        override val targetDescription: String = "Editable element: $targetQuery"
    }

    data class Scroll(
        val direction: ScrollDirection = ScrollDirection.DOWN
    ) : AppAction() {
        override val type: AppActionType = AppActionType.SCROLL
        override val targetDescription: String = "Scroll direction: $direction"
    }

    object Back : AppAction() {
        override val type: AppActionType = AppActionType.BACK
        override val targetDescription: String = "System Back"
    }

    object Home : AppAction() {
        override val type: AppActionType = AppActionType.HOME
        override val targetDescription: String = "System Home"
    }

    data class SendMessage(
        val expectedRecipient: String,
        val expectedMessage: String,
        val targetApp: String = "WhatsApp"
    ) : AppAction() {
        override val type: AppActionType = AppActionType.SEND_MESSAGE
        override val targetDescription: String = "Send message to $expectedRecipient via $targetApp"
    }
}

