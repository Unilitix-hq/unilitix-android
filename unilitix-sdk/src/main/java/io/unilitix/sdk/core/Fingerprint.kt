package io.unilitix.sdk.core

/**
 * A stable id for "this bug", so the dashboard counts one crash once across
 * devices: the exception type plus the first three stack frames with line
 * and column numbers removed. A port of the contract's reference
 * implementation (api repo `internal/sdkcontract/fingerprint.go` and, for
 * native crashes, `native.go`); the generated `SdkContract.fingerprintVectors`
 * pin the output and run in `SdkContractTest`.
 *
 * Frame forms (first match wins): Apple callStackSymbols, Dart (`#0 …`,
 * including AOT `virt` frames), JavaScript / Hermes (`at fn (file:1:2)`),
 * JVM (`at a.B.c(B.kt:12)`) and bare `at …` frames. JVM frames must start
 * with `at`: otherwise a message line such as
 * `TypeError: x is not an object (evaluating 'a.b')` counts as a frame.
 *
 * Native crashes (contract 1.4.0): when the type is `SIGNAL` or
 * `SIGNAL:<n>`, only the app's own frames count ([NativeFrames]): system
 * libraries, language runtimes, cross-platform engines and the Unilitix SDK
 * are skipped, so a crash inside libc called from the app groups by the app's
 * code. With no app frame the result is the 1.3 value, `SIGNAL:<n>` alone.
 */
internal object Fingerprint {

    const val FRAMES = 3

    private val appleFrame = Regex("""^\d+\s+(\S+)\s+0x[0-9a-fA-F]+\s+(.+?)(?:\s+\+\s+(\d+))?$""")
    private val dartFrame = Regex("""^#\d+\s+(.*)$""")
    private val dartVirt = Regex("""virt\s+([0-9a-f]+)""")
    private val dartLine = Regex(""":\d+(:\d+)?\)""")
    private val spaces = Regex("""\s+""")
    private val jsFrame = Regex("""^at\s+(.*?)\s*\((.*?)(?::\d+)+\)$""")
    private val jvmFrame = Regex("""^at\s+(.*?)\(([^:()]+)(?::\d+)?\)$""")
    private val trailLine = Regex(""":\d+(:\d+)?$""")
    private val signalType = Regex("""^SIGNAL(?::(\d+))?$""")

    fun of(exceptionType: String, stackTrace: String?): String {
        if (isSignalType(exceptionType)) return native(exceptionType, stackTrace ?: "")
        val parts = mutableListOf(exceptionType)
        if (stackTrace != null) {
            var taken = 0
            for (raw in stackTrace.split('\n')) {
                if (taken == FRAMES) break
                val frame = normalise(raw) ?: continue
                parts.add(frame)
                taken++
            }
        }
        return fnv1a64(parts.joinToString("|"))
    }

    /** `SIGNAL` or `SIGNAL:<n>`. */
    fun isSignalType(type: String): Boolean = signalType.matches(type)

    /**
     * The fingerprint type of a native crash: `SIGNAL:<n>` from type `SIGNAL`
     * and [signal] n (`SIGNAL` when n is unknown); a type that already is
     * `SIGNAL:<n>` is kept.
     */
    fun signalFingerprintType(exceptionType: String, signal: Int?): String {
        val m = signalType.matchEntire(exceptionType)
        if (m != null && m.groupValues[1].isNotEmpty()) return exceptionType
        return if (signal != null && signal > 0) "SIGNAL:$signal" else "SIGNAL"
    }

    /** `SIGNAL:<n>` plus the stack's first app frames. */
    fun native(signalType: String, stackTrace: String): String =
        fnv1a64((listOf(signalType) + NativeFrames.appFrames(stackTrace)).joinToString("|"))

