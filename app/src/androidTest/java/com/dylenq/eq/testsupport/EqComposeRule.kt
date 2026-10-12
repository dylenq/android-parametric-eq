package com.dylenq.eq.testsupport

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.dylenq.eq.MainActivity
import com.dylenq.eq.service.EqService
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Compose UI test rule: grants notifications, installs a fresh runtime ([RuntimeRule], outermost
 * among ours), and launches [MainActivity] on demand. Teardown closes the scenario and stops EqService.
 */
class EqComposeRule(factoryProvider: () -> com.dylenq.eq.service.EffectFactory = { FakeEffectFactory() }) : TestRule {
    val runtimeRule = RuntimeRule(factoryProvider)
    val compose: ComposeTestRule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    val runtime get() = runtimeRule.runtime

    fun launch(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }

    private val cleanup = object : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } finally {
                    scenario?.close()
                    scenario = null
                    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
                    ctx.stopService(Intent(ctx, EqService::class.java))
                }
            }
        }
    }

    private val chain: TestRule = RuleChain.emptyRuleChain().let {
        var c = it
        if (Build.VERSION.SDK_INT >= 33) {
            c = c.around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        }
        c.around(runtimeRule).around(cleanup).around(compose)
    }

    override fun apply(base: Statement, description: Description): Statement = chain.apply(base, description)
}
