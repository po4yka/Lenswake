package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.SelectorTemplateReference
import dev.po4yka.lenswake.core.SupportTier

enum class PixelCameraTemplateKind(
    val reference: SelectorTemplateReference,
) {
    SEMANTIC_STANDARD(SelectorTemplateReference("pixel-7-semantic", 1)),
    SEMANTIC_TELEPHOTO(SelectorTemplateReference("pixel-8-pro-telephoto", 1)),
}

enum class PixelGlobalStableBuildWindow(
    private val approvedBuildIds: Set<String>,
) {
    JULY_2026(setOf("CP2A.260705.006")),
    JULY_AUGUST_2026(setOf("CP2A.260705.006", "CP2A.260805.005")),
    ;

    fun accepts(buildId: String): Boolean = buildId in approvedBuildIds
}

data class SupportedPixelModel(
    val model: String,
    val codename: String,
    val template: PixelCameraTemplateKind,
    val supportTier: SupportTier,
    val globalStableBuildWindow: PixelGlobalStableBuildWindow,
    val certificationTarget: Boolean = false,
)

/** The release support boundary is deliberately fixed and never grows from a numeric range. */
object SupportedPixelModelRegistry {
    private val JULY = PixelGlobalStableBuildWindow.JULY_2026
    private val JULY_AUGUST = PixelGlobalStableBuildWindow.JULY_AUGUST_2026

    val entries: List<SupportedPixelModel> = listOf(
        experimental("Pixel 6", "oriole", PixelCameraTemplateKind.SEMANTIC_STANDARD, JULY),
        experimental("Pixel 6 Pro", "raven", PixelCameraTemplateKind.SEMANTIC_TELEPHOTO, JULY),
        experimental("Pixel 6a", "bluejay", PixelCameraTemplateKind.SEMANTIC_STANDARD, JULY),
        certificationTarget("Pixel 7", "panther", PixelCameraTemplateKind.SEMANTIC_STANDARD, JULY),
        experimental("Pixel 7 Pro", "cheetah", PixelCameraTemplateKind.SEMANTIC_TELEPHOTO, JULY),
        experimental("Pixel 7a", "lynx", PixelCameraTemplateKind.SEMANTIC_STANDARD, JULY),
        experimental("Pixel 8", "shiba", PixelCameraTemplateKind.SEMANTIC_STANDARD, JULY_AUGUST),
        certificationTarget(
            "Pixel 8 Pro",
            "husky",
            PixelCameraTemplateKind.SEMANTIC_TELEPHOTO,
            JULY_AUGUST,
        ),
        experimental("Pixel 8a", "akita", PixelCameraTemplateKind.SEMANTIC_STANDARD, JULY_AUGUST),
        experimental("Pixel 9", "tokay", PixelCameraTemplateKind.SEMANTIC_STANDARD, JULY_AUGUST),
        experimental("Pixel 9 Pro", "caiman", PixelCameraTemplateKind.SEMANTIC_TELEPHOTO, JULY_AUGUST),
        experimental("Pixel 9 Pro XL", "komodo", PixelCameraTemplateKind.SEMANTIC_TELEPHOTO, JULY_AUGUST),
        experimental("Pixel 9a", "tegu", PixelCameraTemplateKind.SEMANTIC_STANDARD, JULY_AUGUST),
        experimental("Pixel 10", "frankel", PixelCameraTemplateKind.SEMANTIC_TELEPHOTO, JULY_AUGUST),
        experimental("Pixel 10 Pro", "blazer", PixelCameraTemplateKind.SEMANTIC_TELEPHOTO, JULY_AUGUST),
        experimental("Pixel 10 Pro XL", "mustang", PixelCameraTemplateKind.SEMANTIC_TELEPHOTO, JULY_AUGUST),
        experimental("Pixel 10a", "stallion", PixelCameraTemplateKind.SEMANTIC_STANDARD, JULY_AUGUST),
    ).also { registry ->
        require(registry.size == EXPECTED_MODEL_COUNT)
        require(registry.map { it.model }.distinct().size == registry.size)
        require(registry.map { it.codename }.distinct().size == registry.size)
    }

    fun find(
        manufacturer: String,
        model: String,
        codename: String,
    ): SupportedPixelModel? {
        if (manufacturer != GOOGLE_MANUFACTURER) return null
        return entries.singleOrNull { it.model == model && it.codename == codename }
    }

    private fun certificationTarget(
        model: String,
        codename: String,
        template: PixelCameraTemplateKind,
        globalStableBuildWindow: PixelGlobalStableBuildWindow,
    ) = SupportedPixelModel(
        model,
        codename,
        template,
        SupportTier.EXPERIMENTAL,
        globalStableBuildWindow,
        certificationTarget = true,
    )

    private fun experimental(
        model: String,
        codename: String,
        template: PixelCameraTemplateKind,
        globalStableBuildWindow: PixelGlobalStableBuildWindow,
    ) = SupportedPixelModel(
        model,
        codename,
        template,
        SupportTier.EXPERIMENTAL,
        globalStableBuildWindow,
    )

    private const val EXPECTED_MODEL_COUNT = 17
    private const val GOOGLE_MANUFACTURER = "Google"
}
