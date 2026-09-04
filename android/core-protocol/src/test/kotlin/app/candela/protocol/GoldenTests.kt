package app.candela.protocol

import java.io.File

/**
 * Stage 1 + Stage 2 verification against the golden vectors emitted by the frozen
 * TypeScript reference (tools/gen-golden.ts).
 *
 * If any assertion here fails, the Kotlin port is NOT wire-compatible and no
 * Camera2 work should proceed — a divergence in, say, the fountain RNG would show
 * up on-device as "the transfer just never completes", which is close to
 * undebuggable through an optical link.
 */

private lateinit var goldenDir: File

private fun load(name: String): JsonValue =
    JsonParser.parse(File(goldenDir, name).readText())

private fun hex(s: String): ByteArray = Bytes.fromHex(s)

fun main(args: Array<String>) {
    goldenDir = File(
        if (args.isNotEmpty()) args[0]
        else "android/core-protocol/src/test/resources/golden",
    )
    require(goldenDir.isDirectory) { "golden dir not found: ${goldenDir.absolutePath}" }

    println("Candela protocol + vision verification")
    println("golden vectors: ${goldenDir.absolutePath}")

    testBytes()
    testCrc32()
    testCrypto()
    testFrames()
    testSoliton()
    testNeighbors()
    testEncoder()
    testTranscripts()
    testSessionMachine()
    testTamperResistance()

    val code = T.summary()
    kotlin.system.exitProcess(code)
}

private fun testBytes() {
    T.suite("Bytes / endianness")
    T.eqHex("u16be(0)", "0000", Bytes.u16be(0))
    T.eqHex("u16be(65535)", "ffff", Bytes.u16be(65535))
    T.eqHex("u16be(258)", "0102", Bytes.u16be(258))
    T.eqHex("u32be(4294967295)", "ffffffff", Bytes.u32be(4294967295L))
    T.eqHex("u32be(1)", "00000001", Bytes.u32be(1))
    T.eqInt("readU16 high bit", 0xFF80, Bytes.readU16(byteArrayOf(0xFF.toByte(), 0x80.toByte()), 0))
    T.eqLong(
        "readU32 high bit",
        0xFFFFFFFFL,
        Bytes.readU32(byteArrayOf(-1, -1, -1, -1), 0),
    )
    T.eqStr("hex roundtrip", "00ff7f80", Bytes.toHex(hex("00ff7f80")))
    T.check("eq true", Bytes.eq(hex("aabb"), hex("aabb")))
    T.check("eq false", !Bytes.eq(hex("aabb"), hex("aabc")))
    T.check("eq length", !Bytes.eq(hex("aabb"), hex("aabbcc")))
    T.ok("signed-byte widening handled")
}

private fun testCrc32() {
    T.suite("CRC32 vs golden")
    val v = load("crc32.json")
    for (c in v["cases"].arr()) {
        val name = c["name"].str()
        val data = hex(c["dataHex"].str())
        T.eqLong("crc32($name)", c["crc"].num().toLong(), Crc32.compute(data))
    }
    // Independent check against the published IEEE test vector.
    T.eqLong("crc32(\"123456789\") == 0xCBF43926", 0xCBF43926L, Crc32.compute("123456789".toByteArray()))
    T.ok("CRC32 matches TS table and the standard vector")
}

private fun testCrypto() {
    T.suite("Ed25519 / SHA-256 / SAS vs golden")
    val v = load("crypto.json")
    val sk = hex(v["secretKeyHex"].str())
    val expectedPub = v["publicKeyHex"].str()

    val pub = Crypto.signer.publicKey(sk)
    T.eqHex("public key derivation", expectedPub, pub)

    for (s in v["sha256"].arr()) {
        val n = s["len"].int()
        T.eqHex("sha256(len=$n)", s["hashHex"].str(), Crypto.sha256(hex(s["dataHex"].str())))
    }

    // RFC 8032 signing is deterministic, so signatures must match @noble byte-for-byte.
    for (s in v["signatures"].arr()) {
        val n = s["len"].int()
        val msg = hex(s["msgHex"].str())
        val sig = Crypto.sign(msg, sk)
        T.eqHex("ed25519 sign(len=$n)", s["sigHex"].str(), sig)
        T.check("ed25519 verify(len=$n)", Crypto.verify(sig, msg, pub))
        T.check(
            "ed25519 reject tampered msg(len=$n)",
            !Crypto.verify(sig, msg + byteArrayOf(1), pub),
        )
    }

    T.eqStr("SAS digits", v["sas"].str(), Crypto.sasFromPublicKey(pub))
    T.eqStr(
        "session fingerprint",
        v["fingerprint"].str(),
        Crypto.sessionFingerprint(hex(v["sessionIdHex"].str()), pub),
    )
    T.ok("crypto layer is byte-identical to @noble/ed25519")
}

