package com.boopathi.bg

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boopathi.bg.data.local.BGDatabase
import com.boopathi.bg.data.repository.RoomBGRepository
import com.boopathi.bg.service.agent.AgentExecutionPolicy
import com.boopathi.bg.service.agent.AppAction
import com.boopathi.bg.service.agent.AppActionResultStatus
import com.boopathi.bg.service.agent.AppAgent
import com.boopathi.bg.service.agent.AppVerifier
import com.boopathi.bg.service.agent.UiElementInfo
import com.boopathi.bg.service.messaging.MessageDraft
import com.boopathi.bg.service.messaging.MessagingPolicy
import com.boopathi.bg.service.messaging.WhatsAppMessagingAdapter
import com.boopathi.bg.service.notification.NotificationPrivacyPolicy
import com.boopathi.bg.service.security.MessageDispatchAuthorization
import com.boopathi.bg.service.tool.RiskLevel
import com.boopathi.bg.service.tool.Tool
import com.boopathi.bg.service.tool.ToolRegistry
import com.boopathi.bg.service.tool.ToolResult
import com.boopathi.bg.service.tool.ToolStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase12ReleaseReadinessTest {

    private lateinit var context: Context
    private lateinit var db: BGDatabase
    private lateinit var repository: RoomBGRepository
    private lateinit var bridge: FakeAccessibilityBridge
    private val adapter = WhatsAppMessagingAdapter()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, BGDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomBGRepository(db, Dispatchers.Unconfined)
        bridge = FakeAccessibilityBridge(currentPkg = "com.whatsapp")
        MessagingPolicy.SENDING_UNSUPPORTED = false
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun executeAuthorized(
        action: AppAction.SendMessage,
        agent: AppAgent
    ) = agent.executeAuthorizedSend(
        "test-task",
        action,
        MessageDispatchAuthorization.issue(
            "test-task",
            MessageDraft(action.expectedRecipient, action.expectedMessage, action.targetApp),
            System.currentTimeMillis()
        )
    )

    // 1. OpenApp without ToolRegistry returns unavailable
    @Test
    fun test1_openAppWithoutToolRegistryReturnsUnavailable() = runBlocking {
        val agent = AppAgent(bridge = bridge, toolRegistry = null)
        val result = agent.executeAction(AppAction.OpenApp("WhatsApp"))

        assertEquals(AppActionResultStatus.UNAVAILABLE, result.status)
        assertTrue(result.reason?.contains("Tool registry is not available") == true)
    }

    // 2. Failed app launch does not report success
    @Test
    fun test2_failedAppLaunchDoesNotReportSuccess() = runBlocking {
        val failingLaunchTool = object : Tool {
            override val name: String = "OPEN_APP"
            override val description: String = "Failing launch tool"
            override val riskLevel: RiskLevel = RiskLevel.LOW
            override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
                return ToolResult(
                    status = ToolStatus.FAILED,
                    message = "Application not installed on device.",
                    toolName = "OPEN_APP"
                )
            }
        }
        val registry = ToolRegistry().apply { register(failingLaunchTool) }
        val agent = AppAgent(bridge = bridge, toolRegistry = registry)

        val result = agent.executeAction(AppAction.OpenApp("NonExistentApp"))

        assertEquals(AppActionResultStatus.FAILED, result.status)
        assertTrue(result.reason?.contains("not installed") == true)
    }

    // 3. Tap with multiple actionable matches requests clarification
    @Test
    fun test3_tapWithMultipleActionableMatchesRequestsClarification() = runBlocking {
        bridge.mockElements = mutableListOf(
            UiElementInfo(text = "Save", isClickable = true, isEnabled = true),
            UiElementInfo(text = "Save", isClickable = true, isEnabled = true)
        )
        val agent = AppAgent(bridge = bridge)

        val result = agent.executeAction(AppAction.Tap("Save"))

        assertEquals(AppActionResultStatus.REQUIRES_CLARIFICATION, result.status)
        assertTrue(result.reason?.contains("clarify", ignoreCase = true) == true)
        assertTrue(bridge.executedActions.none { it.startsWith("click:") })
    }

    // 4. Tap with one unique actionable match works
    @Test
    fun test4_tapWithOneUniqueActionableMatchWorks() = runBlocking {
        bridge.mockElements = mutableListOf(
            UiElementInfo(text = "Settings", isClickable = true, isEnabled = true),
            UiElementInfo(text = "Settings", isClickable = false, isEnabled = false)
        )
        val agent = AppAgent(bridge = bridge)

        val result = agent.executeAction(AppAction.Tap("Settings"))

        assertEquals(AppActionResultStatus.SUCCESS, result.status)
        assertTrue(bridge.executedActions.contains("click:Settings"))
    }

    // 5. TypeText with multiple editable fields requests clarification
    @Test
    fun test5_typeTextWithMultipleEditableFieldsRequestsClarification() = runBlocking {
        bridge.mockElements = mutableListOf(
            UiElementInfo(text = "Search", viewId = "search_1", isEditable = true, isEnabled = true),
            UiElementInfo(text = "Search", viewId = "search_2", isEditable = true, isEnabled = true)
        )
        val agent = AppAgent(bridge = bridge)

        val result = agent.executeAction(AppAction.TypeText("Search", "Jetpack Compose"))

        assertEquals(AppActionResultStatus.REQUIRES_CLARIFICATION, result.status)
        assertTrue(result.reason?.contains("Multiple editable input fields match") == true)
        assertTrue(bridge.executedActions.none { it.startsWith("type:") })
    }

    // 6. TypeText with no editable target fails safely
    @Test
    fun test6_typeTextWithNoEditableTargetFailsSafely() = runBlocking {
        bridge.mockElements = mutableListOf(
            UiElementInfo(text = "Search History Header", isEditable = false, isEnabled = true)
        )
        val agent = AppAgent(bridge = bridge)

        val result = agent.executeAction(AppAction.TypeText("Search History Header", "Query"))

        assertEquals(AppActionResultStatus.FAILED, result.status)
        assertTrue(result.reason?.contains("not an editable text field") == true)
        assertTrue(bridge.executedActions.none { it.startsWith("type:") })
    }

    // 7. Blank foreground target fails immediately
    @Test
    fun test7_blankForegroundTargetFailsImmediately() = runBlocking {
        val verifier = AppVerifier(bridge)

        val blankResult = verifier.verifyWindowActive("")
        assertFalse(blankResult.isVerified)
        assertTrue(blankResult.details.contains("blank target app"))

        val spacesResult = verifier.verifyWindowActive("   ")
        assertFalse(spacesResult.isVerified)
        assertTrue(spacesResult.details.contains("blank target app"))

        val invalidTimeoutResult = verifier.verifyWindowActive("whatsapp", timeoutMs = 0L)
        assertFalse(invalidTimeoutResult.isVerified)
        assertTrue(invalidTimeoutResult.details.contains("Invalid timeout"))
    }

    // 8. Similar but incorrect package IDs do not pass exact package verification
    @Test
    fun test8_similarButIncorrectPackageIdsDoNotPassExactVerification() = runBlocking {
        val verifier = AppVerifier(bridge)

        // Phishing / mimic package name
        bridge.currentPkg = "com.evilwhatsapp.app"
        val failResult = verifier.verifyWindowActive("whatsapp", timeoutMs = 200L)
        assertFalse(failResult.isVerified)

        // Exact trusted package
        bridge.currentPkg = "com.whatsapp"
        val passResult = verifier.verifyWindowActive("whatsapp", timeoutMs = 200L)
        assertTrue(passResult.isVerified)
    }

    // 9. Foreground-package changes prevent a WhatsApp Send click
    @Test
    fun test9_foregroundPackageChangePreventsSendClick() = runBlocking {
        bridge.currentPkg = "com.whatsapp"
        bridge.mockElements = mutableListOf(
            UiElementInfo(text = "Karthi", bounds = android.graphics.Rect(0, 0, 1080, 200)),
            UiElementInfo(text = "Hello there", isEditable = true, isEnabled = true),
            UiElementInfo(contentDescription = "Send", isClickable = true, isEnabled = true)
        )

        val testBridge = object : FakeAccessibilityBridge(
            currentPkg = "com.whatsapp",
            mockElements = bridge.mockElements
        ) {
            private var calls = 0
            override suspend fun getVisibleElements(): List<UiElementInfo> {
                calls++
                // After finding send button (call 3), before send click, emulate foreground app switch
                if (calls >= 4) {
                    currentPkg = "com.android.launcher"
                }
                return super.getVisibleElements()
            }
        }

        val agent = AppAgent(bridge = testBridge, messagingAdapter = adapter)
        val action = AppAction.SendMessage(expectedRecipient = "Karthi", expectedMessage = "Hello there")
        val result = executeAuthorized(action, agent)

        assertEquals(AppActionResultStatus.FAILED, result.status)
        assertTrue(result.reason?.contains("Foreground app changed") == true)
        assertTrue(testBridge.executedActions.none { it.contains("Send") })
    }

    // 10. Ambiguous Send controls are rejected
    @Test
    fun test10_ambiguousSendControlsAreRejected() = runBlocking {
        bridge.currentPkg = "com.whatsapp"
        bridge.mockElements = mutableListOf(
            UiElementInfo(contentDescription = "Send", isClickable = true, isEnabled = true),
            UiElementInfo(contentDescription = "Send", isClickable = true, isEnabled = true)
        )

        val sendButton = adapter.findSendButton(bridge)
        assertNull("Ambiguous send buttons must be rejected by returning null", sendButton)
    }

    // 11. An old matching message does not satisfy post-send verification
    @Test
    fun test11_oldMatchingMessageDoesNotSatisfyPostSendVerification() = runBlocking {
        bridge.currentPkg = "com.whatsapp"
        // Message "Done" already exists in conversation history
        bridge.mockElements = mutableListOf(
            UiElementInfo(text = "Done", isEditable = false),
            UiElementInfo(text = "", isEditable = true, isEnabled = true)
        )

        val previousCount = adapter.exactMessageMatchCount(bridge, "Done")
        assertEquals(1, previousCount)

        // No new message bubble is added, only old bubble exists
        val verified = adapter.verifyMessageSent(
            bridge = bridge,
            expectedDraft = "Done",
            previousMatchCount = previousCount,
            timeoutMs = 400L
        )

        assertFalse("Old message bubble must not verify delivery without a count increase", verified)
    }

    // 12. Send timeout does not trigger a second click
    @Test
    fun test12_sendTimeoutDoesNotTriggerSecondClick() = runBlocking {
        bridge.currentPkg = "com.whatsapp"
        bridge.mockElements = mutableListOf(
            UiElementInfo(text = "Karthi", bounds = android.graphics.Rect(0, 0, 1080, 200)),
            UiElementInfo(text = "Meet me", isEditable = true, isEnabled = true),
            UiElementInfo(contentDescription = "Send", isClickable = true, isEnabled = true)
        )

        val timingOutAdapter = object : WhatsAppMessagingAdapter() {
            override suspend fun verifyMessageSent(
                bridge: com.boopathi.bg.service.agent.AccessibilityBridge,
                expectedDraft: String,
                previousMatchCount: Int,
                timeoutMs: Long
            ): Boolean = false
        }

        val agent = AppAgent(bridge = bridge, messagingAdapter = timingOutAdapter)
        val action = AppAction.SendMessage(expectedRecipient = "Karthi", expectedMessage = "Meet me")
        val result = executeAuthorized(action, agent)

        assertFalse(result.isSuccess)
        assertTrue(result.reason?.contains("No automatic retry") == true)

        val clickCount = bridge.executedActions.count { it.contains("Send") }
        assertEquals(1, clickCount)
    }

    // 13. Existing owner authorization, expiry, single-use, and sensitive-content tests still pass
    @Test
    fun test13_existingOwnerAuthAndSafetyPoliciesPreserved() {
        val sendAction = AppAction.SendMessage(expectedRecipient = "Karthi", expectedMessage = "Hello")
        val decision = AgentExecutionPolicy.evaluateActionRisk(sendAction)
        assertFalse(decision.isPermitted)
        assertEquals(AppActionResultStatus.REQUIRES_CONFIRMATION, decision.status)
        assertEquals(RiskLevel.MEDIUM, decision.level)

        // Sensitive content (OTP / PIN / Money transfer) blocked
        assertFalse(MessagingPolicy.isContentSafeToSend("My OTP is 998811"))
        assertFalse(MessagingPolicy.isContentSafeToSend("Send 500 rupees to John"))
        assertTrue(MessagingPolicy.isContentSafeToSend("Hello, how are you?"))
    }

    // 14. Notification privacy regression cases pass
    @Test
    fun test14_notificationPrivacyRegressionCases() {
        // Harmless notifications containing credential substrings are NOT sensitive
        assertFalse(NotificationPrivacyPolicy.isSensitive("", "I am going shopping today"))
        assertFalse(NotificationPrivacyPolicy.isSensitive("", "The shipping status was updated"))
        assertFalse(NotificationPrivacyPolicy.isSensitive("", "Order #123456 delivered"))

        // Authentic credential / sensitive notifications ARE detected
        assertTrue(NotificationPrivacyPolicy.isSensitive("", "Your PIN is required to login"))
        assertTrue(NotificationPrivacyPolicy.isSensitive("", "Your verification code is 482910"))
        assertTrue(NotificationPrivacyPolicy.isSensitive("Bank Alert", "Debited INR 500"))
    }
}

