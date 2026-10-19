package dev.dsh.mobile.data

/**
 * How long to wait before the next poll.
 *
 * Both polling loops in the app ask the same question with different clocks, so the
 * arithmetic lives here where it can be tested instead of inside the view model: a
 * phone that retries a dead host every two seconds is a battery story, and one that
 * backs off too eagerly stops noticing that the host came back.
 *
 * The failure curve doubles from [activeDelay], is capped at [cap], and saturates
 * after four doublings so a long outage cannot overflow the shift.
 *
 * @param failures consecutive failed polls, zero when the last one worked.
 * @param busy whether there is something to watch right now (a running turn, or a
 *   list that contains one).
 * @param activeDelay the interval while busy.
 * @param idleDelay the interval while nothing is happening.
 * @param cap the slowest interval a failure streak may reach.
 * @return milliseconds to wait.
 */
fun pollDelay(
    failures: Int,
    busy: Boolean,
    activeDelay: Long,
    idleDelay: Long,
    cap: Long = 60_000,
): Long {
    if (failures > 0) {
        val doublings = minOf(failures, MAX_DOUBLINGS)
        return minOf(cap, activeDelay shl doublings)
    }
    return if (busy) activeDelay else idleDelay
}

/** Doublings allowed before the delay saturates and the cap does the rest. */
const val MAX_DOUBLINGS = 4
