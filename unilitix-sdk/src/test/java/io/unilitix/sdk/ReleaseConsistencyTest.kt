package io.unilitix.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.10.2: the reported sdkVersion, the published version, the README install
 * snippet and the CHANGELOG agree (1.10.1 reported itself as 1.10.0), and the
 * consumer R8 rules keep @UnilitixPrivate.
 */
class ReleaseConsistencyTest {

    /** The repository root: the unit test's working directory is the module. */
    private val root: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        requireNotNull(dir) { "settings.gradle.kts not found above ${File("").absolutePath}" }
    }

    private val version: String by lazy {
        File(root, "gradle.properties").readLines()
            .first { it.startsWith("unilitix.version=") }
            .substringAfter('=').trim()
    }

    @Test
    fun `BuildConfig reports the published version`() {
        assertEquals(version, BuildConfig.SDK_VERSION)
    }

    @Test
    fun `README install snippet and CHANGELOG name the version`() {
        val readme = File(root, "README.md").readText()
        assertTrue("README install snippet", readme.contains("com.unilitix:unilitix-android:$version\""))
        val changelog = File(root, "CHANGELOG.md").readText()
        assertTrue("CHANGELOG entry", changelog.contains("\n## $version\n"))
    }

    @Test
    fun `consumer rules keep the UnilitixPrivate annotation on app classes`() {
        val rules = File(root, "unilitix-sdk/consumer-rules.pro").readText()
        assertTrue(rules.contains("-keepattributes RuntimeVisibleAnnotations"))
        assertTrue(rules.contains("@io.unilitix.sdk.UnilitixPrivate class *"))
        assertTrue(rules.contains("-keep @interface io.unilitix.sdk.UnilitixPrivate"))
        // Never keep the host app's Room classes or silence whole libraries.
        assertTrue(!rules.contains("-keep @androidx.room.Entity class *\n"))
        assertTrue(!rules.contains("-dontwarn okhttp3.**"))
    }
}
