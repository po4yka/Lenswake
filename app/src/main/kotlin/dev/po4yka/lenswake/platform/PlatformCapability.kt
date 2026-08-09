package dev.po4yka.lenswake.platform

enum class PlatformCapabilityCode {
    PIXEL_CAMERA_NOT_INSTALLED,
    SECURE_CAMERA_NOT_RESOLVABLE,
    RESOLVED_ACTIVITY_WRONG_PACKAGE,
    RESOLVED_ACTIVITY_NOT_EXPORTED,
    SECURE_CAMERA_DISPATCH_REJECTED,
    SECURE_CAMERA_DISPATCH_FAILED,
    NO_VERIFIED_WAKE_PATH,
}

sealed interface PlatformCapability<out T> {
    data class Available<T>(val value: T) : PlatformCapability<T>

    data class Unavailable(
        val code: PlatformCapabilityCode,
        val detail: String,
        val cause: Throwable? = null,
    ) : PlatformCapability<Nothing>
}
