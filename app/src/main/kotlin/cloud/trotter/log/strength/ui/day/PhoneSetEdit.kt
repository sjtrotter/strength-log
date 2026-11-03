package cloud.trotter.log.strength.ui.day

import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.seeding.SetEdit

internal fun phoneTickEdit(current: LoggedSet, checked: Boolean, loggedAt: Long, count: Int? = null): SetEdit =
    SetEdit(
        done = checked,
        reps = count,
        seconds = if (count != null) 0 else null,
        completedAtMillis = loggedAt.takeIf { checked && !current.done },
    )
