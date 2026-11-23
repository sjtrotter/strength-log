package cloud.trotter.log.strength.domain.records

import cloud.trotter.log.strength.domain.standards.TestRef
import cloud.trotter.log.strength.domain.standards.TestStop

data class TestResult(val ref: TestRef, val completedAt: Long, val count: Int?, val seconds: Int?, val stop: TestStop) {
    init { require((count == null) != (seconds == null)) }
    val value: Int get() = count ?: requireNotNull(seconds)
}

/** Results compare only within one identity; a 1:00 count never meets a 2:00 count. */
object TestProgress {
    fun compatible(a: TestRef, b: TestRef): Boolean = a == b

    fun best(ref: TestRef, results: List<TestResult>): Int? =
        results.filter { compatible(it.ref, ref) }.maxOfOrNull { it.value }

    fun met(required: Int, best: Int?): Boolean = best != null && best >= required

    fun newest(ref: TestRef, results: List<TestResult>): TestResult? =
        results.filter { compatible(it.ref, ref) }.maxByOrNull { it.completedAt }
}
