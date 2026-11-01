package com.dylenq.eq.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dylenq.eq.testsupport.EqComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupErrorScreenTest {
    @get:Rule val rule = EqComposeRule()

    @Test fun explainsTheFailureAndNamesTheCause() {
        rule.host { EqTheme { StartupErrorScreen("UnsatisfiedLinkError") } }
        rule.compose.onNodeWithText("EQ could not start").assertIsDisplayed()
        rule.compose.onNodeWithText("UnsatisfiedLinkError").assertIsDisplayed()
    }
}