    /** One frame with its location stripped of line/column, or null if the line is not a frame. */
    internal fun normalise(rawLine: String): String? {
        val line = rawLine.trim()
        if (line.isEmpty()) return null
        appleFrame.matchEntire(line)?.let { m ->
            val image = m.groupValues[1]
            val symbol = m.groupValues[2].trim()
            val offset = m.groupValues[3]
            return if (symbol == image && offset.isNotEmpty()) "$image+$offset" else "$image $symbol"
        }
        dartFrame.matchEntire(line)?.let { m ->
            val frame = m.groupValues[1]
            dartVirt.find(frame)?.let { return "virt:${it.groupValues[1]}" }
            val noLine = dartLine.replace(frame, ")")
            if (noLine.startsWith("<asynchronous suspension>")) return null
            return spaces.replace(noLine, " ").trim()
        }
        jsFrame.matchEntire(line)?.let { return "${it.groupValues[1]} (${it.groupValues[2]})" }
        jvmFrame.matchEntire(line)?.let { return "${it.groupValues[1]}(${it.groupValues[2]})" }
        if (line.startsWith("at ")) return trailLine.replace(line.removePrefix("at "), "").trim()
        return null
    }

    /** Two 32-bit FNV-1a hashes with different seeds over the UTF-8 bytes. */
    internal fun fnv1a64(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val a = fnv1a32(bytes, 0x811c9dc5L)
        val b = fnv1a32(bytes, 0x811c9dc5L xor 0x5bd1e995L)
        return String.format(java.util.Locale.US, "%08x%08x", a, b)
    }

    private fun fnv1a32(bytes: ByteArray, seed: Long): Long {
        var h = seed
        for (byte in bytes) {
            h = h xor (byte.toLong() and 0xff)
            h = (h * 16777619L) and 0xffffffffL
        }
        return h
    }
}

/**
 * Native stack frames for crash grouping (contract 1.4.0, reference
 * `internal/sdkcontract/native.go`). Reads Android tombstone / unwindstack
 * lines (also after `native: ` in ANR dumps), Apple callStackSymbols lines
 * and Dart AOT lines, and tells the app's own frames from system ones using
 * the contract's `nativeSystemImages` and `nativeSystemPathPrefixes`.
 */
internal object NativeFrames {

    /** Contract `nativeSystemPathPrefixes`. */
    val SYSTEM_PATH_PREFIXES: List<String> = listOf(
        "/system/", "/apex/", "/vendor/", "/product/", "/system_ext/", "/odm/",
        "/data/dalvik-cache/", "/data/misc/", "/dev/", "/memfd:", "[", "<anonymous",
        "/usr/lib/", "/System/Library/", "/Library/", "/private/preboot/Cryptexes/",
    )

    /** Contract `nativeSystemImages`; a trailing `*` matches by prefix. */
    val SYSTEM_IMAGES: List<String> = listOf(
        // Android OS and ART
        "libc.so", "libm.so", "libdl.so", "liblog.so", "libutils.so", "libcutils.so", "libbinder.so",
        "libandroid.so", "libandroid_runtime.so", "libart.so", "libartbase.so", "libart-compiler.so",
        "libnativebridge.so", "libnativeloader.so", "libhwui.so", "libgui.so", "libui.so", "libEGL.so",
        "libGLESv1_CM.so", "libGLESv2.so", "libGLESv3.so", "libvulkan.so", "libmediandk.so", "libcamera2ndk.so",
        "libsqlite.so", "libjavacore.so", "libopenjdk.so", "libopenjdkjvm.so", "linker", "linker64",
        "app_process32", "app_process64", "boot.oat", "boot-framework.oat", "boot.art", "base.odex", "base.vdex",
        "libc++.so", "libc++_shared.so", "libunwind.so", "libunwindstack.so", "libbacktrace.so",
        // Apple OS and runtimes
        "libsystem_*", "libdyld.dylib", "dyld", "libobjc.A.dylib", "libdispatch.dylib", "libc++.1.dylib",
        "libc++abi.dylib", "libswiftCore.dylib", "libswift_*", "libswiftFoundation.dylib", "libxpc.dylib",
        "CoreFoundation", "Foundation", "UIKitCore", "UIKit", "SwiftUI", "SwiftUICore", "GraphicsServices",
        "QuartzCore", "CoreGraphics", "CoreText", "WebKit", "JavaScriptCore", "AppKit", "Combine",
        "FrontBoardServices", "BackBoardServices", "RunningBoardServices", "CFNetwork", "Security",
        // Flutter engine
        "libflutter.so", "Flutter", "FlutterMacOS",
        // React Native / Hermes / JSC
        "libhermes.so", "libhermes_executor.so", "libhermestooling.so", "hermes", "libjsc.so", "libjscexecutor.so",
        "libreactnativejni.so", "libreactnative.so", "libreact_*", "libjsi.so", "libjsinspector.so",
        "libfbjni.so", "libfolly_runtime.so", "libfolly_json.so", "libglog.so", "libyoga.so", "libturbomodulejsijni.so",
        "libfabricjni.so", "librrc_*", "React", "React_*", "ReactCommon", "RCT*", "yoga", "folly",
        // Unilitix SDKs
        "libunilitix-native.so", "libunilitix_native.so", "UnilitixCore", "Unilitix", "unilitix_flutter",
    )

