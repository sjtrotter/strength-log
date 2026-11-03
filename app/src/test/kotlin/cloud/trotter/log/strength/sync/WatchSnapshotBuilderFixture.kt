package cloud.trotter.log.strength.sync

import cloud.trotter.log.strength.data.DayTracks
import cloud.trotter.log.strength.data.LoggedSlot
import cloud.trotter.log.strength.data.PerformedDay
import cloud.trotter.log.strength.data.ProgramSlot
import cloud.trotter.log.strength.data.catalog.ExerciseCatalog
import cloud.trotter.log.strength.domain.generator.WizardAnswers
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.LifterConfig
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.standards.RestSettings
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.domain.units.WeightUnit

// The frozen assertion lines and Room integration fixture retain their original calls.
internal fun WatchSnapshotBuilder.build(
    program: Program,
    suggestedDayId: String?,
    slots: List<ProgramSlot>,
    logs: List<LoggedSlot>,
    cfg: LifterConfig,
    catalog: ExerciseCatalog,
    unit: WeightUnit,
    revision: Long,
    restSettings: RestSettings = RestSettings(),
    equipment: Set<Equipment> = Equipment.entries.toSet(),
    planId: Long = 1L,
    planName: String? = null,
    runId: String? = null,
    processedEditStamps: List<Long>? = null,
): WatchSnapshot? {
    val day = PerformedDay.of(
        planId, suggestedDayId ?: return null, program, slots,
        DayTracks(runId, false, logs),
        WizardAnswers(config = cfg, equipment = equipment), catalog,
    )
    return build(
        day = day,
        unit = unit,
        revision = revision,
        restSettings = restSettings,
        planName = planName,
        processedEditStamps = processedEditStamps,
    )
}
