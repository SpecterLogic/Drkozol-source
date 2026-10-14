package com.v2ray.ang.service

/** An unlock resumes only the same explicit choice that this watcher stopped on lock. */
internal fun shouldResumeOnUnlock(pendingGuid: String?, selectedGuid: String?, enabled: Boolean, permissionReady: Boolean): Boolean =
    enabled && permissionReady && !pendingGuid.isNullOrBlank() && pendingGuid == selectedGuid

internal fun isUnlockedScreen(interactive: Boolean, keyguardLocked: Boolean): Boolean = interactive && !keyguardLocked

/** A repeated owned stop remains owned; an unrelated manual stop always cancels resume. */
internal fun isAutomationPause(origin: String?, pauseOrigin: String): Boolean =
    pauseOrigin.isNotBlank() && origin == pauseOrigin
