package com.boopathi.bg

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boopathi.bg.data.local.BGDatabase
import com.boopathi.bg.data.repository.RoomBGRepository
import com.boopathi.bg.service.command.CommandProcessor
import com.boopathi.bg.service.memory.RoomMemoryManager
import com.boopathi.bg.service.tool.FlashlightTool
import com.boopathi.bg.service.tool.GoHomeTool
import com.boopathi.bg.service.tool.OpenAppTool
import com.boopathi.bg.service.tool.OpenSettingsTool
import com.boopathi.bg.service.tool.RiskLevel
import com.boopathi.bg.service.tool.Tool
import com.boopathi.bg.service.tool.ToolIntentDetector
import com.boopathi.bg.service.tool.ToolRegistry
import com.boopathi.bg.service.tool.ToolResult
import com.boopathi.bg.service.tool.ToolStatus
import com.boopathi.bg.service.tool.VolumeTool
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
class Phase3ToolTest {

    private lateinit var context: Context
    private lateinit var db: BGDatabase
    private lateinit var repository: RoomBGRepository
    private lateinit var memoryManager: RoomMemoryManager
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var processor: CommandProcessor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, BGDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomBGRepository(db, Dispatchers.Unconfined)
        memoryManager = RoomMemoryManager(repository)

        toolRegistry = ToolRegistry().apply {
            register(OpenAppTool(context))
            register(FlashlightTool(context))
            register(VolumeTool(context))
            register(OpenSettingsTool(context))
            register(GoHomeTool(context))
        }

        processor = CommandProcessor(
            memoryManager = memoryManager,
            toolRegistry = toolRegistry
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun test1_ToolRegistryRegistrationAndRiskLevelBlocking() = runBlocking {
        // Registered tools exist
        assertNotNull(toolRegistry.getTool(OpenAppTool.NAME))
        assertNotNull(toolRegistry.getTool(FlashlightTool.NAME))
        assertNotNull(toolRegistry.getTool(VolumeTool.NAME))
        assertNotNull(toolRegistry.getTool(OpenSettingsTool.NAME))
        assertNotNull(toolRegistry.getTool(GoHomeTool.NAME))

        // Register a mock HIGH risk tool
        val highRiskTool = object : Tool {
            override val name: String = "DELETE_ALL_FILES"
            override val description: String = "Destructive file deletion"
            override val riskLevel: RiskLevel = RiskLevel.HIGH
            override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
                return ToolResult(ToolStatus.SUCCESS, "Files deleted", name)
            }
        }
        toolRegistry.register(highRiskTool)

        // Attempting to execute HIGH risk tool must be safely blocked
        val result = toolRegistry.execute("DELETE_ALL_FILES")
        assertEquals(ToolStatus.FAILED, result.status)
        assertTrue(result.message.contains("requires user authorization"))
    }

