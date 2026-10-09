package com.boopathi.bg

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boopathi.bg.data.local.BGDatabase
import com.boopathi.bg.data.repository.RoomBGRepository
import com.boopathi.bg.service.agent.AccessibilityBridge
import com.boopathi.bg.service.agent.AgentExecutionPolicy
import com.boopathi.bg.service.agent.AppAction
import com.boopathi.bg.service.agent.AppActionResultStatus
import com.boopathi.bg.service.agent.AppActionType
import com.boopathi.bg.service.agent.AppAgent
import com.boopathi.bg.service.agent.AppAgentState
import com.boopathi.bg.service.agent.AppPlan
import com.boopathi.bg.service.agent.AppPlanner
import com.boopathi.bg.service.agent.AppVerifier
import com.boopathi.bg.service.agent.ScrollDirection
import com.boopathi.bg.service.agent.UiElementInfo
import com.boopathi.bg.service.command.CommandProcessor
import com.boopathi.bg.service.memory.RoomMemoryManager
import com.boopathi.bg.service.tool.ToolRegistry
import kotlinx.coroutines.CoroutineScope
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

/**
 * Fake implementation of AccessibilityBridge for fast, deterministic unit and JVM testing.
 */
open class FakeAccessibilityBridge(
    var serviceEnabled: Boolean = true,
    var connected: Boolean = true,
    var currentPkg: String = "com.boopathi.bg",
    var mockElements: MutableList<UiElementInfo> = mutableListOf()
) : AccessibilityBridge {

    val executedActions = mutableListOf<String>()

    override fun isServiceEnabled(): Boolean = serviceEnabled
    override fun isConnected(): Boolean = connected
    override fun getCurrentPackage(): String = currentPkg

    open override suspend fun getVisibleElements(): List<UiElementInfo> = mockElements.toList()

    open override suspend fun findByText(query: String, normalized: Boolean): List<UiElementInfo> {
        return mockElements.filter { it.matchesQuery(query, normalized) }
    }

    open override suspend fun findByContentDescription(query: String, normalized: Boolean): List<UiElementInfo> {
        return mockElements.filter { it.contentDescription.contains(query, ignoreCase = true) }
    }

    open override suspend fun findByViewId(viewId: String): List<UiElementInfo> {
        return mockElements.filter { it.viewId.contains(viewId, ignoreCase = true) }
    }

    open override suspend fun click(element: UiElementInfo): Boolean {
        executedActions.add("click:${element.displayLabel}")
        return element.isClickable && element.isEnabled
    }

    open override suspend fun typeText(element: UiElementInfo, text: String): Boolean {
        executedActions.add("type:${element.displayLabel}:$text")
        return element.isEditable && element.isEnabled
    }

    open override suspend fun scroll(direction: ScrollDirection): Boolean {
        executedActions.add("scroll:${direction.name}")
        return true
    }

    open override suspend fun pressBack(): Boolean {
        executedActions.add("back")
        return true
    }

    open override suspend fun pressHome(): Boolean {
        executedActions.add("home")
        return true
    }
}

@RunWith(AndroidJUnit4::class)
class Phase4AgentTest {

    private lateinit var context: Context
    private lateinit var db: BGDatabase
    private lateinit var repository: RoomBGRepository
    private lateinit var memoryManager: RoomMemoryManager
    private lateinit var bridge: FakeAccessibilityBridge
    private lateinit var appAgent: AppAgent
    private lateinit var processor: CommandProcessor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, BGDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomBGRepository(db, Dispatchers.Unconfined)
        memoryManager = RoomMemoryManager(repository, CoroutineScope(Dispatchers.Unconfined))

