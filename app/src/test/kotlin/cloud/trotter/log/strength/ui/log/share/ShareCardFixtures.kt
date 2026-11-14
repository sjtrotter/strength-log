package cloud.trotter.log.strength.ui.log.share

import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.model.SetKind

internal fun nineExerciseShareSets(): List<SessionSetEntity> {
    fun set(id: String, name: String, weight: Double = 0.0, reps: Int = 0, seconds: Int = 0) = SessionSetEntity(
        id = 0, sessionId = 0, exerciseId = id, exerciseName = name, slot = Slot.MAIN,
        setIndex = 0, kind = SetKind.WORK.name, weightLb = weight, reps = reps, done = true, seconds = seconds,
    )
    return listOf(
        set("bb_back_squat", "Barbell Back Squat", 275.0, 5),
        set("romanian_deadlift", "Romanian Deadlift", 205.0, 6),
        set("bb_bench", "Barbell Bench Press", 185.0, 5),
        set("bb_row", "Barbell Row", 155.0, 8),
        set("db_curl", "Dumbbell Curl", 35.0, 10),
        set("lateral_raise", "Dumbbell Lateral Raise", 25.0, 12),
        set("pullup", "Pull-up", reps = 12),
        set("plank", "Plank", seconds = 75),
        set("face_pull", "Face Pull", 40.0, 12),
    )
}
