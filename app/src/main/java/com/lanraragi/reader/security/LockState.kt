package com.lanraragi.reader.security

/**
 * Whether the app lock is on, as one value ([AppLockCredentials.lockState]).
 * Every lock gate reads [isLocked]: it is true for [Unknown] too, so a
 * keystore failure can make a locked app look locked, never unlocked
 * (fail closed, audit 2026-10-06c SEC-01).
 */
sealed interface LockState {

    val isLocked: Boolean

    /** No pattern is set. A definite answer, which AppLockGate may keep for the foreground session. */
    data object None : LockState {
        override val isLocked: Boolean get() = false
    }

    /**
     * A pattern is set. [keystoreBound]: its hash is also wrapped by the
     * AndroidKeyStore key, so fingerprint unlock can decrypt it.
     */
    data class Pattern(val keystoreBound: Boolean) : LockState {
        override val isLocked: Boolean get() = true
    }

    /**
     * Cannot be told: no plain mirror yet, a legacy store still to be
     * migrated, and the secure store did not open in time. Treated as locked.
     */
    data object Unknown : LockState {
        override val isLocked: Boolean get() = true
    }
}
