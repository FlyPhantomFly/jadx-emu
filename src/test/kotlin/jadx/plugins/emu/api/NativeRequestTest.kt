package jadx.plugins.emu.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File

class NativeRequestTest {
    private val apk = File("app.apk")

    @Test
    fun theAbiDefaultsToArm64() {
        assertEquals("arm64-v8a", NativeRequest(apk, setOf("Lcom/foo/Bar;->a"), listOf("libfoo.so")).abi)
    }

    @Test
    fun aRequestWithNoMethodsIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { NativeRequest(apk, emptySet(), listOf("libfoo.so")) }
    }

    @Test
    fun aRequestWithNoLibrariesIsRefusedSoNothingLoadsEverything() {
        assertThrows(IllegalArgumentException::class.java) { NativeRequest(apk, setOf("Lcom/foo/Bar;->a"), emptyList()) }
    }

    @Test
    fun aBlankAbiIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { NativeRequest(apk, setOf("Lcom/foo/Bar;->a"), listOf("libfoo.so"), abi = " ") }
    }
}
