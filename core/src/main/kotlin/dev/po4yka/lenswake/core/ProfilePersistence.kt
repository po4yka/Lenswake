package dev.po4yka.lenswake.core

enum class ProfilePersistenceIssueCode {
    CORRUPT_ENTRY,
}

/** A persisted profile row that could not be reconstructed without weakening validation. */
data class ProfilePersistenceIssue(
    /** Raw database key: it remains representable even when the persisted id is itself invalid. */
    val entryKey: String,
    val code: ProfilePersistenceIssueCode,
)

class CorruptProfileEntryException(
    val issue: ProfilePersistenceIssue,
    cause: Throwable,
) : IllegalStateException(
    "Persisted automation profile entry is corrupt",
    cause,
)