private fun testFrames() {
    T.suite("Frame codec vs golden")
    val v = load("frames.json")
    val sessionId = hex(v["sessionIdHex"].str())
    val pub = hex(v["publicKeyHex"].str())
    val sk = hex(load("crypto.json")["secretKeyHex"].str())

    // CAL
    val calExpected = v["cal"]["rawHex"].str()
    val cal = Frames.encodeCal(sessionId)
    T.eqHex("encodeCal", calExpected, cal)
    val calDec = Frames.decode(cal)
    T.check("decode CAL ok", calDec is DecodeResult.Ok && calDec.frame is Frame.Cal)
    if (calDec is DecodeResult.Ok) {
        T.eqHex("CAL sessionId", v["sessionIdHex"].str(), (calDec.frame as Frame.Cal).sessionId)
    }

    // HEADER
    val h = v["header"]
    val payload = HeaderPayload(
        sessionId = sessionId,
        fileName = h["fileName"].str(),
        fileSize = h["fileSize"].num().toLong(),
        k = h["k"].int(),
        blockSize = h["blockSize"].int(),
        fileHash = hex(h["fileHashHex"].str()),
        publicKey = pub,
        mime = h["mime"].str(),
    )
    val headerRaw = Frames.encodeHeader(payload, sk)
    T.eqHex("encodeHeader", h["rawHex"].str(), headerRaw)

    when (val d = Frames.decode(headerRaw)) {
        is DecodeResult.Ok -> {
            val f = d.frame as Frame.Header
            T.eqStr("HEADER fileName", h["fileName"].str(), f.fileName)
            T.eqLong("HEADER fileSize", h["fileSize"].num().toLong(), f.fileSize)
            T.eqInt("HEADER k", h["k"].int(), f.k)
            T.eqInt("HEADER blockSize", h["blockSize"].int(), f.blockSize)
            T.eqHex("HEADER fileHash", h["fileHashHex"].str(), f.fileHash)
            T.eqStr("HEADER mime", h["mime"].str(), f.mime)
            T.eqHex("HEADER publicKey", v["publicKeyHex"].str(), f.publicKey)
        }
        is DecodeResult.Rejected -> T.check("decode HEADER ok", false, "rejected ${d.reason}")
    }

    // DATA
    for (df in v["data"].arr()) {
        val id = df["symbolId"].int()
        val p = hex(df["payloadHex"].str())
        val raw = Frames.encodeData(sessionId, id, p, sk)
        T.eqHex("encodeData(id=$id)", df["rawHex"].str(), raw)
        when (val d = Frames.decode(raw, pub)) {
            is DecodeResult.Ok -> {
                val f = d.frame as Frame.Data
                T.eqInt("DATA symbolId($id)", id, f.symbolId)
                T.eqHex("DATA payload($id)", df["payloadHex"].str(), f.payload)
            }
            is DecodeResult.Rejected -> T.check("decode DATA($id)", false, "rejected ${d.reason}")
        }
    }
    T.ok("CAL/HEADER/DATA encode+decode byte-identical to TS")
}

private fun testSoliton() {
    T.suite("Robust soliton distribution")
    val v = load("fountain_soliton.json")
    for (s in v["sets"].arr()) {
        val k = s["k"].int()
        val expected = s["mu"].arr().map { it.num() }
        val actual = Fountain.robustSoliton(k)
        T.eqInt("mu size k=$k", expected.size, actual.size)
        var worst = 0.0
        for (i in expected.indices) {
            val d = kotlin.math.abs(expected[i] - actual[i])
            if (d > worst) worst = d
        }
        // Vectors are rounded to 12 dp on the TS side.
        T.check("mu values k=$k", worst < 1e-11, "max delta=$worst")
    }
    T.ok("soliton matches within 1e-11")
}

