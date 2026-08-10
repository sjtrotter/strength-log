package cloud.trotter.log.strength.wear.glance

import android.content.Context
import cloud.trotter.log.strength.wear.R

internal fun dayGlanceCopy(context: Context) = DayGlanceCopy(
    noProgram = context.getString(R.string.dial_no_program),
    setUpOnPhone = context.getString(R.string.glance_set_up_on_phone),
    dayTitle = { day, title -> context.getString(R.string.glance_day_title, day, title) },
    dayOnly = { context.getString(R.string.glance_day_only, it) },
    liftsSets = { lifts, sets ->
        val id = when {
            lifts == 1 && sets == 1 -> R.string.glance_lift_set
            lifts == 1 -> R.string.glance_lift_sets
            sets == 1 -> R.string.glance_lifts_set
            else -> R.string.glance_lifts_sets
        }
        context.getString(id, lifts, sets)
    },
    progressSets = { done, total -> context.getString(R.string.glance_progress_sets, done, total) },
    doneSets = {
        context.getString(if (it == 1) R.string.glance_done_set else R.string.glance_done_sets, it)
    },
    noProgramDescription = context.getString(R.string.glance_no_program_description),
    doneDescription = { day, total -> context.getString(R.string.glance_done_description, day, total) },
    progressDescription = { day, done, total ->
        context.getString(R.string.glance_progress_description, day, done, total)
    },
    emptyShortText = context.getString(R.string.glance_empty_short_text),
    ratio = { done, total -> context.getString(R.string.glance_ratio, done, total) },
)
