package dev.po4yka.lenswake.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SupportedPixelModelRegistryTest {
    @Test
    fun `registry contains exactly the fixed 17 non-folding Pixel 6 through 10a models`() {
        assertEquals(17, SupportedPixelModelRegistry.entries.size)
        assertEquals(
            setOf(
                "Pixel 6",
                "Pixel 6 Pro",
                "Pixel 6a",
                "Pixel 7",
                "Pixel 7 Pro",
                "Pixel 7a",
                "Pixel 8",
                "Pixel 8 Pro",
                "Pixel 8a",
                "Pixel 9",
                "Pixel 9 Pro",
                "Pixel 9 Pro XL",
                "Pixel 9a",
                "Pixel 10",
                "Pixel 10 Pro",
                "Pixel 10 Pro XL",
                "Pixel 10a",
            ),
            SupportedPixelModelRegistry.entries.mapTo(linkedSetOf()) { it.model },
        )
    }

    @Test
    fun `Pixel 7 and Pixel 8 Pro are certification targets but remain experimental before release acceptance`() {
        assertEquals(
            setOf("Pixel 7", "Pixel 8 Pro"),
            SupportedPixelModelRegistry.entries
                .filter(SupportedPixelModel::certificationTarget)
                .mapTo(linkedSetOf()) { it.model },
        )
        assertTrue(SupportedPixelModelRegistry.entries.all {
            it.supportTier == dev.po4yka.lenswake.core.SupportTier.EXPERIMENTAL
        })
    }

    @Test
    fun `fold tablet pre-six and future models are rejected`() {
        listOf(
            "Pixel Fold" to "felix",
            "Pixel 9 Pro Fold" to "comet",
            "Pixel Tablet" to "tangorpro",
            "Pixel 5a" to "barbet",
            "Pixel 11" to "unknown",
        ).forEach { (model, codename) ->
            assertNull(SupportedPixelModelRegistry.find("Google", model, codename))
        }
    }

    @Test
    fun `model and codename must identify the same registry entry`() {
        assertNull(SupportedPixelModelRegistry.find("Google", "Pixel 7", "husky"))
        assertNull(SupportedPixelModelRegistry.find("Another", "Pixel 7", "panther"))
    }

    @Test
    fun `selector templates use the provenance-corrected version`() {
        assertEquals(2, PixelCameraTemplateKind.SEMANTIC_STANDARD.reference.version)
        assertEquals(2, PixelCameraTemplateKind.SEMANTIC_TELEPHOTO.reference.version)
    }
}
