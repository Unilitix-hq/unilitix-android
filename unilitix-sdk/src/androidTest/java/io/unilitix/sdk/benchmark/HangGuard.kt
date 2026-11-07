package io.unilitix.sdk.benchmark

import android.os.Looper
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Like JUnit's Timeout, but a hang fails with the main thread's stack in the
 * message: on an emulator the test thread is usually just waiting for the
 * main thread, so its own stack (Object.wait) says nothing.
 */
class HangGuard(private val seconds: Long) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "test-${description.methodName}").apply { isDaemon = true } }
            val future = executor.submit<Unit> { base.evaluate() }
            try {
                future.get(seconds, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            } catch (e: TimeoutException) {
                val main = Looper.getMainLooper().thread
                val testThread = Thread.getAllStackTraces().keys.firstOrNull { it.name == "test-${description.methodName}" }
                fun dump(t: Thread?) = t?.stackTrace?.joinToString("\n") { "    at $it" } ?: "    (not found)"
                throw AssertionError(
                    "${description.methodName} hung for ${seconds}s.\n" +
                        "Main thread (${main.state}):\n${dump(main)}\n" +
                        "Test thread:\n${dump(testThread)}",
                )
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