    @Test
    fun test2_ToolIntentDetectorEnglishCommands() {
        // Open App
        val ytIntent = ToolIntentDetector.detectIntent("Open YouTube")
        assertNotNull(ytIntent)
        assertEquals(OpenAppTool.NAME, ytIntent?.toolName)
        assertEquals("youtube", (ytIntent?.parameters?.get("appName") as? String)?.lowercase())

        val gmailIntent = ToolIntentDetector.detectIntent("Launch Gmail")
        assertNotNull(gmailIntent)
        assertEquals(OpenAppTool.NAME, gmailIntent?.toolName)
        assertEquals("gmail", (gmailIntent?.parameters?.get("appName") as? String)?.lowercase())

        // Flashlight
        val flashOn = ToolIntentDetector.detectIntent("Turn on flashlight")
        assertNotNull(flashOn)
        assertEquals(FlashlightTool.NAME, flashOn?.toolName)
        assertEquals(true, flashOn?.parameters?.get("enable"))

        val flashOff = ToolIntentDetector.detectIntent("Turn off flashlight")
        assertNotNull(flashOff)
        assertEquals(FlashlightTool.NAME, flashOff?.toolName)
        assertEquals(false, flashOff?.parameters?.get("enable"))

        // Volume
        val volUp = ToolIntentDetector.detectIntent("Increase volume")
        assertNotNull(volUp)
        assertEquals(VolumeTool.NAME, volUp?.toolName)
        assertEquals("increase", volUp?.parameters?.get("direction"))

        val volDown = ToolIntentDetector.detectIntent("Decrease volume")
        assertNotNull(volDown)
        assertEquals(VolumeTool.NAME, volDown?.toolName)
        assertEquals("decrease", volDown?.parameters?.get("direction"))

        val vol50 = ToolIntentDetector.detectIntent("Set media volume to 50 percent")
        assertNotNull(vol50)
        assertEquals(VolumeTool.NAME, vol50?.toolName)
        assertEquals(50, vol50?.parameters?.get("percent"))

        // Settings
        val settingsIntent = ToolIntentDetector.detectIntent("Open settings")
        assertNotNull(settingsIntent)
        assertEquals(OpenSettingsTool.NAME, settingsIntent?.toolName)
        assertEquals("general", settingsIntent?.parameters?.get("target"))

        val wifiIntent = ToolIntentDetector.detectIntent("Open WiFi settings")
        assertNotNull(wifiIntent)
        assertEquals(OpenSettingsTool.NAME, wifiIntent?.toolName)
        assertEquals("wifi", wifiIntent?.parameters?.get("target"))

        val btIntent = ToolIntentDetector.detectIntent("Open Bluetooth settings")
        assertNotNull(btIntent)
        assertEquals(OpenSettingsTool.NAME, btIntent?.toolName)
        assertEquals("bluetooth", btIntent?.parameters?.get("target"))

        val displayIntent = ToolIntentDetector.detectIntent("Open display settings")
        assertNotNull(displayIntent)
        assertEquals(OpenSettingsTool.NAME, displayIntent?.toolName)
        assertEquals("display", displayIntent?.parameters?.get("target"))

        // Home
        val homeIntent = ToolIntentDetector.detectIntent("Go home")
        assertNotNull(homeIntent)
        assertEquals(GoHomeTool.NAME, homeIntent?.toolName)
    }

    @Test
    fun test3_ToolIntentDetectorTamilAndTanglishCommands() {
        // Tanglish Open App
        val igTanglish = ToolIntentDetector.detectIntent("Instagram open pannu")
        assertNotNull(igTanglish)
        assertEquals(OpenAppTool.NAME, igTanglish?.toolName)
        assertEquals("instagram", (igTanglish?.parameters?.get("appName") as? String)?.lowercase())

        val fbTanglish = ToolIntentDetector.detectIntent("Facebook open pannunga")
        assertNotNull(fbTanglish)
        assertEquals(OpenAppTool.NAME, fbTanglish?.toolName)
        assertEquals("facebook", (fbTanglish?.parameters?.get("appName") as? String)?.lowercase())

        // Tanglish Flashlight
        val flashOnTanglish = ToolIntentDetector.detectIntent("Flashlight on pannu")
        assertNotNull(flashOnTanglish)
        assertEquals(FlashlightTool.NAME, flashOnTanglish?.toolName)
        assertEquals(true, flashOnTanglish?.parameters?.get("enable"))

        val flashOffTanglish = ToolIntentDetector.detectIntent("Flashlight off pannu")
        assertNotNull(flashOffTanglish)
        assertEquals(FlashlightTool.NAME, flashOffTanglish?.toolName)
        assertEquals(false, flashOffTanglish?.parameters?.get("enable"))

        // Tanglish Volume
        val volUpTanglish = ToolIntentDetector.detectIntent("Volume increase pannu")
        assertNotNull(volUpTanglish)
        assertEquals(VolumeTool.NAME, volUpTanglish?.toolName)
        assertEquals("increase", volUpTanglish?.parameters?.get("direction"))

        // Tanglish Settings & Home
        val settingsTanglish = ToolIntentDetector.detectIntent("Settings open pannu")
        assertNotNull(settingsTanglish)
        assertEquals(OpenSettingsTool.NAME, settingsTanglish?.toolName)

        val homeTanglish = ToolIntentDetector.detectIntent("Home-ku po")
        assertNotNull(homeTanglish)
        assertEquals(GoHomeTool.NAME, homeTanglish?.toolName)
    }

