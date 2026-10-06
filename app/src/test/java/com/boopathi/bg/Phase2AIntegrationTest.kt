package com.boopathi.bg

import com.boopathi.bg.data.model.ChatMessage
import com.boopathi.bg.data.model.CommandResult
import com.boopathi.bg.data.model.OwnerProfile
import com.boopathi.bg.data.model.Sender
import com.boopathi.bg.service.ai.AIProvider
import com.boopathi.bg.service.command.CommandProcessor
import com.boopathi.bg.service.memory.InMemoryMemoryManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase2AIntegrationTest {

    @Test
    fun testContextWindowRetention() = runBlocking {
        val memory = InMemoryMemoryManager()
        // Simulate 3 turns of conversation
        memory.logInteraction("What is Kotlin?", "Kotlin is a modern language.")
        memory.logInteraction("Who created it?", "JetBrains created it.")
        memory.logInteraction("Is it good for Android?", "Yes, it is the preferred language.")

        val context = memory.getRecentContext(maxTurns = 2)
        // 2 turns = 4 messages (last 2 interactions)
        assertEquals(4, context.size)
        assertEquals("Who created it?", context[0].text)
        assertEquals("JetBrains created it.", context[1].text)
        assertEquals("Is it good for Android?", context[2].text)
        assertEquals("Yes, it is the preferred language.", context[3].text)
    }

    @Test
    fun testCommandRoutingLocalPriority() = runBlocking {
        val memory = InMemoryMemoryManager()
        val processor = CommandProcessor(memoryManager = memory)

        // Local command should resolve immediately
        val timeResult = processor.processCommand("What time is it?")
        assertTrue(timeResult.isSuccess)
        assertTrue(timeResult.textResponse.contains("The current time is"))

        val greetingResult = processor.processCommand("Hi BG")
        assertTrue(greetingResult.isSuccess)
        assertEquals("Hi Boopathi. How can I help you?", greetingResult.textResponse)
    }

    @Test
    fun testAiGracefulFallbackWhenNoKey() = runBlocking {
        val memory = InMemoryMemoryManager()
        // Default GeminiProvider with no API key
        val processor = CommandProcessor(memoryManager = memory)

        val aiResult = processor.processCommand("What is Kotlin?")
        assertFalse(aiResult.isSuccess)
        assertEquals("I'm having trouble connecting to my AI service right now.", aiResult.textResponse)

        // Local command must still work immediately after AI failure!
        val localResult = processor.processCommand("What time is it?")
        assertTrue(localResult.isSuccess)
        assertTrue(localResult.textResponse.startsWith("The current time is"))
    }

    @Test
    fun testMockGeminiContextForwarding() = runBlocking {
        val memory = InMemoryMemoryManager()

        // Mock AIProvider that inspects received context
        var receivedHistorySize = 0
        var receivedPrompt = ""

        val mockAiProvider = object : AIProvider {
            override val name: String = "MockGemini"
            override val isAvailable: Boolean = true

            override suspend fun processCommand(
                prompt: String,
                owner: OwnerProfile,
                conversationHistory: List<ChatMessage>,
                relevantMemories: List<com.boopathi.bg.data.local.entity.MemoryEntity>
            ): CommandResult {
                receivedHistorySize = conversationHistory.size
                receivedPrompt = prompt
                return CommandResult(
                    textResponse = "Response to $prompt with ${conversationHistory.size} previous messages",
                    providerName = name,
                    isSuccess = true
                )
            }
        }

        val processor = CommandProcessor(
            memoryManager = memory,
            geminiProvider = mockAiProvider
        )

        // Turn 1
        val turn1 = processor.processCommand("What is Kotlin?")
        assertTrue(turn1.isSuccess)
        assertEquals(0, receivedHistorySize)
        assertEquals("What is Kotlin?", receivedPrompt)

        // Turn 2: Follow-up question
        val turn2 = processor.processCommand("Who created it?")
        assertTrue(turn2.isSuccess)
        // Previous interaction logged: 1 user msg + 1 bg msg = 2 messages in history
        assertEquals(2, receivedHistorySize)
        assertEquals("Who created it?", receivedPrompt)
    }
}

