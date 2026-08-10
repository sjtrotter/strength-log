package cloud.trotter.log.strength.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import cloud.trotter.log.strength.data.db.dao.CustomExerciseDao
import cloud.trotter.log.strength.data.db.dao.CardioSessionDao
import cloud.trotter.log.strength.data.db.dao.ProgramDao
import cloud.trotter.log.strength.data.db.dao.RestoreMarkerDao
import cloud.trotter.log.strength.data.db.dao.SessionDao
import cloud.trotter.log.strength.data.db.entity.CustomExerciseEntity
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.ProgramDayEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.db.entity.RestoreMarkerEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity

@Database(
    entities = [
        ProgramDayEntity::class,
        ProgramExerciseEntity::class,
        ExerciseLogEntity::class,
        WorkoutSessionEntity::class,
        SessionSetEntity::class,
        CustomExerciseEntity::class,
        RestoreMarkerEntity::class,
        CardioSessionEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class StrengthDatabase : RoomDatabase() {
    abstract fun programDao(): ProgramDao
    abstract fun sessionDao(): SessionDao
    abstract fun customExerciseDao(): CustomExerciseDao
    abstract fun restoreMarkerDao(): RestoreMarkerDao
    abstract fun cardioSessionDao(): CardioSessionDao

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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
    }
}
