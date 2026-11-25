// Which WSPR slots the beacon transmits in: a port of WSJT-X's WSPR/WsprTxScheduler.cpp (GPL v3, K1JT and the WSJT
// developers). Rather than a coin flip each slot (which can transmit twice running), it plans each two hours ahead -
// 60 two-minute slots, as 6 rows of 10 - so that:
//   - up to 25 %: transmissions are spread out with a random gap of receive slots between them (K1JT's method:
//     at 20 % 3 to 5 receive slots between transmissions; never two transmissions running);
//   - over 25 %: no more than 1 (up to 50 %), 2 (up to 60 %) or 3 transmissions running, filling the largest gaps first.
// The first slot of each two hours is never a transmission, so two plans never join into a double transmission.
package uk.hamdigital.core

import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

object WsprTxScheduler {
    private val tx = Array(6) { IntArray(10) }        // the plan: [20-minute slot][2-minute slot within it], 1 = transmit
    private var planFor = -1                          // the two-hour slot (0-11) the plan is for
    private var planPct = 0                           // the percentage it was made for

    /** Transmit in the two-minute slot starting at [slotMs] (epoch ms), at [pct] % of slots? */
    fun transmitIn(slotMs: Long, pct: Int): Boolean {
        val t = Instant.ofEpochMilli(slotMs + 30_000).atZone(ZoneOffset.UTC) // (as WSJT-X: 30 s into it, clear of the edge)
        val twoHour = t.hour / 2                      // which two hours of the day
        val twenty = (t.hour - twoHour * 2) * 3 + t.minute / 20 // which 20 minutes of those (0-5)
        val two = (t.minute % 20) / 2                 // which 2 minutes of those (0-9)
        if (twoHour != planFor || pct != planPct) {   // a new two hours, or the percentage changed: plan again
            plan(pct); planFor = twoHour; planPct = pct
        }
        return tx[twenty][two] == 1
    }

    // Slot [i] (0-59) of the plan, read and written as WSJT-X's flat char[60].
    private fun get(i: Int) = tx[i / 10][i % 10]
    private fun put(i: Int, v: Int) { tx[i / 10][i % 10] = v }

    // A plan for [pct] % (create_tx_schedule).
    private fun plan(pct: Int) {
        val needed = (60 * (pct / 100.0) + 0.5).toInt() // transmissions wanted in the two hours
        for (r in tx) r.fill(0)                       // none yet
        if (pct <= 0) return
        if (pct <= 25) {                              // K1JT's method: a random number of receive slots, then one transmission
            val rxAvg = 100.0 / pct - 1.0             // receive slots on average between transmissions
            var i = 0
            while (true) {
                val x = Random.nextInt(100) / 100.0
                val nrx = (rxAvg + 3.0 * x - 1.0).toInt() // 2-5 at 25 %, 3-5 at 20 %
                i += nrx + 1
                if (i < 60) put(i, 1) else break
            }
            return
        }
        val (txLim, perBand) = when {                 // most transmissions running; least per column (band, when hopping)
            pct < 33 -> 1 to 1
            pct < 50 -> 1 to 2
            pct < 60 -> 2 to 3
            else -> 3 to 4
        }
        repeat(perBand) { for (j in 0 until 10) addToBand(j) } // each column its share
        var sum = trim(txLim)                         // then no more than txLim running
        var tries = 0; var done = false
        while (tries < 100 && !done) {                // fill columns the trim emptied
            done = true
            for (j in 0 until 10) {
                val have = (0 until 6).sumOf { tx[it][j] }
                if (have < perBand) { done = false; repeat(perBand - have) { addToBand(j) } }
            }
            sum = trim(txLim); tries++
        }
        repeat(needed - sum) { addOne() }             // then up to the percentage, in the largest gaps
    }

    // One more transmission in column [band], in a random row (tx_add_to_band).
    private fun addToBand(band: Int) {
        repeat(10) {
            val row = Random.nextInt(6)
            if (tx[row][band] != 1) { tx[row][band] = 1; return }
        }
    }

    // At most [lim] transmissions running, and none in the first slot; returns how many are left (tx_trim).
    private fun trim(lim: Int): Int {
        if (get(0) == 1) put(0, 0)
        var run = 0
        for (i in 0 until 60) {
            if (get(i) == 1) { run++; if (run > lim) { put(i, 0); run = 0 } } else run = 0
        }
        return (0 until 60).sumOf { get(it) }
    }

    // One more transmission without making two run together: in the middle of the largest gap (13, 11, ... 3 slots)
    // (tx_add_one).
    private fun addOne() {
        var gap = 13
        while (gap >= 3) {
            for (i in 0 until 60 - gap) {
                if ((0 until gap).none { get(i + it) == 1 }) { put(i + gap / 2, 1); return }
            }
            gap -= 2
        }
        if (get(58) == 0 && get(59) == 0) put(59, 1) // last resort: the last slot
    }
}