    data class Frame(val image: String, val path: String, val frame: String, val system: Boolean)

    private val tombstoneFrame = Regex("""^(?:native:\s+)?#\d+\s+pc\s+([0-9a-fA-F]{1,16})\s+(.*?)\s*$""")
    private val buildId = Regex("""\s*\(BuildId:\s*[0-9a-fA-F]+\)$""")
    private val offset = Regex("""\s*\(offset 0x[0-9a-fA-F]+\)""")
    private val symOffset = Regex("""\+(?:0x[0-9a-fA-F]+|\d+)$""")
    private val appleFrame = Regex("""^\d+\s+(\S+)\s+0x[0-9a-fA-F]+\s+(.+?)(?:\s+\+\s+(\d+))?$""")
    private val dartFrame = Regex("""^#\d+\s+(.*)$""")
    private val dartVirt = Regex("""virt\s+([0-9a-f]+)""")

    fun isSystemImage(image: String, path: String): Boolean {
        if (path.isNotEmpty() && SYSTEM_PATH_PREFIXES.any { path.startsWith(it) }) return true
        if (image.isEmpty() || image == "???" || image.startsWith("[") || image.startsWith("<")) return true
        for (n in SYSTEM_IMAGES) {
            if (n.endsWith("*")) {
                if (image.startsWith(n.dropLast(1))) return true
            } else if (image == n) {
                return true
            }
        }
        return false
    }

    /** One native stack line, or null when it is not a frame. */
    fun normalise(rawLine: String): Frame? {
        val line = rawLine.trim()
        if (line.isEmpty()) return null
        tombstoneFrame.matchEntire(line)?.let { m ->
            val pc = m.groupValues[1].toULongOrNull(16) ?: return null
            var rest = buildId.replace(m.groupValues[2], "")
            rest = offset.replace(rest, "")
            var file = rest
            var sym = ""
            val open = rest.indexOf(" (")
            if (open >= 0 && rest.endsWith(")")) {
                file = rest.substring(0, open)
                sym = rest.substring(open + 2, rest.length - 1)
            }
            file = file.trim()
            var image = file
            val bang = image.lastIndexOf('!')
            if (bang >= 0) image = image.substring(bang + 1)
            image = baseName(image)
            sym = symOffset.replace(sym.trim(), "").trim()
            val frame = if (sym.isNotEmpty()) "$image $sym" else "$image+0x${pc.toString(16)}"
            return Frame(image, file, frame, isSystemImage(image, file))
        }
        appleFrame.matchEntire(line)?.let { m ->
            val image = m.groupValues[1]
            val symbol = m.groupValues[2].trim()
            val off = m.groupValues[3]
            val frame = if (symbol == image && off.isNotEmpty()) "$image+$off" else "$image $symbol"
            return Frame(image, "", frame, isSystemImage(image, ""))
        }
        dartFrame.matchEntire(line)?.let { m ->
            dartVirt.find(m.groupValues[1])?.let { return Frame("", "", "virt:${it.groupValues[1]}", false) }
        }
        return null
    }

    /** The first [Fingerprint.FRAMES] app frames of [stack], normalised. */
    fun appFrames(stack: String): List<String> {
        val out = ArrayList<String>(Fingerprint.FRAMES)
        for (raw in stack.split('\n')) {
            if (out.size == Fingerprint.FRAMES) break
            val f = normalise(raw) ?: continue
            if (f.system) continue
            out.add(f.frame)
        }
        return out
    }

    /** Go's path.Base: the last element, trailing slashes removed; "." for empty, "/" for all slashes. */
    private fun baseName(p: String): String {
        if (p.isEmpty()) return "."
        val trimmed = p.trimEnd('/')
        if (trimmed.isEmpty()) return "/"
        return trimmed.substring(trimmed.lastIndexOf('/') + 1)
    }
}