        bridge = FakeAccessibilityBridge()
        appAgent = AppAgent(bridge = bridge)
        processor = CommandProcessor(
            memoryManager = memoryManager,
            appAgent = appAgent
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // 1. Immutable UiElementInfo Creation & Property Extraction
    @Test
    fun testUiElementInfoCreation() {
        val element = UiElementInfo(
            text = "Search Settings",
            contentDescription = "Search bar",
            viewId = "com.android.settings:id/search_action_bar",
            className = "android.widget.EditText",
            packageName = "com.android.settings",
            isClickable = true,
            isEditable = true,
            isEnabled = true,
            bounds = android.graphics.Rect(20, 50, 300, 120)
        )

        assertEquals("Search Settings", element.displayLabel)
        assertEquals(280, element.bounds.width())
        assertEquals(70, element.bounds.height())
        assertTrue(element.matchesQuery("search", normalized = true))
        assertTrue(element.matchesQuery("Search Settings", normalized = false))
    }

    // 2. Safe Normalization & Fuzzy Query Matching
    @Test
    fun testUiElementQueryMatching() {
        val element = UiElementInfo(
            text = "Wi-Fi & Network",
            contentDescription = "Network preferences",
            viewId = "com.android.settings:id/network_settings",
            isClickable = true
        )

        assertTrue(element.matchesQuery("wifi", normalized = true))
        assertTrue(element.matchesQuery("network", normalized = true))
        assertTrue(element.matchesQuery("Network Preferences", normalized = true))
        assertFalse(element.matchesQuery("Bluetooth", normalized = true))
    }

    // 3. InspectUi Action Populates Elements & Verifies Successfully
    @Test
    fun testInspectUiActionSuccess() = runBlocking {
        bridge.mockElements = mutableListOf(
            UiElementInfo(text = "Settings", isClickable = false),
            UiElementInfo(text = "Network & internet", isClickable = true),
            UiElementInfo(text = "Connected devices", isClickable = true)
        )

        val action = AppAction.InspectUi
        val result = appAgent.executeAction(action)

        assertEquals(AppActionResultStatus.SUCCESS, result.status)
        assertEquals(3, appAgent.visibleElements.value.size)
        assertTrue(result.verificationDetails?.contains("3") == true)
        assertTrue(result.verificationDetails?.contains("inspected", ignoreCase = true) == true)
    }

    // 4. Tap Action on Visible Clickable Element
    @Test
    fun testTapActionSuccess() = runBlocking {
        bridge.mockElements = mutableListOf(
            UiElementInfo(text = "Battery", isClickable = true, isEnabled = true)
        )

        val action = AppAction.Tap("Battery")
        val result = appAgent.executeAction(action)

        assertEquals(AppActionResultStatus.SUCCESS, result.status)
        assertTrue(bridge.executedActions.contains("click:Battery"))
    }

    // 5. Missing Element Returns NOT_FOUND Without Crashing
    @Test
    fun testMissingElementReturnsNotFound() = runBlocking {
        bridge.mockElements = mutableListOf(
            UiElementInfo(text = "Display", isClickable = true)
        )

        val action = AppAction.Tap("NonExistentButton")
        val result = appAgent.executeAction(action)

        assertEquals(AppActionResultStatus.NOT_FOUND, result.status)
        assertTrue(result.reason?.contains("not found") == true)
    }

    // 6. Disabled Element Rejection
    @Test
    fun testDisabledElementRejection() = runBlocking {
        bridge.mockElements = mutableListOf(
            UiElementInfo(text = "Next Page", isClickable = true, isEnabled = false)
        )

        val action = AppAction.Tap("Next Page")
        val result = appAgent.executeAction(action)

        assertEquals(AppActionResultStatus.FAILED, result.status)
        assertTrue(result.reason?.contains("disabled") == true)
    }

    // 7. Non-Editable Target Text Entry Rejection
    @Test
    fun testNonEditableTargetTextEntryRejection() = runBlocking {
        bridge.mockElements = mutableListOf(
            UiElementInfo(text = "Status Header", isClickable = false, isEditable = false)
        )

        val action = AppAction.TypeText(targetQuery = "Status Header", textToType = "Hello")
        val result = appAgent.executeAction(action)

        assertEquals(AppActionResultStatus.FAILED, result.status)
        assertTrue(result.reason?.contains("not an editable") == true)
    }

    // 8. TypeText Action Success on Editable Field
    @Test
    fun testTypeTextSuccess() = runBlocking {
        bridge.mockElements = mutableListOf(
            UiElementInfo(text = "Search", viewId = "search_box", isEditable = true, isEnabled = true)
        )

        val action = AppAction.TypeText(targetQuery = "Search", textToType = "Airplane Mode")
        val result = appAgent.executeAction(action)

        assertEquals(AppActionResultStatus.SUCCESS, result.status)
        assertTrue(bridge.executedActions.contains("type:Search:Airplane Mode"))
    }

    // 9. Scroll Actions (Down and Up)
    @Test
    fun testScrollDownAndUp() = runBlocking {
        val downRes = appAgent.executeAction(AppAction.Scroll(ScrollDirection.DOWN))
        assertEquals(AppActionResultStatus.SUCCESS, downRes.status)
        assertTrue(bridge.executedActions.contains("scroll:DOWN"))

        val upRes = appAgent.executeAction(AppAction.Scroll(ScrollDirection.UP))
        assertEquals(AppActionResultStatus.SUCCESS, upRes.status)
        assertTrue(bridge.executedActions.contains("scroll:UP"))
    }

    // 10. Back & Home Global Navigation Actions
    @Test
    fun testBackAndHomeNavigation() = runBlocking {
        val backRes = appAgent.executeAction(AppAction.Back)
        assertEquals(AppActionResultStatus.SUCCESS, backRes.status)
        assertTrue(bridge.executedActions.contains("back"))

        val homeRes = appAgent.executeAction(AppAction.Home)
        assertEquals(AppActionResultStatus.SUCCESS, homeRes.status)
        assertTrue(bridge.executedActions.contains("home"))
    }

    // 11. Safety Policy: Sensitive Targets Blocked (HIGH Risk)
    @Test
    fun testSafetyPolicyBlocksSensitiveTargets() = runBlocking {
        val sensitiveTap = AppAction.Tap("Password")
        val decision = AgentExecutionPolicy.evaluateActionRisk(sensitiveTap)
        assertFalse(decision.isPermitted)
        assertEquals(AppActionResultStatus.BLOCKED, decision.status)

        val executedRes = appAgent.executeAction(sensitiveTap)
        assertEquals(AppActionResultStatus.BLOCKED, executedRes.status)
        assertEquals(AppAgentState.BLOCKED, appAgent.state.value)
    }

    // 12. Safety Policy: Send/Delete Targets Require Confirmation (MEDIUM Risk)
    @Test
    fun testSafetyPolicyRequiresConfirmation() = runBlocking {
        val sendAction = AppAction.Tap("Send Message")
        val decision = AgentExecutionPolicy.evaluateActionRisk(sendAction)
        assertFalse(decision.isPermitted)
        assertEquals(AppActionResultStatus.REQUIRES_CONFIRMATION, decision.status)

        val executedRes = appAgent.executeAction(sendAction)
        assertEquals(AppActionResultStatus.REQUIRES_CONFIRMATION, executedRes.status)
    }

    // 13. Accessibility Disconnected Returns REQUIRES_ACCESSIBILITY
    @Test
    fun testAccessibilityDisconnectedHandling() = runBlocking {
        bridge.connected = false
        val result = appAgent.executeAction(AppAction.InspectUi)

        assertEquals(AppActionResultStatus.REQUIRES_ACCESSIBILITY, result.status)
        assertEquals(AppAgentState.WAITING_FOR_ACCESSIBILITY, appAgent.state.value)
    }

    // 14. Planner: Multilingual Command Parsing (English, Tamil, Tanglish)
    @Test
    fun testPlannerMultilingualCommands() {
        val planner = AppPlanner()

        // English commands
        val plan1 = planner.createPlan("inspect this screen")
        assertNotNull(plan1)
        assertEquals(1, plan1!!.size)
        assertTrue(plan1.actions[0] is AppAction.InspectUi)

        val plan2 = planner.createPlan("scroll down")
        assertNotNull(plan2)
        assertTrue(plan2!!.actions[0] is AppAction.Scroll)

        // Tanglish / Tamil commands
        val plan3 = planner.createPlan("screen-la enna irukku sollu")
        assertNotNull(plan3)
        assertTrue(plan3!!.actions[0] is AppAction.InspectUi)

        val plan4 = planner.createPlan("keela scroll pannu")
        assertNotNull(plan4)
        assertTrue(plan4!!.actions[0] is AppAction.Scroll)

        val plan5 = planner.createPlan("mela scroll pannu")
        assertNotNull(plan5)
        assertTrue(plan5!!.actions[0] is AppAction.Scroll)

        val plan6 = planner.createPlan("back po")
        assertNotNull(plan6)
        assertTrue(plan6!!.actions[0] is AppAction.Back)

        val plan7 = planner.createPlan("Search button-a tap pannu")
        assertNotNull(plan7)
        assertTrue(plan7!!.actions[0] is AppAction.Tap)
        assertEquals("Search", (plan7.actions[0] as AppAction.Tap).targetQuery)
    }

    // 15. End-to-End CommandProcessor Integration with AppAgent
    @Test
    fun testCommandProcessorWithAppAgent() = runBlocking {
        bridge.mockElements = mutableListOf(
            UiElementInfo(text = "Settings", isClickable = true),
            UiElementInfo(text = "Wi-Fi", isClickable = true)
        )

        // Test English Inspect
        val result1 = processor.processCommand("inspect this screen")
        assertEquals("AppAgent", result1.providerName)
        assertTrue(result1.isSuccess)
        assertTrue(result1.textResponse.contains("Settings") || result1.textResponse.contains("found 2"))

        // Test Tanglish Scroll Down
        val result2 = processor.processCommand("keela scroll pannu")
        assertEquals("AppAgent", result2.providerName)
        assertTrue(result2.isSuccess)
        assertTrue(result2.textResponse.contains("Scrolled"))

        // Test Tanglish Back Navigation
        val result3 = processor.processCommand("back po")
        assertEquals("AppAgent", result3.providerName)
        assertTrue(result3.isSuccess)
        assertTrue(result3.textResponse.contains("Navigated back"))
    }
}
