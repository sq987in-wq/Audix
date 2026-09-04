package app.candela.protocol

import java.util.BitSet
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Systematic Luby Transform fountain. 1:1 with src/protocol/fountain.ts.
 *
 * THIS FILE IS INTEROP-CRITICAL. The neighbour selection is a pure function of
 * (symbolId, k) and must produce byte-identical results to the TypeScript
 * reference, or a Kotlin receiver silently mis-XORs a TypeScript sender's
 * symbols and the fountain never converges. FountainNeighborsTest asserts this
 * against golden vectors for k in {1, 2, 7, 86, 683, 2000} across ~5200 ids.
 *
 * The two places JS and JVM most easily diverge, both handled explicitly below:
 *  1. mulberry32 — JS uses uint32 wraparound and Math.imul; Kotlin Int is signed
 *     32-bit with the same wraparound, so `imul` maps to plain `*`, but every
 *     shift must be `ushr` (logical) and every comparison/division must go
 *     through a Long widened with `and 0xFFFFFFFFL`.
 *  2. seeding — `(symbolId + 1) * 0x9E3779B9` is IEEE-754 double arithmetic in
 *     JS, exact for ids up to 65535 (product < 2^53), then truncated to uint32.
 *     Kotlin reproduces that with Long multiply + mask.
 */
object Fountain {

    /** Deterministic PRNG, bit-exact with the TypeScript mulberry32. */
    class Mulberry32(seed: Int) {
        private var a: Int = seed

        fun nextDouble(): Double {
            a += 0x6D2B79F5.toInt()
            var t = a
            t = (t xor (t ushr 15)) * (t or 1)
            t = t xor (t + (t xor (t ushr 7)) * (t or 61))
            val u = (t xor (t ushr 14)).toLong() and 0xFFFFFFFFL
            return u.toDouble() / 4294967296.0
        }
    }

    fun seedFor(symbolId: Int): Int =
        (((symbolId + 1).toLong() * 0x9E3779B9L) and 0xFFFFFFFFL).toInt()

    /**
     * Robust soliton distribution. Returns mu[0..k], mu[0] unused.
     * c = 0.12, delta = 0.05 (frozen, PSR section 2.4).
     */
    fun robustSoliton(
        k: Int,
        c: Double = Constants.FOUNTAIN_C,
        delta: Double = Constants.FOUNTAIN_DELTA,
    ): DoubleArray {
        val r = max(1.0, c * ln(k / delta) * sqrt(k.toDouble()))
        val rho = DoubleArray(k + 1)
        if (k >= 1) rho[1] = 1.0 / k
        for (d in 2..k) rho[d] = 1.0 / (d.toDouble() * (d - 1).toDouble())

        val spike = max(1, min(k, Math.round(k / r).toInt()))
        val tau = DoubleArray(k + 1)
        for (d in 1 until spike) tau[d] = r / (d.toDouble() * k.toDouble())
        if (spike <= k) tau[spike] = (r * ln(r / delta)) / k.toDouble()

        var z = 0.0
        val mu = DoubleArray(k + 1)
        for (d in 1..k) {
            mu[d] = rho[d] + tau[d]
            z += mu[d]
        }
        for (d in 1..k) mu[d] /= z
        return mu
    }

    private fun sampleDegree(mu: DoubleArray, rng: Mulberry32): Int {
        val u = rng.nextDouble()
        var acc = 0.0
        for (d in 1 until mu.size) {
            acc += mu[d]
            if (u <= acc) return d
        }
        return max(1, mu.size - 1)
    }

    private fun sampleNeighbors(k: Int, d: Int, rng: Mulberry32): IntArray {
        val set = LinkedHashSet<Int>()
        val want = min(max(1, d), k)
        var guard = 0
        while (set.size < want && guard++ < k * 8) {
            val idx = Math.floor(rng.nextDouble() * k).toInt()
            if (idx in 0 until k) set.add(idx)
        }
        val out = set.toIntArray()
        out.sort()
        return out
    }

    /** Systematic: ids below k are the source blocks themselves. */
    fun neighborsFor(symbolId: Int, k: Int, mu: DoubleArray): IntArray {
        if (symbolId < k) return intArrayOf(symbolId)
        val rng = Mulberry32(seedFor(symbolId))
        val d = sampleDegree(mu, rng)
        return sampleNeighbors(k, d, rng)
    }

    internal fun xorInto(dst: ByteArray, src: ByteArray) {
        val n = min(dst.size, src.size)
        for (i in 0 until n) dst[i] = (dst[i].toInt() xor src[i].toInt()).toByte()
    }

    class Encoder(data: ByteArray, val blockSize: Int) {
        val fileSize: Int = data.size
        val k: Int = max(1, ceil(data.size.toDouble() / blockSize).toInt())
        val mu: DoubleArray
        private val blocks: Array<ByteArray>
        private var nextId = 0

        init {
            blocks = Array(k) { i ->
                val b = ByteArray(blockSize)
                val start = i * blockSize
                val end = min(start + blockSize, data.size)
                if (start < data.size) data.copyInto(b, 0, start, end)
                b
            }
            mu = robustSoliton(k)
        }

        fun recommendedSymbols(): Int =
            ceil(k * Constants.FOUNTAIN_OVERHEAD).toInt() + 16

        data class Symbol(val symbolId: Int, val payload: ByteArray, val neighbors: IntArray) {
            override fun equals(other: Any?) = other is Symbol &&
                symbolId == other.symbolId && payload.contentEquals(other.payload)
            override fun hashCode() = symbolId
        }

        fun encode(symbolId: Int? = null): Symbol {
            val id = symbolId ?: nextId++
            val neighbors = neighborsFor(id, k, mu)
            val payload = ByteArray(blockSize)
            for (n in neighbors) xorInto(payload, blocks[n])
            return Symbol(id, payload, neighbors)
        }
    }

