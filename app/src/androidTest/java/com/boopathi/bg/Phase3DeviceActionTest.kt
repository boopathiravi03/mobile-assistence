package com.boopathi.bg

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boopathi.bg.data.local.BGDatabase
import com.boopathi.bg.data.repository.RoomBGRepository
import com.boopathi.bg.service.command.CommandProcessor
import com.boopathi.bg.service.memory.RoomMemoryManager
import com.boopathi.bg.service.tool.FlashlightTool
import com.boopathi.bg.service.tool.GoHomeTool
import com.boopathi.bg.service.tool.OpenAppTool
import com.boopathi.bg.service.tool.OpenSettingsTool
import com.boopathi.bg.service.tool.ToolRegistry
import com.boopathi.bg.service.tool.ToolStatus
import com.boopathi.bg.service.tool.VolumeTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase3DeviceActionTest {

    private lateinit var targetContext: Context
    private lateinit var db: BGDatabase
    private lateinit var repository: RoomBGRepository
    private lateinit var memoryManager: RoomMemoryManager
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var processor: CommandProcessor

    @Before
    fun setUp() {
        targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(targetContext, BGDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomBGRepository(db, Dispatchers.Unconfined)
        memoryManager = RoomMemoryManager(repository, CoroutineScope(Dispatchers.Unconfined))

        toolRegistry = ToolRegistry().apply {
            register(OpenAppTool(targetContext))
            register(FlashlightTool(targetContext))
            register(VolumeTool(targetContext))
            register(OpenSettingsTool(targetContext))
            register(GoHomeTool(targetContext))
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
    fun testMatrix_RealDeviceActions() = runBlocking {
        // 1. "Open Settings" -> Real intent launch
        val settingsRes = processor.processCommand("Open Settings")
        assertTrue(settingsRes.isSuccess)
        assertEquals(ToolStatus.SUCCESS, settingsRes.toolResult?.status)
        assertTrue(settingsRes.textResponse.contains("Opening System settings, Boopathi."))

        // 2. "Open WiFi settings" -> Real intent launch
        val wifiRes = processor.processCommand("Open WiFi settings")
        assertTrue(wifiRes.isSuccess)
        assertEquals(ToolStatus.SUCCESS, wifiRes.toolResult?.status)
        assertTrue(wifiRes.textResponse.contains("Opening Wi-Fi settings, Boopathi."))

        // 3. "Increase volume" -> Real AudioManager adjustment
        val volUpRes = processor.processCommand("Increase volume")
        assertTrue(volUpRes.isSuccess)
        assertEquals(ToolStatus.SUCCESS, volUpRes.toolResult?.status)
        assertTrue(volUpRes.textResponse.contains("Volume increased"))

        // 4. "Decrease volume" -> Real AudioManager adjustment
        val volDownRes = processor.processCommand("Decrease volume")
        assertTrue(volDownRes.isSuccess)
        assertEquals(ToolStatus.SUCCESS, volDownRes.toolResult?.status)
        assertTrue(volDownRes.textResponse.contains("Volume decreased"))

        // 5. "Go home" -> Real ACTION_MAIN + CATEGORY_HOME launch
        val homeRes = processor.processCommand("Go home")
        assertTrue(homeRes.isSuccess)
        assertEquals(ToolStatus.SUCCESS, homeRes.toolResult?.status)
        assertEquals("Going home, Boopathi.", homeRes.textResponse)

        // 6. Tanglish commands
        val tanglishVol = processor.processCommand("Volume increase pannu")
        assertTrue(tanglishVol.isSuccess)
        assertEquals(ToolStatus.SUCCESS, tanglishVol.toolResult?.status)

        val tanglishHome = processor.processCommand("Home-ku po")
        assertTrue(tanglishHome.isSuccess)
        assertEquals(ToolStatus.SUCCESS, tanglishHome.toolResult?.status)

        // 7. Flashlight on real device / emulator (checks live torch support)
        val flashOnRes = processor.processCommand("Turn on flashlight")
        if (flashOnRes.isSuccess) {
            assertEquals(ToolStatus.SUCCESS, flashOnRes.toolResult?.status)
            assertEquals("Flashlight turned on, Boopathi.", flashOnRes.textResponse)

            val flashOffRes = processor.processCommand("Turn off flashlight")
            assertTrue(flashOffRes.isSuccess)
            assertEquals(ToolStatus.SUCCESS, flashOffRes.toolResult?.status)
            assertEquals("Flashlight turned off, Boopathi.", flashOffRes.textResponse)
        } else {
            assertEquals(ToolStatus.UNAVAILABLE, flashOnRes.toolResult?.status)
            assertEquals("Flashlight is not available on this device.", flashOnRes.textResponse)
        }

        // 8. Open uninstalled apps -> UNAVAILABLE
        val igRes = processor.processCommand("Open Instagram")
        assertFalse(igRes.isSuccess)
        assertEquals(ToolStatus.UNAVAILABLE, igRes.toolResult?.status)
        assertEquals("Boopathi, I couldn't find Instagram on this phone.", igRes.textResponse)

        val igTanglish = processor.processCommand("Instagram open pannu")
        assertFalse(igTanglish.isSuccess)
        assertEquals(ToolStatus.UNAVAILABLE, igTanglish.toolResult?.status)
        assertEquals("Boopathi, I couldn't find Instagram on this phone.", igTanglish.textResponse)

        val waRes = processor.processCommand("Open WhatsApp")
        assertFalse(waRes.isSuccess)
        assertEquals(ToolStatus.UNAVAILABLE, waRes.toolResult?.status)
        assertEquals("Boopathi, I couldn't find WhatsApp on this phone.", waRes.textResponse)

        val waTanglish = processor.processCommand("WhatsApp open pannu")
        assertFalse(waTanglish.isSuccess)
        assertEquals(ToolStatus.UNAVAILABLE, waTanglish.toolResult?.status)
        assertEquals("Boopathi, I couldn't find WhatsApp on this phone.", waTanglish.textResponse)

        // 9. Negative test: Fake app
        val fakeRes = processor.processCommand("Open CompletelyNonExistentApp123")
        assertFalse(fakeRes.isSuccess)
        assertEquals(ToolStatus.UNAVAILABLE, fakeRes.toolResult?.status)
        assertEquals("Boopathi, I couldn't find CompletelyNonExistentApp123 on this phone.", fakeRes.textResponse)

        // 10. Open YouTube (installed on Google APIs emulator)
        val ytRes = processor.processCommand("Open YouTube")
        if (ytRes.isSuccess) {
            assertEquals(ToolStatus.SUCCESS, ytRes.toolResult?.status)
            assertTrue(ytRes.textResponse.contains("YouTube is open"))
        } else {
            assertEquals(ToolStatus.UNAVAILABLE, ytRes.toolResult?.status)
        }
    }
}

