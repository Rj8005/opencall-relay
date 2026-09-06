package com.opencall.relay.offline

/**
 * PART 1.4: holds every [MeshTransport] this device currently has active and
 * exposes a single merged peer list across all of them — the first piece of
 * scaffolding a second transport (Part 2) needs to slot in without every
 * caller re-learning how to merge two independent peer lists itself.
 *
 * With exactly one transport registered (today, [WifiDirectManager] only)
 * [mergedPeers] degenerates to that transport's own list, unchanged and
 * undeduped — see [mergePeers]'s doc for why that's a real guarantee, not a
 * coincidence of the current call sites.
 */
class MeshTransportRegistry {

    private val transports = LinkedHashMap<String, MeshTransport>()
    private val lastKnownPeersByTransport = LinkedHashMap<String, List<DiscoveredPeer>>()

    fun register(transport: MeshTransport) {
        transports[transport.id] = transport
    }

    fun unregister(transportId: String) {
        transports.remove(transportId)
        lastKnownPeersByTransport.remove(transportId)
    }

    fun all(): List<MeshTransport> = transports.values.toList()

    fun get(transportId: String): MeshTransport? = transports[transportId]

    /** Call whenever a transport's [MeshTransport.Callbacks.onPeersChanged]
     *  fires — updates this registry's own record for that transport, so
     *  [mergedPeers] always reflects the latest sighting from EVERY
     *  registered transport, not just whichever fired most recently. */
    fun updatePeers(transportId: String, peers: List<DiscoveredPeer>) {
        lastKnownPeersByTransport[transportId] = peers
    }

    fun mergedPeers(): List<DiscoveredPeer> = mergePeers(lastKnownPeersByTransport.values.flatten())

    companion object {
        /** PART 1.4/2.6: dedupes [peers] by [DiscoveredPeer.resolvedShortNodeId]
         *  — the ONLY identifier common to every transport (see
         *  [DiscoveredPeer]'s own doc: [DiscoveredPeer.transportPeerId] is
         *  scoped to its own transport and two different transports' ids for
         *  the SAME physical device never match). A peer with a null
         *  resolvedShortNodeId (not yet resolved via any side channel) is
         *  NEVER deduped against anything else — it's kept as its own row,
         *  same as today's "UNRESOLVED" nearbyDevices behavior — so
         *  [mergePeers] with a single transport, or with every peer
         *  unresolved, returns its input list completely unchanged (order
         *  and all), which is what makes "one transport registered ==
         *  today's behavior" a real guarantee rather than an approximation.
         *
         *  When two OR MORE sightings DO share a resolved short node id,
         *  local-wifi is preferred over wifi-direct (2.6: "lower setup
         *  cost, no group formation") — expressed generically as a transport
         *  PRIORITY ORDER rather than a hardcoded id comparison, so a THIRD
         *  future transport only needs to extend [TRANSPORT_PRIORITY], not
         *  change this function. A transport absent from that list (not
         *  possible today, but the fallback needs a rule) sorts last, first
         *  sighting wins. */
        private val TRANSPORT_PRIORITY = listOf("local-wifi", "wifi-direct")

        private fun priorityOf(transportId: String): Int =
            TRANSPORT_PRIORITY.indexOf(transportId).let { if (it < 0) Int.MAX_VALUE else it }

        fun mergePeers(peers: List<DiscoveredPeer>): List<DiscoveredPeer> {
            val result = mutableListOf<DiscoveredPeer>()
            val bestByShortId = mutableMapOf<String, Int>() // resolvedShortNodeId -> index into result
            peers.forEach { peer ->
                val key = peer.resolvedShortNodeId
                if (key == null) {
                    result.add(peer)
                    return@forEach
                }
                val existingIndex = bestByShortId[key]
                if (existingIndex == null) {
                    bestByShortId[key] = result.size
                    result.add(peer)
                } else if (priorityOf(peer.transportId) < priorityOf(result[existingIndex].transportId)) {
                    result[existingIndex] = peer
                }
            }
            return result
        }
    }
}
