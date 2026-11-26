package uk.hamdigital.ui

internal fun beginMomentaryPtt(start: () -> Boolean, currentSession: () -> Long): Long =
    if (start()) currentSession() else 0L
