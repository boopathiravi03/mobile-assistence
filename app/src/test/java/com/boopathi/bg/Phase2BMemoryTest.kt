package com.boopathi.bg

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boopathi.bg.data.local.BGDatabase
import com.boopathi.bg.data.local.entity.MemoryEntity
import com.boopathi.bg.data.model.OwnerProfile
import com.boopathi.bg.data.repository.RoomBGRepository
import com.boopathi.bg.service.command.CommandProcessor
import com.boopathi.bg.service.memory.RoomMemoryManager
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
class Phase2BMemoryTest {

    private lateinit var context: Context
    private lateinit var db: BGDatabase
    private lateinit var repository: RoomBGRepository
    private lateinit var memoryManager: RoomMemoryManager
    private lateinit var processor: CommandProcessor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, BGDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomBGRepository(db, Dispatchers.Unconfined)
        memoryManager = RoomMemoryManager(repository)
        processor = CommandProcessor(memoryManager)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun test1_SaveAndRetrieveOwnerProfile() = runBlocking {
        val initial = memoryManager.getOwnerProfile()
        assertEquals("Boopathi", initial.name)
        assertEquals("en", initial.preferredLanguage)

        memoryManager.updateOwnerProfile(OwnerProfile(name = "Boopathi R", preferredLanguage = "ta"))
        val updated = memoryManager.getOwnerProfile()
        assertEquals("Boopathi R", updated.name)
        assertEquals("ta", updated.preferredLanguage)
    }

    @Test
    fun test2_SaveAndRetrieveMemory() = runBlocking {
        val saved = memoryManager.saveMemory(
            title = "Favorite programming language",
            content = "Kotlin",
            type = MemoryEntity.TYPE_PREFERENCE
        )
        assertNotNull(saved)
        assertTrue(saved.id > 0)

        val memories = memoryManager.getMemories()
        assertEquals(1, memories.size)
        assertEquals("Favorite programming language", memories[0].title)
        assertEquals("Kotlin", memories[0].content)
        assertEquals(MemoryEntity.TYPE_PREFERENCE, memories[0].type)
    }

    @Test
    fun test3_DeleteSpecificMemory() = runBlocking {
        memoryManager.saveMemory("Favorite programming language", "Kotlin", MemoryEntity.TYPE_PREFERENCE)
        memoryManager.saveMemory("Pet", "Dog", MemoryEntity.TYPE_FACT)

        assertEquals(2, memoryManager.getMemories().size)

        val deleted = memoryManager.forgetMemory("Kotlin")
        assertTrue(deleted)

        val remaining = memoryManager.getMemories()
        assertEquals(1, remaining.size)
        assertEquals("Pet", remaining[0].title)
    }

    @Test
    fun test4_SaveAndRetrieveNotes() = runBlocking {
        memoryManager.saveNote("Need to complete DBMS assignment")
        memoryManager.saveNote("Meeting with mentor at 3 PM")

        val notes = memoryManager.getNotes()
        assertEquals(2, notes.size)
        assertTrue(notes.any { it.content.contains("DBMS assignment") })
        assertTrue(notes.any { it.content.contains("Meeting with mentor") })
    }

    @Test
    fun test5_CreateAndCompleteTask() = runBlocking {
        val task = memoryManager.createTask("Study DBMS", "Prepare chapter 3")
        assertNotNull(task)
        assertFalse(task.completed)

        val activeTasks = memoryManager.getTasks(completed = false)
        assertEquals(1, activeTasks.size)
        assertEquals("Study DBMS", activeTasks[0].title)

        val marked = memoryManager.markTaskComplete("DBMS")
        assertTrue(marked)

        val remainingActive = memoryManager.getTasks(completed = false)
        assertEquals(0, remainingActive.size)
    }

    @Test
    fun test6_ConversationPersistence() = runBlocking {
        memoryManager.logInteraction("What is Kotlin?", "Kotlin is an expressive language.")
        
        val persisted = repository.getAllMessages()
        assertEquals(2, persisted.size)
        assertEquals("USER", persisted[0].sender)
        assertEquals("What is Kotlin?", persisted[0].message)
        assertEquals("BG", persisted[1].sender)
        assertEquals("Kotlin is an expressive language.", persisted[1].message)
    }

    @Test
    fun test7_AcceptanceSequenceSteps1To10() = runBlocking {
        // STEP 1: Remember favorite programming language
        val step1 = processor.processCommand("Remember that my favorite programming language is Kotlin.")
        assertTrue(step1.isSuccess)
        assertEquals("Got it, Boopathi. I'll remember that.", step1.textResponse)

        // STEP 2 & 3: Simulate Close BG and Reopen BG
        // Create new manager instance pointing to the same database
        val reopenedManager = RoomMemoryManager(repository)
        val reopenedProcessor = CommandProcessor(reopenedManager)

        // STEP 4: Ask what is favorite programming language
        val step4 = reopenedProcessor.processCommand("What is my favorite programming language?")
        assertTrue(step4.isSuccess)
        assertEquals("Kotlin.", step4.textResponse)

        // STEP 5: Remember project is called BG
        val step5 = reopenedProcessor.processCommand("Remember that my project is called BG.")
        assertTrue(step5.isSuccess)
        assertEquals("Got it, Boopathi. I'll remember that.", step5.textResponse)

        // STEP 6: Ask what BG remembers
        val step6 = reopenedProcessor.processCommand("What do you remember about me?")
        assertTrue(step6.isSuccess)
        assertTrue(step6.textResponse.contains("Kotlin"))
        assertTrue(step6.textResponse.contains("BG"))

        // STEP 7: Note that I need to complete my DBMS assignment
        val step7 = reopenedProcessor.processCommand("Note that I need to complete my DBMS assignment.")
        assertTrue(step7.isSuccess)
        assertEquals("Note saved, Boopathi.", step7.textResponse)

        // STEP 8: Ask for notes
        val step8 = reopenedProcessor.processCommand("What are my notes?")
        assertTrue(step8.isSuccess)
        assertTrue(step8.textResponse.contains("complete my DBMS assignment"))

        // STEP 9: Remind me to study DBMS
        val step9 = reopenedProcessor.processCommand("Remind me to study DBMS.")
        assertTrue(step9.isSuccess)
        assertEquals("Okay, Boopathi. I've added that task.", step9.textResponse)

        // Verify task stored
        val tasks = reopenedManager.getTasks(completed = false)
        assertEquals(1, tasks.size)
        assertEquals("Study DBMS", tasks[0].title)

        // STEP 10: Restart application and verify persistence
        val finalManager = RoomMemoryManager(repository)
        assertEquals(2, finalManager.getMemories().filter { it.type != MemoryEntity.TYPE_NOTE }.size)
        assertEquals(1, finalManager.getNotes().size)
        assertEquals(1, finalManager.getTasks(completed = false).size)
    }
}

