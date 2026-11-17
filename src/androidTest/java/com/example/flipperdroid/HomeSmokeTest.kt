package com.example.flipperdroid

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator smoke test: the app starts, the legal gate is dismissible, and the
 * Home grid renders. Deliberately minimal — per-module behaviour is covered by
 * the JVM tiers (`src/test`), and anything that needs a radio, an IR blaster, an
 * NFC controller or root needs a real phone.
 *
 * Run against a Simlock lease:
 * ```
 * eval "$(scripts/adb-env.sh)"
 * ./gradlew connectedAndroidTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class HomeSmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun legalGateThenHomeGrid() {
        compose.waitForIdle()

        // The gate lives in HomeScreen and is shown only while "legalAccepted" is
        // not stored. App data survives between runs on the same lease, so accept
        // it only when it is on screen.
        if (compose.onAllNodesWithText("Accept and continue").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Accept and continue").performClick()
            compose.waitForIdle()
        }

        compose.onNodeWithText("NFC Reader").assertIsDisplayed()
        compose.onNodeWithText("NFC & RFID").assertIsDisplayed()
    }
}
