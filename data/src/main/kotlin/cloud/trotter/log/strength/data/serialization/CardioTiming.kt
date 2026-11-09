package cloud.trotter.log.strength.data.serialization

/** The one check for a stored sprint timing name (`cardio_set_result.timingMethod/startProtocol`, sprint goal
 *  attempts) until PR 5 gives them enums: an upper-case identifier such as PHONE or THREE_POINT. */
object CardioTiming {
    const val MAX_CENTIS = 360_000
    fun isName(value: String): Boolean = NAME.matches(value)
    private val NAME = Regex("[A-Z][A-Z_]{0,23}")
}
