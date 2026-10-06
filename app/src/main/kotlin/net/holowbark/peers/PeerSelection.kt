package net.holowbark.peers

/**
 * The peers the tunnel dials, split by who chose them. [manual] are the ones the
 * user typed or ticked, and they stay through every mode change. [derived] are
 * the ones the app chose: from the user's country, or by searching.
 */
data class PeerSelection(
    val manual: Set<String> = emptySet(),
    val derived: Set<String> = emptySet(),
) {
    val all: Set<String> get() = manual + derived

    /** A peer the user picks is theirs, even when the app had already chosen it. */
    fun withManual(addresses: Collection<String>) =
        copy(manual = manual + addresses, derived = derived - addresses.toSet())

    fun without(addresses: Collection<String>) =
        copy(manual = manual - addresses.toSet(), derived = derived - addresses.toSet())

    fun withDerived(addresses: Collection<String>) =
        copy(derived = addresses.toSet() - manual)
}
