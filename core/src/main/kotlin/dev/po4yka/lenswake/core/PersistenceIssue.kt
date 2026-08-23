package dev.po4yka.lenswake.core

/** Persisted data domains whose rows can be individually corrupted. */
enum class PersistenceDomain {
    SCHEDULE,
    EXECUTION_SESSION,
    EXECUTION_EVENT,
}

enum class PersistenceIssueCode {
    CORRUPT_ENTRY,
}

/** A persisted row that could not be reconstructed without weakening validation. */
data class PersistenceIssue(
    /** Raw database key: it remains representable even when the persisted id is itself invalid. */
    val domain: PersistenceDomain,
    val entryKey: String,
    val code: PersistenceIssueCode,
)
