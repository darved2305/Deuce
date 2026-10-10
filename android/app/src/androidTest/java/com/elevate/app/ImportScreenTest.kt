package com.elevate.app

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elevate.app.ui.screens.ImportScreen
import com.elevate.app.ui.theme.ElevateTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun chooseButtonOpensThePicker() {
        var clicks = 0
        rule.setContent { ElevateTheme { ImportScreen(opening = false, error = null, onChoose = { clicks++ }) } }
        rule.onNodeWithText("Choose a match video").assertIsEnabled().performClick()
        assertEquals(1, clicks)
    }

    @Test fun whileOpeningTheButtonIsDisabledAndSaysSo() {
        rule.setContent { ElevateTheme { ImportScreen(opening = true, error = null, onChoose = {}) } }
        rule.onNodeWithText("Opening…").assertIsNotEnabled()
        rule.onNodeWithText("Choose a match video").assertDoesNotExist()
    }

    @Test fun anImportErrorIsShown() {
        rule.setContent { ElevateTheme { ImportScreen(opening = false, error = "This file can't be opened as a video.", onChoose = {}) } }
        rule.onNodeWithText("This file can't be opened as a video.").assertExists()
    }
}
