package dev.nymph.aireader.playback.export

/**
 * The sample rate an exported audiobook is written at: the most common rate
 * across the document's chunks, so the (rare) synthesis-failure fallback
 * silence chunks get resampled toward the real speech rate and never the
 * other way around. Ties break to the higher rate to avoid losing bandwidth.
 */
fun chooseTargetRate(rates: Iterable<Int>): Int {
    val counts = rates.groupingBy { it }.eachCount()
    require(counts.isNotEmpty()) { "cannot choose a target rate from no chunks" }
    return counts.entries
        .maxWith(compareBy({ it.value }, { it.key }))
        .key
}
