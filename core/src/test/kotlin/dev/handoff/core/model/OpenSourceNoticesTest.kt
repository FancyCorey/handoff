package dev.handoff.core.model

import dev.handoff.core.text.OpenSourceNotices
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSourceNoticesTest {
    @Test
    fun `bundled notices carry every required notice and full license text`() {
        val text = OpenSourceNotices.text
        listOf(
            "Copyright (c) 2026 Felip6499", // PodSwitch MIT notice
            "Copyright (c) 2004-2023 QOS.ch", // SLF4J MIT notice
            "Copyright (c) 2011 Google Inc.", // Skia BSD notice
            "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION", // Apache 2.0 full text
            "END OF TERMS AND CONDITIONS",
            "Permission is hereby granted, free of charge",
            "JmDNS", "JNA", "Koin", "ZXing", "Okio", "Stately", "Skiko", "OpenJDK",
        ).forEach { assertTrue("missing: $it", text.contains(it)) }
    }

    @Test
    fun `reflow joins wrapped lines but keeps list items and headings apart`() {
        val text = OpenSourceNotices.readable
        assertTrue(text.contains("Permission is hereby granted, free of charge, to any person obtaining a copy of this software"))
        assertTrue(text.contains("\n- Koin: Apache-2.0"))
        assertTrue(text.contains("\nCOMPONENTS\n"))
    }
}