    private class Equation(var vars: MutableList<Int>, val payload: ByteArray)

    /**
     * Peel decoder with a bounded Gaussian-elimination fallback.
     *
     * Peeling alone stalls on mid-size k; GE finishes it. GE is bounded to
     * <=80 unknowns and a pool of <=96 equations so worst-case cost stays inside
     * the thermal budget (audit section 3) rather than spiking on a bad frame.
     *
     * Dedup uses a BitSet(65536) — allocation-free after init, matching the
     * 16-bit symbol id space (audit section 6.6).
     */
    class Decoder(val k: Int, val blockSize: Int, val fileSize: Int) {
        val mu: DoubleArray = robustSoliton(k)
        private val recovered = arrayOfNulls<ByteArray>(k)
        private var recoveredCount = 0
        private val seen = BitSet(65536)
        private var seenCount = 0
        private var pool = mutableListOf<Equation>()

        val uniqueCount: Int get() = seenCount
        val doneCount: Int get() = recoveredCount
        val progress: Double get() = if (k == 0) 1.0 else recoveredCount.toDouble() / k

        fun has(symbolId: Int): Boolean = seen.get(symbolId)
        fun isComplete(): Boolean = recoveredCount >= k

        fun ingest(symbolId: Int, payload: ByteArray): Boolean {
            if (symbolId < 0 || symbolId > 65535) return false
            if (seen.get(symbolId) || isComplete()) return false
            seen.set(symbolId)
            seenCount++

            val neighbors = neighborsFor(symbolId, k, mu)
            val eq = Equation(neighbors.toMutableList(), payload.copyOf())
            substituteKnown(eq)
            pool.add(eq)
            peel()
            if (!isComplete() && pool.isNotEmpty() && pool.size <= 96) tryGaussian()
            return true
        }

        private fun substituteKnown(eq: Equation) {
            var i = eq.vars.size - 1
            while (i >= 0) {
                val n = eq.vars[i]
                val rec = recovered[n]
                if (rec != null) {
                    xorInto(eq.payload, rec)
                    eq.vars.removeAt(i)
                }
                i--
            }
        }

        private fun peel() {
            var progressed = true
            while (progressed) {
                progressed = false
                var i = pool.size - 1
                while (i >= 0) {
                    val eq = pool[i]
                    substituteKnown(eq)
                    if (eq.vars.isEmpty()) {
                        pool.removeAt(i)
                    } else if (eq.vars.size == 1) {
                        val idx = eq.vars[0]
                        if (recovered[idx] == null) {
                            recovered[idx] = eq.payload.copyOf()
                            recoveredCount++
                            progressed = true
                        }
                        pool.removeAt(i)
                    }
                    i--
                }
            }
        }

        private fun tryGaussian() {
            val unknown = ArrayList<Int>()
            for (i in 0 until k) if (recovered[i] == null) unknown.add(i)
            if (unknown.isEmpty() || unknown.size > 80) return

            val col = HashMap<Int, Int>(unknown.size * 2)
            unknown.forEachIndexed { i, id -> col[id] = i }
            val u = unknown.size

            val eqs = ArrayList<Equation>()
            for (eq in pool) {
                substituteKnown(eq)
                if (eq.vars.isEmpty()) continue
                if (eq.vars.all { col.containsKey(it) }) {
                    eqs.add(Equation(eq.vars.toMutableList(), eq.payload.copyOf()))
                }
            }
            if (eqs.size < u) return

            val rowW = (u + 7) / 8
            val bits = ArrayList<ByteArray>(eqs.size)
            val rhs = ArrayList<ByteArray>(eqs.size)
            for (eq in eqs) {
                val row = ByteArray(rowW)
                for (v in eq.vars) {
                    val c = col[v] ?: continue
                    row[c shr 3] = (row[c shr 3].toInt() or (1 shl (c and 7))).toByte()
                }
                bits.add(row)
                rhs.add(eq.payload.copyOf())
            }

            val m = bits.size
            val pivotRow = IntArray(u) { -1 }
            var rank = 0
            for (c in 0 until u) {
                var pr = -1
                for (r in rank until m) {
                    if ((bits[r][c shr 3].toInt() and (1 shl (c and 7))) != 0) { pr = r; break }
                }
                if (pr < 0) continue
                if (pr != rank) {
                    val tb = bits[rank]; bits[rank] = bits[pr]; bits[pr] = tb
                    val tp = rhs[rank]; rhs[rank] = rhs[pr]; rhs[pr] = tp
                }
                pivotRow[c] = rank
                for (r in 0 until m) {
                    if (r == rank) continue
                    if ((bits[r][c shr 3].toInt() and (1 shl (c and 7))) != 0) {
                        val br = bits[r]; val bk = bits[rank]
                        for (j in 0 until rowW) br[j] = (br[j].toInt() xor bk[j].toInt()).toByte()
                        xorInto(rhs[r], rhs[rank])
                    }
                }
                rank++
            }
            if (rank < u) return

            for (c in 0 until u) {
                val r = pivotRow[c]
                if (r < 0) return
                val idx = unknown[c]
                if (recovered[idx] == null) {
                    recovered[idx] = rhs[r].copyOf()
                    recoveredCount++
                }
            }
            pool = mutableListOf()
        }

        fun assemble(): ByteArray? {
            if (!isComplete()) return null
            val out = ByteArray(fileSize)
            for (i in 0 until k) {
                val block = recovered[i] ?: return null
                val start = i * blockSize
                val len = min(blockSize, fileSize - start)
                if (len > 0) block.copyInto(out, start, 0, len)
            }
            return out
        }
    }
}
