package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.SelectorTemplateReference
import dev.po4yka.lenswake.core.SupportTier

private const val STANDARD_TEMPLATE_VERSION = 3
private const val TELEPHOTO_TEMPLATE_VERSION = 2

enum class PixelCameraTemplateKind(
    val reference: SelectorTemplateReference,
) {
    SEMANTIC_STANDARD(SelectorTemplateReference("pixel-7-semantic", STANDARD_TEMPLATE_VERSION)),
    SEMANTIC_TELEPHOTO(SelectorTemplateReference("pixel-8-pro-telephoto", TELEPHOTO_TEMPLATE_VERSION)),
}

data class PixelSystemBuildIdentity(
    val buildId: String,
    val incremental: String,
)

enum class PixelGlobalStableBuildSet(
    private val approvedBuilds: Set<PixelSystemBuildIdentity>,
) {
    JULY_2026_EXACT(
        setOf(
            PixelSystemBuildIdentity(
                buildId = "CP2A.260705.006",
                incremental = "15641320",
            ),
        ),
    ),
    ;

    fun accepts(buildId: String, incremental: String): Boolean =
        PixelSystemBuildIdentity(buildId, incremental) in approvedBuilds
}

data class SupportedPixelModel(
    val model: String,
    val codename: String,
    val template: PixelCameraTemplateKind,
    val supportTier: SupportTier,
    val globalStableBuildSet: PixelGlobalStableBuildSet,
    val certificationTarget: Boolean = false,
)

/** The release support boundary is deliberately fixed and never grows from a numeric range. */
object SupportedPixelModelRegistry {
    private val JULY = PixelGlobalStableBuildSet.JULY_2026_EXACT

    val entries: List<SupportedPixelModel> = listOf(
        experimental("Pixel 6", "oriole", PixelCameraTemplateKind.SEMANTIC_STANDARD, JULY),
        experimental("Pixel 6 Pro", "raven", PixelCameraTemplateKind.SEMANTIC_TELEPHOTO, JULY),
        experimental("Pixel 6a", "bluejay", PixelCameraTemplateKind.SEMANTIC_STANDARD, JULY),
        certificationTarget("Pixel 7", "panther", PixelCameraTemplateKind.SEMANTIC_STANDARD, JULY),
        experimental("Pixel 7 Pro", "cheetah", PixelCameraTemplateKind.SEMANTIC_TELEPHOTO, JULY),
        experimental("Pixel 7a", "lynx", PixelCameraTemplateKind.SEMANTIC_STANDARD, JULY),
        experimental("Pixel 8", "shiba", PixelCameraTemplateKind.SEMANTIC_STANDARD, JULY),
        certificationTarget(
            "Pixel 8 Pro",
            "husky",
            PixelCameraTemplateKind.SEMANTIC_TELEPHOTO,
            JULY,
        ),
        experimental("Pixel 8a", "akita", PixelCameraTemplateKind.SEMANTIC_STANDARD, JULY),
        experimental("Pixel 9", "tokay", PixelCameraTemplateKind.SEMANTIC_STANDARD, JULY),
        experimental("Pixel 9 Pro", "caiman", PixelCameraTemplateKind.SEMANTIC_TELEPHOTO, JULY),
        experimental("Pixel 9 Pro XL", "komodo", PixelCameraTemplateKind.SEMANTIC_TELEPHOTO, JULY),
        experimental("Pixel 9a", "tegu", PixelCameraTemplateKind.SEMANTIC_STANDARD, JULY),
        experimental("Pixel 10", "frankel", PixelCameraTemplateKind.SEMANTIC_TELEPHOTO, JULY),
        experimental("Pixel 10 Pro", "blazer", PixelCameraTemplateKind.SEMANTIC_TELEPHOTO, JULY),
        experimental("Pixel 10 Pro XL", "mustang", PixelCameraTemplateKind.SEMANTIC_TELEPHOTO, JULY),
        experimental("Pixel 10a", "stallion", PixelCameraTemplateKind.SEMANTIC_STANDARD, JULY),
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
        globalStableBuildSet: PixelGlobalStableBuildSet,
    ) = SupportedPixelModel(
        model,
        codename,
        template,
        SupportTier.EXPERIMENTAL,
        globalStableBuildSet,
        certificationTarget = true,
    )

    private fun experimental(
        model: String,
        codename: String,
        template: PixelCameraTemplateKind,
        globalStableBuildSet: PixelGlobalStableBuildSet,
    ) = SupportedPixelModel(
        model,
        codename,
        template,
        SupportTier.EXPERIMENTAL,
        globalStableBuildSet,
    )

    private const val EXPECTED_MODEL_COUNT = 17
    private const val GOOGLE_MANUFACTURER = "Google"
}
