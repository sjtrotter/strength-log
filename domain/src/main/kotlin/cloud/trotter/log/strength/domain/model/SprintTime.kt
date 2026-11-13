package cloud.trotter.log.strength.domain.model

/** How a sprint rep was timed (design §3.1.3). Stored by name; new constants go at the end. */
enum class TimingMethod { PHONE, HAND, VIDEO, GATES }

/** How a timed sprint rep started (design §4.1). Stored by name; new constants go at the end. */
enum class StartProtocol { THREE_POINT, TWO_POINT, BLOCKS, STANDING, FLYING }

/** A timed sprint rep: [centis] hundredths by [timingMethod] from [startProtocol]. The three are written together and
 *  never rewritten, so a time is never read under another method (design §3.1.3). */
data class SprintTime(val centis: Int, val timingMethod: TimingMethod, val startProtocol: StartProtocol) {
    init { require(centis in 1..MAX_CENTIS) }

    companion object { const val MAX_CENTIS = 360_000 }
}
