package com.dylenq.eq.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dylenq.eq.MainActivity
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposeSmokeTest {
    private val grant = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(grant).around(compose)
    private val rule get() = compose

    @Test
    fun composableRenders() {
        rule.onNodeWithText("EQ").assertIsDisplayed()
    }
}
