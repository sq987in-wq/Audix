package com.candela.protocol

import java.util.BitSet

private fun imul(a: Int, b: Int): Int = a * b

internal fun mulberry32(seed: Int): () -> Double {
    var a = seed
    return {
        a += 0x6d2b79f5
        var t = a
        t = imul(t xor (t ushr 15), t or 1)
        t = t xor (t + imul(t xor (t ushr 7), t or 61))
        ((t xor (t ushr 14)).toLong() and 0xffffffffL).toDouble() / 4294967296.0
    }
}

internal fun robustSoliton(k: Int, c: Double = Protocol.FOUNTAIN_C, delta: Double = Protocol.FOUNTAIN_DELTA): DoubleArray {
    val R = maxOf(1.0, c * kotlin.math.ln(k / delta) * kotlin.math.sqrt(k.toDouble()))
    val rho = DoubleArray(k + 1)
    rho[1] = 1.0 / k
    for (d in 2..k) rho[d] = 1.0 / (d * (d - 1).toDouble())
    val spike = maxOf(1, minOf(k, kotlin.math.round(k / R).toInt()))
    val tau = DoubleArray(k + 1)
    for (d in 1 until spike) tau[d] = R / (d * k.toDouble())
    tau[spike] = (R * kotlin.math.ln(R / delta)) / k
    var z = 0.0
    val mu = DoubleArray(k + 1)
    for (d in 1..k) {
        mu[d] = rho[d] + tau[d]
        z += mu[d]
    }
    for (d in 1..k) mu[d] /= z
    return mu
}

internal fun sampleDegree(mu: DoubleArray, rng: () -> Double): Int {
    val u = rng()
    var acc = 0.0
    for (d in 1 until mu.size) {
        acc += mu[d]
        if (u <= acc) return d
    }
    return maxOf(1, mu.size - 1)
}

internal fun sampleNeighbors(k: Int, d: Int, rng: () -> Double): IntArray {
    val set = LinkedHashSet<Int>()
    val want = minOf(maxOf(1, d), k)
    var guard = 0
    while (set.size < want && guard++ < k * 8) {
        val idx = kotlin.math.floor(rng() * k).toInt()
        if (idx in 0 until k) set.add(idx)
    }
    return set.sorted().toIntArray()
}

fun neighborsFor(symbolId: Int, k: Int, mu: DoubleArray): IntArray {
    if (symbolId < k) return intArrayOf(symbolId)
    val rng = mulberry32((symbolId + 1) * -1640531527)
    val d = sampleDegree(mu, rng)
    return sampleNeighbors(k, d, rng)
}

class FountainEncoder(
    private val source: (blockIndex: Int) -> ByteArray,
    val k: Int,
    val blockSize: Int,
    val fileSize: Long,
) {
    val mu: DoubleArray = robustSoliton(k)

    constructor(data: ByteArray, blockSize: Int) : this(
        source = { i ->
            val b = ByteArray(blockSize)
            val start = i * blockSize
            val len = minOf(blockSize, data.size - start)
            if (len > 0) System.arraycopy(data, start, b, 0, len)
            b
        },
        k = maxOf(1, (data.size + blockSize - 1) / blockSize),
        blockSize = blockSize,
        fileSize = data.size.toLong(),
    )

    fun recommendedSymbols(): Int = kotlin.math.ceil(k * Protocol.FOUNTAIN_OVERHEAD).toInt() + 16

    fun encode(symbolId: Int): ByteArray {
        val neighbors = neighborsFor(symbolId, k, mu)
        val payload = ByteArray(blockSize)
        for (n in neighbors) Bytes.xorInto(payload, source(n))
        return payload
    }
}

private class Equation(var vars: IntArray, val payload: ByteArray)

class FountainDecoder(val k: Int, val blockSize: Int, val fileSize: Long) {
    val mu: DoubleArray = robustSoliton(k)
    private val recovered = arrayOfNulls<ByteArray>(k)
    private var recoveredCount = 0
    private val seen = BitSet(65536)
    private val pool = ArrayList<Equation>()

    fun uniqueCount(): Int = seen.cardinality()
    fun doneCount(): Int = recoveredCount
    fun progress(): Float = if (k == 0) 1f else recoveredCount.toFloat() / k
    fun has(symbolId: Int): Boolean = seen.get(symbolId)
    fun isComplete(): Boolean = recoveredCount >= k

    fun ingest(symbolId: Int, payload: ByteArray): Boolean {
        if (seen.get(symbolId) || isComplete()) return false
        seen.set(symbolId)
        val neighbors = neighborsFor(symbolId, k, mu)
        val eq = Equation(neighbors.copyOf(), payload.copyOf())
        substituteKnown(eq)
        pool.add(eq)
        peel()
        if (!isComplete() && pool.isNotEmpty() && pool.size <= 96) tryGaussian()
        return true
    }

    private fun substituteKnown(eq: Equation) {
        val keep = ArrayList<Int>()
        for (n in eq.vars) {
            val rec = recovered[n]
            if (rec != null) Bytes.xorInto(eq.payload, rec) else keep.add(n)
        }
        eq.vars = keep.toIntArray()
    }

    private fun peel() {
        var progressed = true
        while (progressed) {
            progressed = false
            val it = pool.listIterator(pool.size)
            while (it.hasPrevious()) {
                val eq = it.previous()
                substituteKnown(eq)
                when (eq.vars.size) {
                    0 -> it.remove()
                    1 -> {
                        val idx = eq.vars[0]
                        if (recovered[idx] == null) {
                            recovered[idx] = eq.payload.copyOf()
                            recoveredCount++
                            progressed = true
                        }
                        it.remove()
                    }
                }
            }
        }
    }

    private fun tryGaussian() {
        val unknown = ArrayList<Int>()
        for (i in 0 until k) if (recovered[i] == null) unknown.add(i)
        if (unknown.isEmpty() || unknown.size > 80) return
        val col = HashMap<Int, Int>()
        unknown.forEachIndexed { i, id -> col[id] = i }
        val u = unknown.size
        val eqs = ArrayList<Equation>()
        for (eq in pool) {
            substituteKnown(eq)
            if (eq.vars.isEmpty()) continue
            if (eq.vars.all { col.containsKey(it) }) {
                eqs.add(Equation(eq.vars.copyOf(), eq.payload.copyOf()))
            }
        }
        if (eqs.size < u) return
        val rowW = (u + 7) / 8
        val bits = ArrayList<ByteArray>()
        val rhs = ArrayList<ByteArray>()
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
                if (bits[r][c shr 3].toInt() and (1 shl (c and 7)) != 0) {
                    pr = r
                    break
                }
            }
            if (pr < 0) continue
            if (pr != rank) {
                val tb = bits[rank]; bits[rank] = bits[pr]; bits[pr] = tb
                val tp = rhs[rank]; rhs[rank] = rhs[pr]; rhs[pr] = tp
            }
            pivotRow[c] = rank
            for (r in 0 until m) {
                if (r == rank) continue
                if (bits[r][c shr 3].toInt() and (1 shl (c and 7)) != 0) {
                    for (j in 0 until rowW) bits[r][j] = (bits[r][j].toInt() xor bits[rank][j].toInt()).toByte()
                    Bytes.xorInto(rhs[r], rhs[rank])
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
        pool.clear()
    }

    fun assemble(): ByteArray? {
        if (!isComplete()) return null
        val out = ByteArray(fileSize.toInt())
        for (i in 0 until k) {
            val block = recovered[i] ?: return null
            val start = i * blockSize
            val len = minOf(blockSize, out.size - start)
            System.arraycopy(block, 0, out, start, len)
        }
        return out
    }
}
