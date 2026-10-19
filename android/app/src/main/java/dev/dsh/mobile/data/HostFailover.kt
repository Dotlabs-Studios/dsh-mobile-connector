package dev.dsh.mobile.data

/**
 * The addresses to try when the current one stops answering.
 *
 * The plugin prints every address a phone can dial — the LAN one and, when
 * Tailscale is up, the tailnet one — and they are two ways to the same harness. A
 * phone that walks out of the house leaves the LAN address behind; a phone that
 * comes home finds the tailnet one slower. Knowing both is what makes either
 * survivable without re-pairing.
 *
 * The list is deduplicated and ordered, and it never contains the address that just
 * failed: retrying the same one is what the poll backoff already does.
 *
 * @param current the address in use, or null when nothing is paired.
 * @param alternates every known address, in the order the host reported them.
 * @return the candidates, best first.
 */
fun failoverCandidates(current: String?, alternates: List<String>): List<String> {
    val seen = mutableSetOf<String>()
    return alternates.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != current && seen.add(it) }
        .toList()
}

/**
 * The address to fall back to after [current] fails, or null when there is none.
 *
 * @param current the address that just failed.
 * @param alternates every known address.
 * @param tried the addresses already attempted in this failure streak.
 * @return the next candidate, if any.
 */
fun nextFailoverHost(current: String?, alternates: List<String>, tried: Set<String>): String? =
    failoverCandidates(current, alternates).firstOrNull { it !in tried }
