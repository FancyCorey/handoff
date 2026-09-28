package dev.handoff.app.feature.diagnostics

import dev.handoff.core.mesh.security.Crypto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportTest {
    private val key = Crypto.b64(Crypto.generateEcKeyPair().public.encoded)

    private val report = DiagnosticsReport.build(
        ReportInput(
            appVersion = "0.1.0",
            androidRelease = "15",
            sdkInt = 35,
            manufacturer = "samsung",
            model = "SM-X",
            sections = listOf(
                "Preferred headset" to listOf("Local mapping" to "AA:BB:CC:DD:EE:FF", "Bond state" to "BONDED"),
                "Leaky" to listOf("key" to key, "link" to "handoff://pair?d=eyJ2IjoxLCJwZWVySWQiOiJhIn0"),
            ),
            events = listOf("CONNECT_FAILED detail=connect(11:22:33:44:55:66) returned false"),
        ),
    )

    @Test
    fun `full bluetooth addresses never appear`() {
        assertFalse(report.contains("AA:BB:CC"))
        assertFalse(report.contains("11:22:33"))
        assertTrue(report.contains("**:**:**:**:EE:FF"))
        assertTrue(report.contains("**:**:**:**:55:66"))
    }

    @Test
    fun `keys and pairing links are redacted`() {
        assertFalse(report.contains(key))
        assertFalse(report.contains("handoff://pair?d=eyJ"))
        assertTrue(report.contains("[redacted]"))
    }

    @Test
    fun `useful context is kept`() {
        assertTrue(report.contains("Android: 15 (API 35)"))
        assertTrue(report.contains("Bond state: BONDED"))
    }
}
