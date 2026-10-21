package com.videoeditor.feature.export

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ExportFileSizeTest {
    @Test fun smallOutputsAndUnitBoundariesRetainMeaningfulSizes() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("0 B", formatBytes(0))
            assertEquals("512 B", formatBytes(512))
            assertEquals("1.0 KB", formatBytes(1024))
            assertEquals("256.0 KB", formatBytes(262144))
            assertEquals("1.0 MB", formatBytes(1048576))
            assertEquals("1.5 MB", formatBytes(1572864))
            assertEquals("1.0 GB", formatBytes(1073741824))
        } finally { Locale.setDefault(previous) }
    }
}
