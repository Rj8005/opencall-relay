package com.opencall.relay.dialer.data

/**
 * SCOPE: this pillar IS a phone-app replacement; it is NOT a spam-ID
 * database. This interface is the single seam a real number-reputation
 * provider could be plugged into later — every UI call site (incoming-call
 * screen, call log, contacts) reads a verdict through this interface only,
 * never a bundled dataset, never a network lookup written by this pillar.
 * [NullSpamLookupProvider] is the only implementation shipped here, and it
 * always answers [SpamVerdict.UNKNOWN] — this app does not build, scrape,
 * or bundle a crowdsourced number-reputation dataset.
 */
enum class SpamVerdict { UNKNOWN, LIKELY_SAFE, LIKELY_SPAM }

interface SpamLookupProvider {
    fun lookup(e164Number: String): SpamVerdict
}

object NullSpamLookupProvider : SpamLookupProvider {
    override fun lookup(e164Number: String): SpamVerdict = SpamVerdict.UNKNOWN
}
