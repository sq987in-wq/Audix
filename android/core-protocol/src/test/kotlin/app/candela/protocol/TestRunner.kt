package app.candela.protocol

/**
 * Tiny assertion/reporting harness.
 *
 * Not JUnit: Maven Central is unreachable from this sandbox, and the whole point
 * of these tests is to run HERE, today, before any Android toolchain exists. The
 * Gradle module declares the same tests as JUnit in CI; this runner is what makes
 * them executable with nothing but kotlinc + a JRE.
 */
object T {
    private var passed = 0
    private var failed = 0
    private val failures = mutableListOf<String>()
    private var currentSuite = ""

    fun suite(name: String) {
        currentSuite = name
        println("\n\u2500\u2500 $name ${"\u2500".repeat(maxOf(2, 58 - name.length))}")
    }

    fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) {
            passed++
        } else {
            failed++
            val msg = "[$currentSuite] $name${if (detail.isEmpty()) "" else " :: $detail"}"
            failures.add(msg)
            println("  FAIL  $name${if (detail.isEmpty()) "" else "  ($detail)"}")
        }
    }

    fun eqHex(name: String, expectedHex: String, actual: ByteArray) {
        val a = Bytes.toHex(actual)
        check(name, a.equals(expectedHex, ignoreCase = true), "expected=$expectedHex actual=$a")
    }

    fun eqInt(name: String, expected: Int, actual: Int) =
        check(name, expected == actual, "expected=$expected actual=$actual")

    fun eqLong(name: String, expected: Long, actual: Long) =
        check(name, expected == actual, "expected=$expected actual=$actual")

    fun eqStr(name: String, expected: String, actual: String) =
        check(name, expected == actual, "expected='$expected' actual='$actual'")

    fun eqDouble(name: String, expected: Double, actual: Double, tol: Double = 1e-9) =
        check(name, kotlin.math.abs(expected - actual) <= tol, "expected=$expected actual=$actual")

    fun info(msg: String) = println("  \u2022 $msg")
    fun ok(msg: String) = println("  \u2713 $msg")

    fun summary(): Int {
        println("\n" + "=".repeat(64))
        if (failed == 0) {
            println("ALL TESTS PASSED   $passed assertions, 0 failures")
        } else {
            println("FAILURES: $failed  (passed $passed)")
            failures.take(40).forEach { println("   - $it") }
        }
        println("=".repeat(64))
        return if (failed == 0) 0 else 1
    }
}
