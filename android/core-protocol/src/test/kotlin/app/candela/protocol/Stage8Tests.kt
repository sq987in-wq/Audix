package app.candela.protocol

import kotlin.system.exitProcess

/**
 * Stage 8 verification: the blocking SAS gate and the SHA-256 export gate.
 *
 * These two mechanisms are the product's security and integrity promises, so they
 * are tested as INVARIANTS ("no data can flow before X") rather than as happy
 * paths. The most important tests here are the negative ones.
 */

private object S {
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

// Deterministic test fixtures.
private val SECRET = ByteArray(32) { (it * 7 + 3).toByte() }
private val PUBLIC = Crypto.signer.publicKey(SECRET)
private val SESSION_ID = ByteArray(8) { (it + 1).toByte() }

fun main() {
    println("Candela Stage 8 verification — SAS gate + export gate")

    testSasGateBasics()
    testSasBlocksDataPlane()
    testSasMismatchPaths()
    testExportGateHashing()
    testExportRefusesPartial()
    testFileNameSanitisation()
    testEndToEndReceive()

    exitProcess(S.summary())
}

private fun testSasGateBasics() {
    S.suite("SAS gate — both parties required")
    val sas = Crypto.sasFromPublicKey(PUBLIC)
    S.check("SAS is 8 digits", sas.length == 8 && sas.all { it.isDigit() }, "sas=$sas")
    S.info("SAS = ${Crypto.sasPretty(sas)}")

    val g = SasGate(sas)
    S.check("starts idle", g.state == SasGate.State.IDLE)
    S.check("locked while idle", !g.isDataPlaneUnlocked)

    g.present()
    S.check("awaiting both after present", g.state == SasGate.State.AWAITING_BOTH)
    S.check("still locked", !g.isDataPlaneUnlocked)

    g.confirmLocal()
    S.check("local only -> LOCAL_CONFIRMED", g.state == SasGate.State.LOCAL_CONFIRMED)
    S.check("ONE confirmation is not enough", !g.isDataPlaneUnlocked)

    g.confirmRemote()
    S.check("both -> UNLOCKED", g.state == SasGate.State.UNLOCKED)
    S.check("now unlocked", g.isDataPlaneUnlocked)

    // Remote-first ordering must work identically.
    val g2 = SasGate(sas)
    g2.present()
    g2.confirmRemote()
    S.check("remote only is not enough", !g2.isDataPlaneUnlocked)
    S.check("remote-first -> REMOTE_CONFIRMED", g2.state == SasGate.State.REMOTE_CONFIRMED)
    g2.confirmLocal()
    S.check("remote-then-local unlocks", g2.isDataPlaneUnlocked)
    S.ok("data plane requires BOTH humans, in either order")
}

private fun testSasBlocksDataPlane() {
    S.suite("SAS gate blocks the fountain, not just the UI")
    val session = ReceiveSession()
    session.startCalibration()
    session.onCalibrationResult(true)
    S.check("reached PAIRING", session.state == SessionState.PAIRING)

    val data = ByteArray(240) { (it % 251).toByte() }
    val enc = Fountain.Encoder(data, 48)
    val header = Frames.encodeHeader(
        HeaderPayload(
            SESSION_ID, "secret.bin", data.size.toLong(), enc.k, 48,
            Crypto.fileHash(data), PUBLIC, "application/octet-stream",
        ),
        SECRET,
    )
    session.ingestFrame(Frames.decode(header))
    S.check("header accepted", session.header != null)
    S.check("SAS presented", session.sasGate?.state == SasGate.State.AWAITING_BOTH)

    // Fire every data symbol at the session BEFORE confirming. All must bounce.
    var accepted = 0
    for (id in 0 until enc.k + 40) {
        val s = enc.encode(id)
        val raw = Frames.encodeData(SESSION_ID, id, s.payload, SECRET)
        if (session.ingestFrame(Frames.decode(raw, PUBLIC))) accepted++
    }
    S.check("ZERO symbols ingested before SAS confirm", accepted == 0, "accepted=$accepted")
    S.check("blocked frames counted", session.framesBlockedBySas >= enc.k,
        "blocked=${session.framesBlockedBySas}")
    S.check("decoder saw nothing", session.decoder?.doneCount == 0)
    S.check("still in PAIRING", session.state == SessionState.PAIRING)
    S.info("${session.framesBlockedBySas} valid, correctly-signed frames refused pre-confirm")

    // One-sided confirmation still blocks.
    session.confirmSasLocal()
    var afterLocal = 0
    for (id in 0 until 20) {
        val s = enc.encode(id)
        if (session.ingestFrame(
                Frames.decode(Frames.encodeData(SESSION_ID, id, s.payload, SECRET), PUBLIC),
            )
        ) afterLocal++
    }
    S.check("still blocked after ONE confirmation", afterLocal == 0, "accepted=$afterLocal")
    S.check("state still PAIRING", session.state == SessionState.PAIRING)

    // Both confirmed -> flows.
    session.confirmSasRemote()
    S.check("unlocked after both", session.sasGate?.isDataPlaneUnlocked == true)
    S.check("advanced to RECEIVING", session.state == SessionState.RECEIVING)

    var flowed = 0
    for (id in 0 until enc.k) {
        val s = enc.encode(id)
        if (session.ingestFrame(
                Frames.decode(Frames.encodeData(SESSION_ID, id, s.payload, SECRET), PUBLIC),
            )
        ) flowed++
    }
    S.check("symbols flow after both confirm", flowed > 0, "flowed=$flowed")
    S.ok("the ingest path itself enforces the gate — a UI bug cannot leak data")
}

private fun testSasMismatchPaths() {
    S.suite("SAS mismatch is terminal")
    val sas = Crypto.sasFromPublicKey(PUBLIC)

    val g = SasGate(sas)
    g.present()
    g.reportMismatch()
    S.check("mismatch -> REJECTED", g.state == SasGate.State.REJECTED)
    S.check("rejected is locked", !g.isDataPlaneUnlocked)
    S.check("reason recorded", g.rejectionReason != null)

    // No recovery: confirming after a mismatch must not unlock.
    g.confirmLocal()
    g.confirmRemote()
    S.check("cannot confirm out of REJECTED", !g.isDataPlaneUnlocked,
        "state=${g.state}")

    // A human tapping confirm while the numbers visibly differ is caught.
    val g2 = SasGate(sas)
    g2.present()
    val wrong = if (sas == "00000000") "11111111" else "00000000"
    g2.confirmLocal(comparedAgainst = wrong)
    S.check("confirm with differing digits is rejected", g2.state == SasGate.State.REJECTED)
    S.check("mismatch never unlocks", !g2.isDataPlaneUnlocked)
    S.info("rejection: ${g2.rejectionReason?.take(72)}…")

    // A MITM substituting its own key produces a different SAS.
    val attackerKey = Crypto.signer.publicKey(ByteArray(32) { 0x42 })
    val attackerSas = Crypto.sasFromPublicKey(attackerKey)
    S.check("attacker key yields a different SAS", attackerSas != sas,
        "victim=$sas attacker=$attackerSas")

    val g3 = SasGate(sas)
    g3.present()
    g3.confirmRemote(remoteSas = attackerSas)
    S.check("remote reporting a foreign SAS rejects", g3.state == SasGate.State.REJECTED)

    // A rejected SAS aborts the whole session.
    val session = ReceiveSession()
    session.startCalibration()
    session.onCalibrationResult(true)
    val data = ByteArray(96) { it.toByte() }
    val enc = Fountain.Encoder(data, 48)
    session.ingestFrame(
        Frames.decode(
            Frames.encodeHeader(
                HeaderPayload(
                    SESSION_ID, "f.bin", data.size.toLong(), enc.k, 48,
                    Crypto.fileHash(data), PUBLIC, "text/plain",
                ),
                SECRET,
            ),
        ),
    )
    session.reportSasMismatch()
    S.check("session aborts on mismatch", session.state == SessionState.ABORTED)
    S.check("no bytes available after abort", session.verifiedBytes() == null)
    S.ok("MITM key substitution surfaces as different digits and is terminal")
}

private fun testExportGateHashing() {
    S.suite("Export gate — SHA-256 must match the signed header")
    val data = ByteArray(1000) { (it * 31 % 255).toByte() }
    val header = Frame.Header(
        SESSION_ID, "report.pdf", data.size.toLong(), 21, 48,
        Crypto.fileHash(data), PUBLIC, "application/pdf", ByteArray(64),
    )

    val good = ExportGate.evaluate(data, header)
    S.check("matching hash publishes", good is ExportGate.Decision.Publish)
    if (good is ExportGate.Decision.Publish) {
        S.check("name carried", good.fileName == "report.pdf")
        S.check("mime carried", good.mimeType == "application/pdf")
        S.check("size carried", good.sizeBytes == data.size)
        S.check("hash hex reported", good.sha256Hex == Bytes.toHex(Crypto.fileHash(data)))
    }

    // One flipped bit — every symbol individually valid, file still wrong.
    val corrupted = data.copyOf().also { it[500] = (it[500].toInt() xor 1).toByte() }
    val bad = ExportGate.evaluate(corrupted, header)
    S.check("single flipped bit refuses", bad is ExportGate.Decision.Refuse)
    if (bad is ExportGate.Decision.Refuse) {
        S.check("refusal says nothing written", bad.detail.contains("Nothing was written"))
        S.info("refusal: ${bad.reason}")
    }

    // Truncated reassembly (a decoder bug, not a bad frame).
    val truncated = data.copyOf(900)
    val t = ExportGate.evaluate(truncated, header)
    S.check("size mismatch refuses", t is ExportGate.Decision.Refuse)
    if (t is ExportGate.Decision.Refuse) {
        S.check("size mismatch identified", t.reason.contains("Size"), "got ${t.reason}")
    }
    S.ok("catches reassembly faults that per-symbol crypto cannot")
}

private fun testExportRefusesPartial() {
    S.suite("No partial write, ever")
    val data = ByteArray(500) { it.toByte() }
    val header = Frame.Header(
        SESSION_ID, "x.bin", data.size.toLong(), 11, 48,
        Crypto.fileHash(data), PUBLIC, "application/octet-stream", ByteArray(64),
    )

    val incomplete = ExportGate.evaluate(null, header)
    S.check("incomplete fountain refuses", incomplete is ExportGate.Decision.Refuse)
    if (incomplete is ExportGate.Decision.Refuse) {
        S.check("mentions resume", incomplete.detail.contains("Resume"))
    }

    // The domain must not hand out bytes on a failed verify.
    val session = ReceiveSession()
    session.startCalibration()
    session.onCalibrationResult(true)
    S.check("no bytes in PAIRING", session.verifiedBytes() == null)
    session.abort()
    S.check("no bytes after abort", session.verifiedBytes() == null)
    S.ok("failure paths yield null bytes, never a truncated buffer")
}

private fun testFileNameSanitisation() {
    S.suite("File name sanitisation (attacker-influenced field)")
    S.check("strips unix traversal",
        !ExportGate.sanitiseFileName("../../etc/passwd").contains("/"),
        ExportGate.sanitiseFileName("../../etc/passwd"))
    S.check("strips windows traversal",
        !ExportGate.sanitiseFileName("..\\..\\win.ini").contains("\\"))
    S.check("traversal reduces to basename",
        ExportGate.sanitiseFileName("../../etc/passwd") == "etc_passwd" ||
            ExportGate.sanitiseFileName("../../etc/passwd") == "passwd",
        ExportGate.sanitiseFileName("../../etc/passwd"))
    S.check("strips control chars",
        !ExportGate.sanitiseFileName("a\u0000b\nc.bin").contains("\u0000"))
    S.check("blank becomes a default",
        ExportGate.sanitiseFileName("   ").isNotBlank())
    S.check("leading dots stripped",
        !ExportGate.sanitiseFileName("...hidden").startsWith("."))
    S.check("long names truncated",
        ExportGate.sanitiseFileName("a".repeat(400) + ".bin").length <= 120)
    S.check("extension preserved on truncation",
        ExportGate.sanitiseFileName("a".repeat(400) + ".bin").endsWith(".bin"))
    S.check("normal names untouched",
        ExportGate.sanitiseFileName("voice-note 01.m4a") == "voice-note 01.m4a")
    S.ok("a malicious sender cannot escape the download directory")
}

private fun testEndToEndReceive() {
    S.suite("End-to-end: calibrate -> pair -> receive -> verify -> export")
    val data = ByteArray(4096) { ((it * 17) % 253).toByte() }
    val enc = Fountain.Encoder(data, 48)

    val session = ReceiveSession()
    session.startCalibration()
    session.onCalibrationResult(true)

    val header = Frames.encodeHeader(
        HeaderPayload(
            SESSION_ID, "note.txt", data.size.toLong(), enc.k, 48,
            Crypto.fileHash(data), PUBLIC, "text/plain",
        ),
        SECRET,
    )
    session.ingestFrame(Frames.decode(header))
    session.confirmSasLocal()
    session.confirmSasRemote()
    S.check("receiving after both confirms", session.state == SessionState.RECEIVING)

    // Stream with 22% deterministic loss, as the optical channel would.
    var seed = 0x1234_5678
    fun rnd(): Int {
        seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17)
        seed = seed xor (seed shl 5); return seed
    }
    var sent = 0
    for (id in 0 until enc.recommendedSymbols() + enc.k) {
        if (session.state != SessionState.RECEIVING) break
        if ((rnd() ushr 1) % 100 < 22) continue
        val s = enc.encode(id)
        session.ingestFrame(
            Frames.decode(Frames.encodeData(SESSION_ID, id, s.payload, SECRET), PUBLIC),
        )
        sent++
    }

