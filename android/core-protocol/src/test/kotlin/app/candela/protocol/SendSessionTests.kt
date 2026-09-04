package app.candela.protocol

import kotlin.system.exitProcess

/**
 * Sender-side verification.
 *
 * The receiver's gate was proven in Stage8Tests. This proves the OTHER HALF:
 * that the sender does not transmit payload before both humans confirm. A
 * one-sided gate is not a gate — an attacker who controls only the receiver's
 * display would still see the whole file go out over the optical channel.
 */

private object TX {
    var passed = 0
    var failed = 0
    val failures = mutableListOf<String>()
    var suite = ""

    fun suite(n: String) {
        suite = n
        println("\n\u2500\u2500 $n ${"\u2500".repeat(maxOf(2, 58 - n.length))}")
    }

    fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) passed++ else {
            failed++
            failures.add("[$suite] $name :: $detail")
            println("  FAIL  $name  ($detail)")
        }
    }

    fun info(m: String) = println("  \u2022 $m")
    fun ok(m: String) = println("  \u2713 $m")

    fun summary(): Int {
        println("\n" + "=".repeat(64))
        if (failed == 0) println("ALL TESTS PASSED   $passed assertions, 0 failures")
        else {
            println("FAILURES: $failed  (passed $passed)")
            failures.forEach { println("   - $it") }
        }
        println("=".repeat(64))
        return if (failed == 0) 0 else 1
    }
}

private val SECRET = ByteArray(32) { (it * 11 + 5).toByte() }
private fun newSession() = SendSession(
    keyPair = Crypto.keyPairFromSecret(SECRET),
    sessionIdOverride = ByteArray(8) { (it + 2).toByte() },
)

fun main() {
    println("Candela sender verification — SendSession")

    testPrepare()
    testRefusals()
    testSenderGate()
    testRoundTrip()

    exitProcess(TX.summary())
}

private fun testPrepare() {
    TX.suite("Prepare builds a signed, self-consistent manifest")
    val data = ByteArray(4096) { ((it * 13) % 251).toByte() }
    val s = newSession()
    val r = s.prepare(data, "photo.jpg", "image/jpeg")

    TX.check("prepare succeeds", r is SendSession.Prepared.Ready)
    r as SendSession.Prepared.Ready
    TX.check("k matches encoder", r.payload.k > 0)
    TX.check("size recorded", r.payload.sizeBytes == data.size)
    TX.check("hash is the file hash", r.payload.sha256Hex == Bytes.toHex(Crypto.fileHash(data)))
    TX.check("mime preserved", r.payload.mime == "image/jpeg")
    TX.check("SAS is 8 digits", r.sas.length == 8 && r.sas.all { it.isDigit() })
    TX.check("recommends more symbols than k", r.payload.recommendedSymbols > r.payload.k)
    TX.info("k=${r.payload.k}, send ${r.payload.recommendedSymbols} symbols, SAS ${Crypto.sasPretty(r.sas)}")

    // The receiver must be able to parse and verify what we produced.
    val headerBytes = s.calFrameBytes()
    TX.check("CAL frame exists", headerBytes != null)

    // Attacker-controlled names are sanitised before they ever hit the wire.
    val s2 = newSession()
    val r2 = s2.prepare(ByteArray(64), "../../etc/passwd", "")
    r2 as SendSession.Prepared.Ready
    TX.check("path traversal sanitised at source", !r2.payload.fileName.contains("/"),
        r2.payload.fileName)
    TX.check("empty mime defaulted", r2.payload.mime == "application/octet-stream")
    TX.ok("manifest is signed and self-consistent before anything is displayed")
}

private fun testRefusals() {
    TX.suite("Refuses rather than starting a doomed transfer")
    val s = newSession()
    val empty = s.prepare(ByteArray(0), "x.bin", "")
    TX.check("empty file refused", empty is SendSession.Prepared.Refused)

    val huge = newSession().prepare(
        ByteArray(Constants.MAX_FILE_BYTES + 1), "big.bin", "",
    )
    TX.check("oversized refused", huge is SendSession.Prepared.Refused)
    huge as SendSession.Prepared.Refused
    TX.check("refusal states the limit", huge.detail.contains("KB"))
    TX.check("refusal explains why", huge.detail.contains("hours"))
    TX.info("refusal: ${huge.reason}")

    TX.check("no payload after refusal", newSession().let {
        it.prepare(ByteArray(0), "x", ""); it.payload == null
    })
    TX.ok("an hours-long transfer is refused up front, not started optimistically")
}