    @Test
    fun test4_VolumeToolExecution() = runBlocking {
        val volTool = toolRegistry.getTool(VolumeTool.NAME)
        assertNotNull(volTool)

        val resIncrease = volTool!!.execute(mapOf("direction" to "increase"))
        assertEquals(ToolStatus.SUCCESS, resIncrease.status)
        assertTrue(resIncrease.message.contains("Volume increased"))

        val resPercent = volTool.execute(mapOf("percent" to 60))
        assertEquals(ToolStatus.SUCCESS, resPercent.status)
        assertTrue(resPercent.message.contains("Media volume set to"))
    }

    @Test
    fun test5_OpenSettingsToolAndGoHomeExecution() = runBlocking {
        val settingsTool = toolRegistry.getTool(OpenSettingsTool.NAME)
        assertNotNull(settingsTool)

        val resSettings = settingsTool!!.execute(mapOf("target" to "wifi"))
        assertEquals(ToolStatus.SUCCESS, resSettings.status)
        assertTrue(resSettings.message.contains("Wi-Fi settings"))

        val homeTool = toolRegistry.getTool(GoHomeTool.NAME)
        assertNotNull(homeTool)

        val resHome = homeTool!!.execute()
        assertEquals(ToolStatus.SUCCESS, resHome.status)
        assertEquals("Going home, Boopathi.", resHome.message)
    }

    @Test
    fun test6_OpenAppToolNegativeTestUninstalledApp() = runBlocking {
        val appTool = toolRegistry.getTool(OpenAppTool.NAME)
        assertNotNull(appTool)

        val result = appTool!!.execute(mapOf("appName" to "CompletelyFakeApp999"))
        assertEquals(ToolStatus.UNAVAILABLE, result.status)
        assertEquals("Boopathi, I couldn't find CompletelyFakeApp999 on this phone.", result.message)
    }

    @Test
    fun test7_CommandProcessorEndToEndDeviceControl() = runBlocking {
        // Direct tool command execution through CommandProcessor
        val settingsResult = processor.processCommand("Open Settings")
        assertTrue(settingsResult.isSuccess)
        assertEquals("ToolRegistry", settingsResult.providerName)
        assertTrue(settingsResult.textResponse.contains("Opening System settings, Boopathi."))
        assertNotNull(settingsResult.toolResult)
        assertEquals(ToolStatus.SUCCESS, settingsResult.toolResult?.status)

        // Go home execution
        val homeResult = processor.processCommand("Go home")
        assertTrue(homeResult.isSuccess)
        assertEquals("Going home, Boopathi.", homeResult.textResponse)

        // Uninstalled app failure handling
        val fakeAppResult = processor.processCommand("Open MysteryApp987")
        assertFalse(fakeAppResult.isSuccess)
        assertEquals("Boopathi, I couldn't find MysteryApp987 on this phone.", fakeAppResult.textResponse)

        // Non-tool query should NOT trigger tools
        val timeResult = processor.processCommand("What time is it?")
        assertTrue(timeResult.isSuccess)
        assertEquals("Local Command Engine", timeResult.providerName)
        assertNull(timeResult.toolResult)

        // Greeting should remain conversational
        val greetingResult = processor.processCommand("Hi BG")
        assertTrue(greetingResult.isSuccess)
        assertEquals("Local Command Engine", greetingResult.providerName)
        assertTrue(greetingResult.textResponse.contains("Boopathi"))
    }
}

