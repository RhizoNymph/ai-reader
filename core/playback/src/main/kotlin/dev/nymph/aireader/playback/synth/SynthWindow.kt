package dev.nymph.aireader.playback.synth

/**
 * Pure windowing decisions for the synthesis scheduler. Kept free of
 * coroutines and I/O so the policy is exhaustively unit-testable.
 */

/**
 * The next index the scheduler should synthesize, or null when there is
 * nothing to do right now.
 *
 * Priority:
 * 1. The first uncached index in the playhead window
 *    `[playhead, min(playhead + lookahead, size))`. The window is always
 *    filled regardless of [maxAheadBytes] — otherwise playback would stall.
 * 2. When the window is fully cached and [usedBytes] is under [maxAheadBytes],
 *    the first uncached index beyond the window (opportunistic forward fill).
 *
 * Indices behind the playhead are never considered; a backward seek moves the
 * window and re-covers them.
 */
internal fun nextTarget(
    playhead: Int,
    size: Int,
    lookahead: Int,
    usedBytes: Long,
    maxAheadBytes: Long,
    isCached: (Int) -> Boolean,
): Int? {
    val windowEnd = minOf(playhead + lookahead, size)
    for (i in playhead until windowEnd) {
        if (!isCached(i)) return i
    }
    if (usedBytes >= maxAheadBytes) return null
    for (i in windowEnd until size) {
        if (!isCached(i)) return i
    }
    return null
}

/**
 * The largest index `j >= playhead - 1` such that every index in
 * `[playhead, j]` is cached. Returns `playhead - 1` when the playhead chunk
 * itself is uncached (nothing playable from here yet).
 */
internal fun cachedThrough(playhead: Int, size: Int, isCached: (Int) -> Boolean): Int {
    var i = playhead
    while (i < size && isCached(i)) i++
    return i - 1
}
