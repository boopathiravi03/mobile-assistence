package com.boopathi.bg

import com.boopathi.bg.data.model.OwnerProfile
import com.boopathi.bg.service.ai.LocalFallbackProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalFallbackProviderTest {

    private lateinit var provider: LocalFallbackProvider
    private val testOwner = OwnerProfile(name = "Boopathi", preferredLanguage = "en")

    @Before
    fun setup() {
        provider = LocalFallbackProvider()
    }

    @Test
    fun testGreetingCommand() = runBlocking {
        val resultHey = provider.processCommand("Hey BG", testOwner)
        assertTrue(resultHey.isSuccess)
        assertEquals("Hi Boopathi. How can I help you?", resultHey.textResponse)

        val resultHi = provider.processCommand("Hi BG", testOwner)
        assertTrue(resultHi.isSuccess)
        assertEquals("Hi Boopathi. How can I help you?", resultHi.textResponse)
    }

    @Test
    fun testIdentityCommand() = runBlocking {
        val result = provider.processCommand("Who are you?", testOwner)
        assertTrue(result.isSuccess)
        assertEquals("I'm BG, your personal AI mobile assistant.", result.textResponse)
    }

    @Test
    fun testTimeCommand() = runBlocking {
        val result = provider.processCommand("What time is it?", testOwner)
        assertTrue(result.isSuccess)
        assertTrue(result.textResponse.startsWith("The current time is"))
    }

    @Test
    fun testConversationCommand() = runBlocking {
        val result = provider.processCommand("How are you?", testOwner)
        assertTrue(result.isSuccess)
        assertEquals("I'm functioning smoothly and ready to assist you, Boopathi.", result.textResponse)
    }

    @Test
    fun testUnhandledCommand() = runBlocking {
        val result = provider.processCommand("Open the spaceship door", testOwner)
        assertFalse(result.isSuccess)
        assertEquals("I'm having trouble connecting to my AI service right now.", result.textResponse)
    }
}
