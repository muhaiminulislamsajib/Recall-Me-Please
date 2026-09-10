package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.Memory
import com.example.data.repository.AuthRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Recall Me Please", appName)
    }

    @Test
    fun `test memory model serialization and recall source`() {
        val memory = Memory(
            memoryId = "test-123",
            userId = "user-abc",
            date = "September 10, 2026",
            time = "2:30 PM",
            timestamp = 1789000000000L,
            title = "Send Proposal",
            content = "John wants me to send the proposal tomorrow.",
            source = Memory.SOURCE_RECALL_ME_PLEASE,
            detectedPeople = listOf("John"),
            detectedTask = "Send the proposal",
            detectedDeadline = "Tomorrow",
            importanceLevel = Memory.IMPORTANCE_HIGH
        )

        val map = memory.toMap()
        val deserialized = Memory.fromMap("test-123", map)

        assertEquals("test-123", deserialized.memoryId)
        assertEquals("Send Proposal", deserialized.title)
        assertEquals(Memory.SOURCE_RECALL_ME_PLEASE, deserialized.source)
        assertEquals("John", deserialized.detectedPeople.first())
        assertEquals(Memory.IMPORTANCE_HIGH, deserialized.importanceLevel)
    }

    @Test
    fun `test recall me please voice command detection pattern`() {
        val utterance = "Recall Me Please. John wants me to send the proposal tomorrow."
        val recallRegex = Regex("(?i)\\brecall\\s+me\\s+please\\b[.,!]?")
        val match = recallRegex.find(utterance)

        assertTrue(match != null)
        val startIndex = match!!.range.last + 1
        val extractedNote = utterance.substring(startIndex).trim().trimStart(',', '.', ':', '-', ';').trim()
        assertEquals("John wants me to send the proposal tomorrow.", extractedNote)
    }

    @Test
    fun `test user registration and sign in flow works 100 percent`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authRepo = AuthRepository(context)

        // 1. Test registration with name, email, and password
        val registerResult = authRepo.signUp("testuser@example.com", "Secret123", "Test User")
        assertTrue(registerResult.isSuccess)
        val registeredUser = registerResult.getOrThrow()
        assertEquals("testuser@example.com", registeredUser.email)
        assertEquals("Test User", registeredUser.displayName)
        assertNotNull(registeredUser.uid)

        // 2. Test sign in with matching password
        val signInResult = authRepo.signIn("testuser@example.com", "Secret123")
        assertTrue(signInResult.isSuccess)
        val signedInUser = signInResult.getOrThrow()
        assertEquals("testuser@example.com", signedInUser.email)

        // 3. Test sign in with incorrect password fails cleanly
        val failResult = authRepo.signIn("testuser@example.com", "WrongPassword")
        assertTrue(failResult.isFailure)
    }

    @Test
    fun `test google sign in succeeds 100 percent for any user`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authRepo = AuthRepository(context)

        val googleResult = authRepo.signInWithGoogle("muhaiminulislamsajib@gmail.com", "Muhaiminul Islam")
        assertTrue(googleResult.isSuccess)
        val user = googleResult.getOrThrow()
        assertEquals("muhaiminulislamsajib@gmail.com", user.email)
        assertEquals("Muhaiminul Islam", user.displayName)
        assertEquals("google.com", user.provider)
        assertTrue(user.uid.startsWith("google_"))
    }
}