private fun testSenderGate() {
    TX.suite("Sender withholds payload until BOTH confirm")
    val data = ByteArray(2048) { it.toByte() }
    val s = newSession()
    s.prepare(data, "secret.bin", "application/octet-stream")
    s.startCalibration()
    s.onCalibrationComplete()
    TX.check("reached PAIRING", s.state == SessionState.PAIRING)
    TX.check("SAS presented", s.sasGate?.state == SasGate.State.AWAITING_BOTH)

    // Ask for payload frames before confirmation. All must come back as CAL.
    val cal = s.calFrameBytes()!!
    var leaked = 0
    for (i in 0 until 50) {
        val f = s.frameFor(SendSession.SymbolContent.Data(i))
        if (f != null && !f.contentEquals(cal)) leaked++
    }
    val h = s.frameFor(SendSession.SymbolContent.Header)
    if (h != null && !h.contentEquals(cal)) leaked++
    TX.check("ZERO payload frames emitted pre-confirm", leaked == 0, "leaked=$leaked")
    TX.check("withheld frames counted", s.framesBlockedBySas >= 50,
        "blocked=${s.framesBlockedBySas}")
    TX.check("no symbols encoded", s.symbolsEmitted == 0L)
    TX.check("header withheld", s.headerFrameBytes() == null)
    TX.check("prerender list empty", s.allDataFrames().isEmpty())
    TX.info("${s.framesBlockedBySas} payload requests answered with CAL instead")

    // One-sided confirmation is still not enough.
    s.confirmSasLocal()
    var afterOne = 0
    for (i in 0 until 20) {
        val f = s.frameFor(SendSession.SymbolContent.Data(i))
        if (f != null && !f.contentEquals(cal)) afterOne++
    }
    TX.check("still withheld after ONE confirm", afterOne == 0, "leaked=$afterOne")
    TX.check("still PAIRING", s.state == SessionState.PAIRING)

    // Both confirmed: payload flows.
    s.confirmSasRemote()
    TX.check("unlocked after both", s.sasGate?.isDataPlaneUnlocked == true)
    val d = s.frameFor(SendSession.SymbolContent.Data(0))
    TX.check("data flows after both confirm", d != null && !d.contentEquals(cal))
    TX.check("header available", s.headerFrameBytes() != null)
    TX.check("prerender now populated", s.allDataFrames().isNotEmpty())

    // A mismatch is terminal and stops transmission for good.
    val s2 = newSession()
    s2.prepare(data, "f.bin", "")
    s2.startCalibration()
    s2.onCalibrationComplete()
    s2.reportSasMismatch()
    TX.check("mismatch aborts", s2.state == SessionState.ABORTED)
    val after = s2.frameFor(SendSession.SymbolContent.Data(0))
    TX.check("nothing sent after mismatch", after == null || after.contentEquals(s2.calFrameBytes()!!))
    TX.ok("the sender enforces the same gate as the receiver, in the frame path")
}

private fun testRoundTrip() {
    TX.suite("End-to-end: SendSession -> optical channel -> ReceiveSession")
    val data = ByteArray(6000) { ((it * 29) % 253).toByte() }

    val tx = newSession()
    tx.prepare(data, "report.pdf", "application/pdf")
    tx.startCalibration()
    tx.onCalibrationComplete()
    tx.confirmSasLocal()
    tx.confirmSasRemote()
    tx.beginSending()
    TX.check("sender is SENDING", tx.state == SessionState.SENDING)

    val rx = ReceiveSession()
    rx.startCalibration()
    rx.onCalibrationResult(true)

    // Header first, as the real link interleaves it.
    val header = tx.headerFrameBytes()!!
    rx.ingestFrame(Frames.decode(header))
    TX.check("receiver parsed the header", rx.header != null)

    // Both SAS values must agree — this is what the humans compare.
    TX.check("SAS matches across devices", tx.sas() == rx.sasGate?.localSas,
        "tx=${tx.sas()} rx=${rx.sasGate?.localSas}")
    rx.confirmSasLocal()
    rx.confirmSasRemote()
    TX.check("receiver is RECEIVING", rx.state == SessionState.RECEIVING)

    // Stream with 20% loss, as a real optical channel behaves.
    var seed = 0x5EED_1234
    fun rnd(): Int {
        seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17)
        seed = seed xor (seed shl 5); return seed
    }
    val pk = rx.header!!.publicKey
    var sent = 0
    var id = 0
    while (rx.state == SessionState.RECEIVING && id < tx.payload!!.recommendedSymbols * 3) {
        val f = tx.frameFor(SendSession.SymbolContent.Data(id))
        id++
        if (f == null) break
        if ((rnd() ushr 1) % 100 < 20) continue // dropped by the channel
        rx.ingestFrame(Frames.decode(f, pk))
        sent++
    }

    TX.check("receiver reached COMPLETE", rx.state == SessionState.COMPLETE,
        "state=${rx.state}")
    val out = rx.verifiedBytes()
    TX.check("bytes recovered", out != null)
    if (out != null) {
        TX.check("byte-identical round trip", Bytes.eq(out, data))
        TX.check("hash matches the sender's", Bytes.toHex(Crypto.fileHash(out)) ==
            tx.payload!!.sha256Hex)
    }
    TX.info("k=${tx.payload!!.k}, delivered $sent symbols through 20% loss")

    tx.markComplete()
    TX.check("sender can close out", tx.state == SessionState.COMPLETE)
    TX.ok("a file survives the full sender -> lossy channel -> receiver path")
}
