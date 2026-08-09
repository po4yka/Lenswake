package dev.po4yka.lenswake.privileged

data class PixelPoint(val x: Int, val y: Int)

data class ActivityLaunchRequest(
    val action: String,
    val packageName: String,
    val componentName: String?,
    val flags: Int,
)

enum class PrivilegedUnavailableReason {
    NOT_CONFIGURED,
}

sealed interface PrivilegedResult {
    data object Dispatched : PrivilegedResult

    data class Unavailable(
        val reason: PrivilegedUnavailableReason,
        val detail: String,
    ) : PrivilegedResult
}

/** Platform-neutral boundary; Shizuku types and shell commands must stay behind this interface. */
interface PrivilegedBridge {
    suspend fun availability(): PrivilegedResult

    suspend fun wakeDevice(): PrivilegedResult

    suspend fun startActivity(request: ActivityLaunchRequest): PrivilegedResult

    suspend fun inputTap(point: PixelPoint): PrivilegedResult
}

class UnavailablePrivilegedBridge : PrivilegedBridge {
    private val result = PrivilegedResult.Unavailable(
        reason = PrivilegedUnavailableReason.NOT_CONFIGURED,
        detail = "No privileged provider is configured; Shizuku is optional infrastructure",
    )

    override suspend fun availability(): PrivilegedResult = result

    override suspend fun wakeDevice(): PrivilegedResult = result

    override suspend fun startActivity(request: ActivityLaunchRequest): PrivilegedResult = result

    override suspend fun inputTap(point: PixelPoint): PrivilegedResult = result
}
