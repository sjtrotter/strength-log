package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.domain.model.SprintTime
import cloud.trotter.log.strength.domain.model.StartProtocol
import cloud.trotter.log.strength.domain.model.TimingMethod

/** The three stored sprint timing columns (`cardio_set_result.centis/timingMethod/startProtocol`) as one domain
 *  time: null when all three are null; IllegalArgumentException for a partial triple or an unknown name. */
object CardioTiming {
    fun of(centis: Int?, timingMethod: String?, startProtocol: String?): SprintTime? {
        if (centis == null && timingMethod == null && startProtocol == null) return null
        require(centis != null && timingMethod != null && startProtocol != null)
        return SprintTime(centis, TimingMethod.valueOf(timingMethod), StartProtocol.valueOf(startProtocol))
    }
}
