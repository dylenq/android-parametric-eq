package com.dylenq.eq.testsupport

import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.EqRuntime
import com.dylenq.eq.service.EffectFactory
import java.io.File
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Installs a fresh [EqRuntime] (fake factory by default, temp state dir) for each test and tears it
 * down afterwards. Tests share one process, so the previous runtime is always restored.
 */
class RuntimeRule(private val factoryProvider: () -> EffectFactory = { FakeEffectFactory() }) : TestRule {
    lateinit var runtime: EqRuntime
        private set
    lateinit var factory: EffectFactory
        private set
    lateinit var dir: File
        private set

    @Suppress("UNCHECKED_CAST")
    fun <T : EffectFactory> factoryAs(): T = factory as T

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val app = ctx.applicationContext as EqApp
            val previous = app.peekRuntime()
            dir = File(ctx.cacheDir, "rt-${System.nanoTime()}").also { it.mkdirs() }
            factory = factoryProvider()
            runtime = EqRuntime(factory, dir)
            app.installForTest(runtime)
            try {
                base.evaluate()
            } finally {
                runtime.shutdown()
                app.installForTest(previous)
                dir.deleteRecursively()
            }
        }
    }
}
