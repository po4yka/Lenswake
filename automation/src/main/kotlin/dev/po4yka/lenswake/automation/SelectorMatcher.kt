package dev.po4yka.lenswake.automation

import dev.po4yka.lenswake.core.AutomationAction
import dev.po4yka.lenswake.core.NormalizedBounds
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.UiSelector
import dev.po4yka.lenswake.core.UiSelectorSet

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

enum class SelectorSignal {
    RESOURCE_ID,
    CONTENT_DESCRIPTION,
    TEXT,
    ROLE,
    CLICKABLE_STATE,
    SELECTED_STATE,
    EXPECTED_REGION,
}

sealed interface SelectorMatchResult {
    data class Match(
        val node: UiNodeSnapshot,
        val score: Int,
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
    ): SelectorMatchResult {
        val selectorSet = profile.targets[action] ?: return SelectorMatchResult.TargetNotConfigured
        return match(selectorSet, profile, nodes)
    }

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

        if (candidatesByNode.isEmpty()) return SelectorMatchResult.NoEligibleNodes

        val bestScore = candidatesByNode.maxOf(SelectorCandidate::score)
        val bestCandidates = candidatesByNode.filter { it.score == bestScore }
        if (bestScore < selectorSet.minimumScore) {
            return SelectorMatchResult.BelowThreshold(
                bestScore = bestScore,
                minimumScore = selectorSet.minimumScore,
                candidates = bestCandidates,
            )
        }
        if (bestCandidates.size > 1) {
            return SelectorMatchResult.Ambiguous(bestCandidates, bestScore)
        }

        val best = bestCandidates.single()
        return SelectorMatchResult.Match(
            node = best.node,
            score = best.score,
            selectorIndex = best.selectorIndex,
            matchedSignals = best.matchedSignals,
        )
    }

    private fun score(
        node: UiNodeSnapshot,
        selector: UiSelector,
        selectorIndex: Int,
        profile: PixelCameraProfile,
    ): SelectorCandidate? {
        if (!node.visible || !node.enabled) return null
        if (node.packageName != selector.packageName || node.packageName != profile.environment.cameraPackage) return null
        if (selector.requiresClickable && !node.clickable) return null
        if (selector.expectedSelected != null && node.selected != selector.expectedSelected) return null

        var score = 0
        val signals = buildSet {
            if (selector.resourceId != null && selector.resourceId == node.resourceId) {
                score += RESOURCE_ID_SCORE
                add(SelectorSignal.RESOURCE_ID)
            }
            if (
                selector.contentDescription != null &&
                selector.contentDescription == node.contentDescription
            ) {
                score += CONTENT_DESCRIPTION_SCORE
                add(SelectorSignal.CONTENT_DESCRIPTION)
            }
            if (selector.text != null && selector.text == node.text) {
                score += TEXT_SCORE
                add(SelectorSignal.TEXT)
            }
            if (selector.role != null && selector.role == node.role) {
                score += ROLE_SCORE
                add(SelectorSignal.ROLE)
            }
            if (selector.requiresClickable && node.clickable) {
                score += STATE_SCORE
                add(SelectorSignal.CLICKABLE_STATE)
            }
            if (selector.expectedSelected != null && node.selected == selector.expectedSelected) {
                score += SELECTED_STATE_SCORE
                add(SelectorSignal.SELECTED_STATE)
            }
            val expectedRegion = selector.expectedRegion
            if (expectedRegion != null && node.bounds?.centerIsInside(expectedRegion) == true) {
                score += REGION_SCORE
                add(SelectorSignal.EXPECTED_REGION)
            }
        }
        return SelectorCandidate(node, score, selectorIndex, signals)
    }

    private fun NormalizedBounds.centerIsInside(region: NormalizedBounds): Boolean {
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        return centerX in region.left..region.right && centerY in region.top..region.bottom
    }

    private companion object {
        const val RESOURCE_ID_SCORE = 100
        const val CONTENT_DESCRIPTION_SCORE = 60
        const val TEXT_SCORE = 30
        const val ROLE_SCORE = 20
        const val STATE_SCORE = 10
        const val SELECTED_STATE_SCORE = 15
        const val REGION_SCORE = 10
    }
}
