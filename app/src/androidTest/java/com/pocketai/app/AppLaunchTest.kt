package com.pocketai.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launches the real activity and waits for PocketAI's own UI to compose, which
 * covers application startup, the settings DataStore read, theme resolution and
 * the navigation graph in one go.
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun applicationLaunchesAndRendersPocketAiUi() {
        // Either first-run setup or the chat screen is valid here; both prove the
        // app got past startup and composed its own content.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("PocketAI", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            composeRule.onAllNodesWithText("PocketAI", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun firstRunSetupExplainsLocalAiAndPrivacy() {
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("Welcome to PocketAI").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Message PocketAI").fetchSemanticsNodes().isNotEmpty()
        }

        val onboarding = composeRule.onAllNodesWithText("Welcome to PocketAI")
            .fetchSemanticsNodes().isNotEmpty()
        if (!onboarding) return   // onboarding already completed on this device

        // The welcome step must state the two claims the product rests on.
        assertTrue(
            composeRule.onAllNodesWithText("The model lives on your device")
                .fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            composeRule.onAllNodesWithText("Private by default")
                .fetchSemanticsNodes().isNotEmpty()
        )
    }
}
