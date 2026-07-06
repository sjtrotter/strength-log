package io.github.sjtrotter.strengthlog.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.sjtrotter.strengthlog.data.db.dao.CustomExerciseDao
import io.github.sjtrotter.strengthlog.data.db.dao.ProgramDao
import io.github.sjtrotter.strengthlog.data.db.dao.SessionDao
import io.github.sjtrotter.strengthlog.data.db.entity.CustomExerciseEntity
import io.github.sjtrotter.strengthlog.data.db.entity.ExerciseLogEntity
import io.github.sjtrotter.strengthlog.data.db.entity.ProgramDayEntity
import io.github.sjtrotter.strengthlog.data.db.entity.ProgramExerciseEntity
import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.data.db.entity.WorkoutSessionEntity

@Database(
    entities = [
        ProgramDayEntity::class,
        ProgramExerciseEntity::class,
        ExerciseLogEntity::class,
        WorkoutSessionEntity::class,
        SessionSetEntity::class,
        CustomExerciseEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class StrengthDatabase : RoomDatabase() {
    abstract fun programDao(): ProgramDao
    abstract fun sessionDao(): SessionDao
    abstract fun customExerciseDao(): CustomExerciseDao

    companion object {
        private const val NAME = "strength.db"

        /**
         * Builds the app database. WAL is Room's default on API 16+ but we set it
         * explicitly to document the choice: it lets reads run concurrently with
         * the immediate per-mutation commits the persistence hardening requires
         * (PLAN.md A6).
         */
        fun build(context: Context): StrengthDatabase =
            Room.databaseBuilder(context.applicationContext, StrengthDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
