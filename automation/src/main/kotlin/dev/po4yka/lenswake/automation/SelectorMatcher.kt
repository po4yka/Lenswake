package dev.po4yka.lenswake.automation

import dev.po4yka.lenswake.core.AutomationAction
import dev.po4yka.lenswake.core.NormalizedBounds
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.UiSelector
import dev.po4yka.lenswake.core.UiSelectorSet

private const val RESOURCE_ID_SCORE = 100
private const val CONTENT_DESCRIPTION_SCORE = 60
private const val TEXT_SCORE = 30
private const val ROLE_SCORE = 20
private const val STATE_SCORE = 10
private const val SELECTED_STATE_SCORE = 15
private const val CHECKED_STATE_SCORE = 15
private const val REGION_SCORE = 10

data class UiNodeSnapshot(
    val id: String,
    val packageName: String?,
    val resourceId: String?,
    val role: String?,
    val contentDescription: String?,
    val text: String?,
    val bounds: NormalizedBounds?,
    val visible: Boolean,
    val clickable: Boolean,
    val selected: Boolean?,
    val checkable: Boolean = false,
    val checked: Boolean? = null,
    val enabled: Boolean,
) {
    init {
        require(id.isNotBlank()) { "UI node identifier must not be blank" }
    }
}

data class SelectorCandidate(
    val node: UiNodeSnapshot,
    val score: Int,
    val selectorIndex: Int,
    val matchedSignals: Set<SelectorSignal>,
)

enum class SelectorSignal(
    internal val score: Int,
    internal val isMeaningfulDiscriminant: Boolean,
) {
    RESOURCE_ID(RESOURCE_ID_SCORE, isMeaningfulDiscriminant = true),
    CONTENT_DESCRIPTION(CONTENT_DESCRIPTION_SCORE, isMeaningfulDiscriminant = true),
    TEXT(TEXT_SCORE, isMeaningfulDiscriminant = true),
    ROLE(ROLE_SCORE, isMeaningfulDiscriminant = true),
    CLICKABLE_STATE(STATE_SCORE, isMeaningfulDiscriminant = false),
    SELECTED_STATE(SELECTED_STATE_SCORE, isMeaningfulDiscriminant = false),
    CHECKED_STATE(CHECKED_STATE_SCORE, isMeaningfulDiscriminant = false),
    EXPECTED_REGION(REGION_SCORE, isMeaningfulDiscriminant = true),
}

sealed interface SelectorMatchResult {
    data class Match(
        val node: UiNodeSnapshot,
        val score: Int,
        val minimumScore: Int,
        val selectorIndex: Int,
        val matchedSignals: Set<SelectorSignal>,
    ) : SelectorMatchResult

    data class Ambiguous(
        val candidates: List<SelectorCandidate>,
        val score: Int,
    ) : SelectorMatchResult

    data class BelowThreshold(
        val bestScore: Int,
        val minimumScore: Int,
        val candidates: List<SelectorCandidate>,
    ) : SelectorMatchResult

    data object NoEligibleNodes : SelectorMatchResult

    data object TargetNotConfigured : SelectorMatchResult
}

class SelectorMatcher {
    fun match(
        action: AutomationAction,
        profile: PixelCameraProfile,
        nodes: List<UiNodeSnapshot>,
    ): SelectorMatchResult = profile.targets[action]
        ?.let { selectorSet -> match(selectorSet, profile, nodes) }
        ?: SelectorMatchResult.TargetNotConfigured

    fun match(
        selectorSet: UiSelectorSet,
        profile: PixelCameraProfile,
        nodes: List<UiNodeSnapshot>,
    ): SelectorMatchResult {
        val candidatesByNode = buildMap<String, SelectorCandidate> {
            nodes.forEach { node ->
                selectorSet.selectors.forEachIndexed { index, selector ->
                    val candidate = score(node, selector, index, profile) ?: return@forEachIndexed
                    val previous = get(node.id)
                    if (previous == null || candidate.score > previous.score) {
                        put(node.id, candidate)
                    }
                }
            }
        }.values.toList()

        return if (candidatesByNode.isEmpty()) {
            SelectorMatchResult.NoEligibleNodes
        } else {
            resultForCandidates(candidatesByNode, selectorSet.minimumScore)
        }
    }