    S.check("reached COMPLETE", session.state == SessionState.COMPLETE,
        "state=${session.state} recovered=${session.decoder?.doneCount}/${enc.k}")
    val out = session.verifiedBytes()
    S.check("bytes released only when complete", out != null)
    if (out != null) {
        S.check("byte-identical to the original", Bytes.eq(out, data))
        S.check("sha256 matches", Bytes.eq(Crypto.fileHash(out), Crypto.fileHash(data)))
    }
    val d = session.exportDecision
    S.check("export approved", d is ExportGate.Decision.Publish)
    if (d is ExportGate.Decision.Publish) S.info("would write '${d.fileName}' (${d.sizeBytes} B)")
    S.info("k=${enc.k}, delivered $sent symbols through 22% loss")

    // A session that never confirmed SAS can never reach COMPLETE.
    val blocked = ReceiveSession()
    blocked.startCalibration()
    blocked.onCalibrationResult(true)
    blocked.ingestFrame(Frames.decode(header))
    for (id in 0 until enc.recommendedSymbols() + enc.k) {
        val s = enc.encode(id)
        blocked.ingestFrame(
            Frames.decode(Frames.encodeData(SESSION_ID, id, s.payload, SECRET), PUBLIC),
        )
    }
    S.check("unconfirmed session never completes", blocked.state == SessionState.PAIRING,
        "state=${blocked.state}")
    S.check("unconfirmed session yields no bytes", blocked.verifiedBytes() == null)
    S.check("unconfirmed session has no export decision", blocked.exportDecision == null)
    S.ok("the full flow honours both gates end to end")
}
