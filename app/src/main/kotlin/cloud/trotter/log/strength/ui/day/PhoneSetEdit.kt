package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.seeding.SetEdit

internal fun phoneTickEdit(current: LoggedSet, checked: Boolean, loggedAt: Long, count: Int? = null, hold: HoldEvent? = null): SetEdit =
    SetEdit(
        done = checked,
        reps = count,
        seconds = if (count != null) 0 else hold?.loggedSeconds,
        startedAtMillis = hold?.startedAtMillis,
        completedAtMillis = (hold?.endedAtMillis ?: loggedAt).takeIf { checked && !current.done },
    )
