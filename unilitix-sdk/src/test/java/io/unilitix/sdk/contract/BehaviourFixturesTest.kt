package io.unilitix.sdk.contract

import io.unilitix.sdk.util.LogSink
import io.unilitix.sdk.util.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The shared behaviour fixtures (contract 1.4.0) against the engine.
 *
 * Fixtures: `src/test/resources/behaviour/` (a byte copy of the api repo's
 * `api/sdk/behaviour/`; regenerate there with `go run ./cmd/sdkcontract` and
 * copy, never edit). Cases this SDK cannot run are listed with a reason in
 * `src/test/resources/SKIPPED.md` for the API team to review; that
 * list must stay a subset of the cases' ids.
 */
class BehaviourFixturesTest {

    private lateinit var previousSink: LogSink

    @Before
    fun quietLogs() {
        previousSink = Logger.sink
        Logger.sink = object : LogSink {
            override fun debug(tag: String, message: String) {}
            override fun info(tag: String, message: String) {}
            override fun warn(tag: String, message: String) {}
            override fun error(tag: String, message: String, throwable: Throwable?) {}
        }
    }

    @After
    fun restoreLogs() {
        Logger.sink = previousSink
    }

    /** A test resource as a file: from the classpath when it is a directory there, else from the module's sources. */
    private fun resource(name: String): File {
        val url = javaClass.classLoader?.getResource(name)
        if (url != null && url.protocol == "file") return File(url.toURI())
        val local = File("src/test/resources/$name")
        require(local.exists()) { "$name not found on the test classpath or in ${local.absolutePath}" }
        return local
    }

    private fun fixtures() = BehaviourFixtures.load(resource("behaviour"), "android")

    /** Case ids from the skip list: rows that start with "| `id`". */
    private fun skipped(): Set<String> {
        val text = resource("SKIPPED.md").readText()
        return Regex("""(?m)^\|\s*`([a-z0-9.\-]+)`""").findAll(text).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `fixtures are contract 1_4_0 and match the generated constants`() {
        assertEquals(SdkContract.VERSION, BehaviourFixtures.CONTRACT_VERSION)
        val f = fixtures()
        assertTrue("android runs most cases", f.cases.size >= 40)
    }

    @Test
    fun `skip list names only real cases`() {
        val ids = fixtures().cases.map { it.id }.toSet()
        val unknown = skipped() - ids
        assertTrue("skip list names cases that do not exist (or do not apply to android): $unknown", unknown.isEmpty())
    }

    @Test
    fun `every android behaviour case passes`() {
        val tmp = Files.createTempDirectory("behaviour").toFile()
        try {
            val failures = BehaviourRunner(fixtures(), tmp).runAll(skipped())
            if (failures.isNotEmpty()) {
                fail("${failures.size} behaviour case(s) failed:\n" + failures.joinToString("\n\n") { "${it.caseId}: ${it.message}" })
            }
        } finally {
            tmp.deleteRecursively()
        }
    }
}
