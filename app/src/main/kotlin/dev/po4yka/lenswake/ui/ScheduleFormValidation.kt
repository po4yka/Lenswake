package dev.po4yka.lenswake.ui

internal data class ScheduleFormValidation(
    val nameError: String? = null,
    val timingError: String? = null,
    val profileError: String? = null,
    val captureError: String? = null,
) {
    val canSubmit: Boolean
        get() = nameError == null && timingError == null && profileError == null && captureError == null
}