    private fun resultForCandidates(
        candidates: List<SelectorCandidate>,
        minimumScore: Int,
    ): SelectorMatchResult {
        val bestScore = candidates.maxOf(SelectorCandidate::score)
        val bestCandidates = candidates.filter { it.score == bestScore }
        return when {
            bestScore < minimumScore -> SelectorMatchResult.BelowThreshold(
                bestScore = bestScore,
                minimumScore = minimumScore,
                candidates = bestCandidates,
            )

            bestCandidates.size > 1 -> SelectorMatchResult.Ambiguous(bestCandidates, bestScore)
            else -> bestCandidates.single().toMatchResult(minimumScore)
        }
    }

    private fun score(
        node: UiNodeSnapshot,
        selector: UiSelector,
        selectorIndex: Int,
        profile: PixelCameraProfile,
    ): SelectorCandidate? {
        val signals = if (node.isEligibleFor(selector, profile.environment.cameraPackage)) {
            matchingSignals(node, selector)
        } else {
            emptySet()
        }
        return signals
            .takeIf { matchedSignals ->
                matchedSignals.any { signal -> signal.isMeaningfulDiscriminant }
            }
            ?.let { matchedSignals ->
                SelectorCandidate(
                    node = node,
                    score = matchedSignals.sumOf { signal -> signal.score },
                    selectorIndex = selectorIndex,
                    matchedSignals = matchedSignals,
                )
            }
    }

    private fun UiNodeSnapshot.isEligibleFor(
        selector: UiSelector,
        cameraPackage: String,
    ): Boolean =
        visible &&
            enabled &&
            packageName == selector.packageName &&
            packageName == cameraPackage &&
            (!selector.requiresClickable || clickable) &&
            (selector.expectedSelected == null || selected == selector.expectedSelected) &&
            (
                selector.expectedChecked == null ||
                    (checkable && checked == selector.expectedChecked)
            )

    private fun matchingSignals(
        node: UiNodeSnapshot,
        selector: UiSelector,
    ): Set<SelectorSignal> = buildSet {
        if (configuredStringMatches(selector.resourceId, node.resourceId)) {
            add(SelectorSignal.RESOURCE_ID)
        }
        if (configuredStringMatches(selector.contentDescription, node.contentDescription)) {
            add(SelectorSignal.CONTENT_DESCRIPTION)
        }
        if (configuredStringMatches(selector.text, node.text)) {
            add(SelectorSignal.TEXT)
        }
        if (configuredStringMatches(selector.role, node.role)) {
            add(SelectorSignal.ROLE)
        }
        if (selector.requiresClickable && node.clickable) {
            add(SelectorSignal.CLICKABLE_STATE)
        }
        if (configuredStateMatches(selector.expectedSelected, node.selected)) {
            add(SelectorSignal.SELECTED_STATE)
        }
        if (configuredStateMatches(selector.expectedChecked, node.checked)) {
            add(SelectorSignal.CHECKED_STATE)
        }
        val expectedRegion = selector.expectedRegion
        if (expectedRegion != null && node.bounds?.centerIsInside(expectedRegion) == true) {
            add(SelectorSignal.EXPECTED_REGION)
        }
    }

    private fun configuredStringMatches(expected: String?, actual: String?): Boolean =
        !expected.isNullOrBlank() && expected == actual

    private fun configuredStateMatches(expected: Boolean?, actual: Boolean?): Boolean =
        expected != null && expected == actual

    private fun SelectorCandidate.toMatchResult(minimumScore: Int) = SelectorMatchResult.Match(
        node = node,
        score = score,
        minimumScore = minimumScore,
        selectorIndex = selectorIndex,
        matchedSignals = matchedSignals,
    )

    private fun NormalizedBounds.centerIsInside(region: NormalizedBounds): Boolean {
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        return centerX in region.left..region.right && centerY in region.top..region.bottom
    }
}
