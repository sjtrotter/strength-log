package cloud.trotter.log.strength.playtest

import androidx.lifecycle.ViewModelStore
import cloud.trotter.log.strength.ui.day.DayViewModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Navigation owns the workout ViewModel stores, and the mutation mutex has no public
// test hook. Keep this access in the harness so production APIs stay unchanged.
internal fun ViewModelStore.workoutMutationLocks(): List<Mutex> = keys().flatMap { key ->
    when (val model = get(key)) {
        is DayViewModel -> listOf(
            DayViewModel::class.java.getDeclaredField("mutationLock").apply { isAccessible = true }.get(model) as Mutex,
        )
        null -> emptyList()
        else -> if (model.javaClass.name == "androidx.navigation.NavControllerViewModel") {
            val stores = model.javaClass.getDeclaredField("viewModelStores").apply { isAccessible = true }.get(model) as Map<*, *>
            stores.values.filterIsInstance<ViewModelStore>().flatMap { it.workoutMutationLocks() }
        } else emptyList()
    }
}

internal suspend fun awaitWorkoutMutations(locks: List<Mutex>) {
    locks.forEach { it.withLock {} }
}
