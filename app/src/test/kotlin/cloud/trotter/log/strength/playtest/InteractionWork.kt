package cloud.trotter.log.strength.playtest

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import cloud.trotter.log.strength.MainActivity
import cloud.trotter.log.strength.ui.day.DayViewModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun ActivityScenario<MainActivity>.workoutMutationLocks(): List<Mutex> {
    if (state == Lifecycle.State.DESTROYED) return emptyList()
    var pending = emptyList<Mutex>()
    onActivity { pending = it.viewModelStore.workoutMutationLocks() }
    return pending
}

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
