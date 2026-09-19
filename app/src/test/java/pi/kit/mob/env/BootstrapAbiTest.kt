package pi.kit.mob.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ABI a build and a device have to agree on, and what is said when they do not.
 *
 * This is the rule behind a report that read "clicking Run in Android Studio says
 * the runtime image is missing". Nothing was missing: an APK carries exactly one
 * ABI's image, Android Studio's default variant is `arm64Debug`, and the emulator
 * is x86_64 — so the app asked for `x86_64`, the head of `SUPPORTED_ABIS`, and the
 * image in the APK was `arm64-v8a`.
 *
 * Both functions are pure for that reason: a build/device mismatch cannot be
 * produced in a unit test any other way.
 */
class BootstrapAbiTest {

    /** An arm64 phone. */
    private val arm64Device = listOf("arm64-v8a", "armeabi-v7a", "armeabi")

    /** An x86_64 emulator without ARM translation. */
    private val x64Device = listOf("x86_64", "x86")

    /**
     * The reported case: the same emulator *with* the native bridge, so it lists
     * the ARM ABI it can translate. The device still decides, and the arm64 image
     * is still the wrong one — see `chooseAbi` for why.
     */
    private val bridgedEmulator = listOf("x86_64", "x86", "arm64-v8a")

    @Test
    fun `each device gets its own abi`() {
        assertEquals(ARCH_ARM64, chooseAbi(arm64Device))
        assertEquals(ARCH_X86_64, chooseAbi(x64Device))
    }

    @Test
    fun `a translating emulator still asks for its own abi`() {
        assertEquals(ARCH_X86_64, chooseAbi(bridgedEmulator))
    }

    @Test
    fun `a device that reports nothing still produces a name`() {
        assertEquals(ARCH_ARM64, chooseAbi(emptyList()))
    }

    @Test
    fun `the message names both sides of a mismatch`() {
        val message = missingImageMessage(ARCH_X86_64, "runtime/x86_64", listOf(ARCH_ARM64))

        assertTrue("says which image is missing", message.contains(ARCH_X86_64))
        assertTrue("says which image the build carries", message.contains("It carries $ARCH_ARM64"))
        assertTrue("names the fix", message.contains("Select Build Variant"))
    }

    @Test
    fun `an apk with no image at all says so`() {
        val message = missingImageMessage(ARCH_X86_64, "runtime/x86_64", emptyList())

        assertTrue(message.contains("no runtime image at all"))
        assertTrue(message.contains("tools/build-runtime-image.py"))
    }
}