private fun testNeighbors() {
    T.suite("Fountain neighbours (mulberry32 bit-exactness)")
    val v = load("fountain_neighbors.json")
    var total = 0
    var mismatches = 0
    for (set in v["sets"].arr()) {
        val k = set["k"].int()
        val mu = Fountain.robustSoliton(k)
        var setMismatch = 0
        for (e in set["entries"].arr()) {
            val id = e["id"].int()
            val expected = e["n"].arr().map { it.int() }.toIntArray()
            val actual = Fountain.neighborsFor(id, k, mu)
            total++
            if (!expected.contentEquals(actual)) {
                mismatches++
                setMismatch++
                if (setMismatch <= 2) {
                    T.check(
                        "neighbors k=$k id=$id", false,
                        "expected=${expected.toList()} actual=${actual.toList()}",
                    )
                }
            }
        }
        T.check("all neighbours match k=$k", setMismatch == 0, "$setMismatch mismatches")
    }
    T.info("compared $total neighbour lists, $mismatches mismatches")
    if (mismatches == 0) T.ok("mulberry32 + soliton + sampling are bit-exact vs JS")
}

private fun testEncoder() {
    T.suite("Fountain encoder XOR payloads")
    val v = load("fountain_encode.json")
    val file = hex(v["fileHex"].str())
    val bs = v["blockSize"].int()
    val enc = Fountain.Encoder(file, bs)
    T.eqInt("k", v["k"].int(), enc.k)
    T.eqInt("recommendedSymbols", v["recommendedSymbols"].int(), enc.recommendedSymbols())
    T.eqHex("file sha256", v["fileHashHex"].str(), Crypto.fileHash(file))

    var bad = 0
    for (s in v["symbols"].arr()) {
        val id = s["id"].int()
        val sym = enc.encode(id)
        if (Bytes.toHex(sym.payload) != s["payloadHex"].str()) {
            bad++
            if (bad <= 3) T.eqHex("encode payload id=$id", s["payloadHex"].str(), sym.payload)
        }
        val expectedN = s["neighbors"].arr().map { it.int() }.toIntArray()
        if (!expectedN.contentEquals(sym.neighbors)) {
            T.check("encode neighbours id=$id", false)
        }
    }
    T.check("all encoded payloads match", bad == 0, "$bad mismatches")
    T.ok("systematic LT encoding is byte-identical")
}

private fun testTranscripts() {
    T.suite("End-to-end fountain recovery @ 22% drop")
    for (size in intArrayOf(4096, 32768)) {
        val v = load("transcript_$size.json")
        val k = v["k"].int()
        val bs = v["blockSize"].int()
        val fileSize = v["fileSize"].int()
        val expectHash = v["fileHashHex"].str()

        val t0 = System.nanoTime()
        val dec = Fountain.Decoder(k, bs, fileSize)
        var consumed = 0
        for (s in v["symbols"].arr()) {
            if (dec.isComplete()) break
            consumed++
            dec.ingest(s["id"].int(), hex(s["payloadHex"].str()))
        }
        val ms = (System.nanoTime() - t0) / 1_000_000.0

        T.check("size=$size complete", dec.isComplete(), "recovered ${dec.doneCount}/$k")
        val out = dec.assemble()
        T.check("size=$size assembled", out != null)
        if (out != null) {
            T.eqInt("size=$size length", fileSize, out.size)
            T.eqHex("size=$size sha256", expectHash, Crypto.fileHash(out))
            T.check("size=$size byte-identical", Bytes.eq(out, hex(v["fileHex"].str())))
        }
        T.info(
            "size=$size k=$k kept=${v["keptCount"].int()} dropped=${v["droppedCount"].int()} " +
                "consumed=$consumed recovered=${dec.doneCount} ${"%.0f".format(ms)} ms",
        )
    }
    T.ok("peel + bounded GE recover the file under simulated optical loss")
}

private fun testSessionMachine() {
    T.suite("Session state machine")
    var s = SessionState.IDLE
    s = SessionMachine.next(s, SessionEvent.START_CALIBRATION)!!
    T.check("IDLE -> CALIBRATING", s == SessionState.CALIBRATING)
    s = SessionMachine.next(s, SessionEvent.CALIBRATION_OK)!!
    T.check("CALIBRATING -> PAIRING", s == SessionState.PAIRING)
    s = SessionMachine.next(s, SessionEvent.SAS_CONFIRMED)!!
    T.check("PAIRING -> RECEIVING", s == SessionState.RECEIVING)
    s = SessionMachine.next(s, SessionEvent.ALL_SYMBOLS_IN)!!
    T.check("RECEIVING -> VERIFYING", s == SessionState.VERIFYING)
    val done = SessionMachine.next(s, SessionEvent.VERIFY_OK)!!
    T.check("VERIFYING -> COMPLETE", done == SessionState.COMPLETE)

    // The security-critical rules.
    T.check(
        "cannot skip SAS pairing",
        SessionMachine.next(SessionState.CALIBRATING, SessionEvent.BEGIN_SEND) == null,
    )
    T.check(
        "verify failure aborts, never completes",
        SessionMachine.next(SessionState.VERIFYING, SessionEvent.VERIFY_FAILED) == SessionState.ABORTED,
    )
    T.check(
        "calibration refusal aborts",
        SessionMachine.next(SessionState.CALIBRATING, SessionEvent.CALIBRATION_REFUSED) == SessionState.ABORTED,
    )
    T.check(
        "thermal pause from RECEIVING",
        SessionMachine.next(SessionState.RECEIVING, SessionEvent.THERMAL_PAUSE) == SessionState.PAUSED,
    )
    T.check(
        "resume from PAUSED",
        SessionMachine.next(SessionState.PAUSED, SessionEvent.RESUME) == SessionState.RECEIVING,
    )
    T.check("COMPLETE is terminal", SessionMachine.next(SessionState.COMPLETE, SessionEvent.RESUME) == null)
    T.check("ABORTED is terminal", SessionMachine.next(SessionState.ABORTED, SessionEvent.ABORT) == null)
    T.ok("SAS gate and no-partial-write invariants hold")
}

