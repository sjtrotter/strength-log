package cloud.trotter.log.strength.ui.day

import android.os.Parcelable
import cloud.trotter.log.strength.domain.generator.CardioBlocks
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.model.Measure
import kotlinx.parcelize.Parcelize

enum class CardioTemplate { EASY, TEMPO, INTERVALS }

/** Canonical seconds/metres and the open set page survive process death together. */
@Parcelize
data class CardioSetDraft(
    val role: CardioRole = CardioRole.WORK,
    val endIsTime: Boolean = true,
    val seconds: Int = 120,
    val metres: Int = 400,
    val hasAim: Boolean = false,
    val hard: Boolean = false,
) : Parcelable {
    fun spec(): CardioSetSpec = CardioSetSpec(
        role = role,
        end = if (endIsTime) Measure.Time(seconds) else Measure.Distance(metres),
        aim = if (!hasAim) null else if (endIsTime) Measure.Distance(metres) else Measure.Time(seconds),
        hard = hard,
    )

    companion object {
        fun from(spec: CardioSetSpec): CardioSetDraft {
            val measures = listOfNotNull(spec.end, spec.aim)
            return CardioSetDraft(
                role = spec.role, endIsTime = spec.end is Measure.Time,
                seconds = measures.filterIsInstance<Measure.Time>().firstOrNull()?.seconds ?: 120,
                metres = measures.filterIsInstance<Measure.Distance>().firstOrNull()?.metres ?: 400,
                hasAim = spec.aim != null, hard = spec.hard,
            )
        }
    }
}

@Parcelize
data class CardioBlockDraft(
    val planId: Long,
    val dayId: String,
    val activity: CardioActivity,
    val sets: List<CardioSetDraft>,
    val editingSet: Int? = null,
) : Parcelable {
    fun block(): CardioBlock = CardioBlock(activity, sets.map(CardioSetDraft::spec))

    fun startFrom(template: CardioTemplate): CardioBlockDraft = from(
        planId, dayId, CardioBlocks.fromLegacy(
            CardioSuggestion("", "", hard = template != CardioTemplate.EASY),
            template == CardioTemplate.INTERVALS, activity,
        ),
    )

    /** Duplicate the last work/recovery pair ahead of any cool-down. */
    fun addSet(): CardioBlockDraft {
        val work = sets.indexOfLast { it.role == CardioRole.WORK }
        val added = if (work < 0) listOf(CardioSetDraft()) else buildList {
            add(sets[work])
            sets.getOrNull(work + 1)?.takeIf { it.role == CardioRole.RECOVERY }?.let(::add)
        }
        val insertion = sets.indexOfFirst { it.role == CardioRole.COOLDOWN }.takeIf { it >= 0 } ?: sets.size
        return copy(sets = sets.take(insertion) + added + sets.drop(insertion), editingSet = null)
    }

    fun removeSet(index: Int): CardioBlockDraft =
        if (sets.size <= 1 || index !in sets.indices) this
        else copy(sets = sets.filterIndexed { position, _ -> position != index }, editingSet = null)

    fun moveSet(index: Int, up: Boolean): CardioBlockDraft {
        val neighbour = index + if (up) -1 else 1
        if (index !in sets.indices || neighbour !in sets.indices) return this
        val reordered = sets.toMutableList()
        reordered[index] = sets[neighbour]
        reordered[neighbour] = sets[index]
        return copy(sets = reordered, editingSet = neighbour.takeIf { editingSet != null })
    }

    companion object {
        fun from(planId: Long, dayId: String, block: CardioBlock): CardioBlockDraft =
            CardioBlockDraft(planId, dayId, block.activity, block.sets.map(CardioSetDraft::from))

        fun new(planId: Long, dayId: String): CardioBlockDraft =
            CardioBlockDraft(planId, dayId, CardioActivity.ROAD_RUN, emptyList()).startFrom(CardioTemplate.EASY)
    }
}
