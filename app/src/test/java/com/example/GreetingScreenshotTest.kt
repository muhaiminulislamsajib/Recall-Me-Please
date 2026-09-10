package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.data.model.Memory
import com.example.ui.components.MemoryCard
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class GreetingScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun memory_card_screenshot() {
        val sampleMemory = Memory(
            memoryId = "preview-1",
            userId = "sample-user",
            date = "September 10, 2026",
            time = "2:35 PM",
            timestamp = 1789000000000L,
            title = "Send Proposal to John",
            content = "John wants me to send the proposal tomorrow.",
            source = Memory.SOURCE_RECALL_ME_PLEASE,
            detectedPeople = listOf("John"),
            detectedTask = "Send proposal",
            detectedDeadline = "Tomorrow",
            importanceLevel = Memory.IMPORTANCE_HIGH
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                MemoryCard(
                    memory = sampleMemory,
                    onDelete = {}
                )
            }
        }

        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/memory_card.png")
    }
}