private fun testTamperResistance() {
    T.suite("Injection / tamper resistance (audit section 5)")
    val v = load("frames.json")
    val sessionId = hex(v["sessionIdHex"].str())
    val pub = hex(v["publicKeyHex"].str())
    val sk = hex(load("crypto.json")["secretKeyHex"].str())

    val payload = hex("00112233445566778899aabbccddeeff")
    val good = Frames.encodeData(sessionId, 42, payload, sk)
    T.check("valid frame accepted", Frames.decode(good, pub) is DecodeResult.Ok)

    // Flip one payload bit: CRC must catch it before the signature is even tried.
    val bitFlip = good.copyOf()
    bitFlip[30] = (bitFlip[30].toInt() xor 0x01).toByte()
    val r1 = Frames.decode(bitFlip, pub)
    T.check(
        "single-bit flip rejected",
        r1 is DecodeResult.Rejected && r1.reason == RejectReason.BAD_CRC,
        "got $r1",
    )

    // Attacker recomputes CRC over tampered payload — signature must catch it.
    val forged = good.copyOf()
    forged[30] = (forged[30].toInt() xor 0x01).toByte()
    val fixedCrc = Crc32.bytes(forged, 0, forged.size - 4)
    fixedCrc.copyInto(forged, forged.size - 4)
    val r2 = Frames.decode(forged, pub)
    T.check(
        "forged-CRC tamper rejected by signature",
        r2 is DecodeResult.Rejected && r2.reason == RejectReason.BAD_SIGNATURE,
        "got $r2",
    )

    // A different sender's key must not validate (stream splicing).
    val attackerSk = ByteArray(32) { 0x11 }
    val attackerFrame = Frames.encodeData(sessionId, 42, payload, attackerSk)
    val r3 = Frames.decode(attackerFrame, pub)
    T.check(
        "spliced attacker stream rejected",
        r3 is DecodeResult.Rejected && r3.reason == RejectReason.BAD_SIGNATURE,
        "got $r3",
    )

    // DATA without an established key is never trusted.
    val r4 = Frames.decode(good, null)
    T.check(
        "unauthenticated DATA rejected",
        r4 is DecodeResult.Rejected && r4.reason == RejectReason.MISSING_KEY,
        "got $r4",
    )

    // Wrong magic / version.
    val badMagic = good.copyOf().also { it[0] = 0x58 }
    T.check(
        "bad magic rejected",
        (Frames.decode(badMagic, pub) as? DecodeResult.Rejected)?.reason == RejectReason.BAD_MAGIC,
    )
    val badVer = good.copyOf().also { it[2] = 9 }
    T.check(
        "bad version rejected",
        (Frames.decode(badVer, pub) as? DecodeResult.Rejected)?.reason == RejectReason.BAD_VERSION,
    )
    T.check("truncated frame rejected", Frames.decode(byteArrayOf(0x43, 0x4C, 1)) is DecodeResult.Rejected)

    // Header signed by attacker must fail.
    val hdr = Frames.encodeHeader(
        HeaderPayload(sessionId, "evil.bin", 100, 3, 48, ByteArray(32), pub, "text/plain"),
        attackerSk,
    )
    T.check(
        "header with mismatched key rejected",
        (Frames.decode(hdr) as? DecodeResult.Rejected)?.reason == RejectReason.BAD_SIGNATURE,
    )
    T.ok("CRC->signature verify order rejects flips, forgeries and splices")
}
