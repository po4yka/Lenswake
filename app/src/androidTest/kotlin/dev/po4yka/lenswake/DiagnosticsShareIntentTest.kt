package dev.po4yka.lenswake

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsShareIntentTest {
    @Test
    fun chooserCarriesPlainTextDiagnostics() {
        val chooser = diagnosticsShareIntent(
            report = "diagnostic report",
            subject = "Lenswake diagnostics",
            chooserTitle = "Share diagnostics",
        )
        val target = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertNotNull(target)
        assertEquals(Intent.ACTION_SEND, target?.action)
        assertEquals("text/plain", target?.type)
        assertEquals("Lenswake diagnostics", target?.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("diagnostic report", target?.getStringExtra(Intent.EXTRA_TEXT))
    }
}
