package cloud.trotter.log.strength.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2 (tracking types): purely additive columns for the new REPS/TIMED
 * exercise tracking. Every column carries a DEFAULT so existing rows keep their
 * exact meaning — a pre-v2 `session_set` reads back as weight×reps with
 * `seconds = 0`, and a pre-v2 `custom_exercise` stays WEIGHTED. No row is
 * rewritten or dropped, so there is nothing to lose.
 *
 * The reinterpretation of *legacy live logs* for entries that were reclassified
 * to TIMED (reps→seconds) is NOT done here: it is a one-shot, DataStore-flagged
 * data fixup (see `LegacyTimedFixup`), not a schema change, so it can be
 * reasoned about and tested on its own and never runs twice.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE session_set ADD COLUMN seconds INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE custom_exercise ADD COLUMN tracking TEXT NOT NULL DEFAULT 'WEIGHTED'")
        db.execSQL("ALTER TABLE custom_exercise ADD COLUMN targetReps INTEGER")
        db.execSQL("ALTER TABLE custom_exercise ADD COLUMN targetSeconds INTEGER")
    }
}

/**
 * v2 → v3 (per-set timing): two nullable columns on archived sets holding the
 * wall-clock millis the watch observed for a set's start and its tick. Nullable
 * with no DEFAULT — every already-archived set genuinely has no observed timing,
 * and NULL ("not observed") must stay distinguishable from 0 ("started at the
 * epoch"), which any downstream active-time math would swallow. The live log needs
 * no migration: it is JSON in `exercise_log.setsJson` and the two `SetDto` fields
 * default to null there.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE session_set ADD COLUMN startedAtMillis INTEGER")
        db.execSQL("ALTER TABLE session_set ADD COLUMN completedAtMillis INTEGER")
    }
}

/**
 * v3 → v4 (unknown bodyweight): `workout_session.bodyweightLb` becomes
 * nullable. SQLite can't relax NOT NULL in place, so the table is rebuilt and
 * copied — it carries no index and nothing references it by foreign key, so the
 * rebuild is a straight copy.
 *
 * The copy also rewrites `0` to NULL. A session written by the app always
 * recorded the configured (positive) bodyweight; a 0 identifies a legacy CSV
 * import, which has no bodyweight column. Those rows are exactly the unknown
 * ones, and leaving them at 0 would keep poisoning the calorie estimate and
 * every full backup.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `workout_session_new` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT NOT NULL, " +
                "`dayTitle` TEXT NOT NULL, `startedAt` INTEGER, `completedAt` INTEGER NOT NULL, " +
                "`bodyweightLb` INTEGER)",
        )
        db.execSQL(
            "INSERT INTO `workout_session_new` (id, dayId, dayTitle, startedAt, completedAt, bodyweightLb) " +
                "SELECT id, dayId, dayTitle, startedAt, completedAt, NULLIF(bodyweightLb, 0) FROM `workout_session`",
        )
        // The copy seeds the new table's sqlite_sequence with the highest
        // SURVIVING id, but AUTOINCREMENT promises no reuse of any id ever
        // issued — carry the source table's high-water mark forward when
        // it's higher (sessions may have been deleted above the
        // survivors).
        db.execSQL(
            "INSERT INTO sqlite_sequence(name, seq) " +
                "SELECT 'workout_session_new', old.seq FROM sqlite_sequence AS old " +
                "WHERE old.name = 'workout_session' " +
                "AND NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = 'workout_session_new')",
        )
        db.execSQL(
            "UPDATE sqlite_sequence SET seq = " +
                "(SELECT old.seq FROM sqlite_sequence AS old WHERE old.name = 'workout_session') " +
                "WHERE name = 'workout_session_new' " +
                "AND seq < (SELECT old.seq FROM sqlite_sequence AS old WHERE old.name = 'workout_session')",
        )
        db.execSQL("DROP TABLE `workout_session`")
        db.execSQL("ALTER TABLE `workout_session_new` RENAME TO `workout_session`")
    }
}

/**
 * v4 → v5 (restore commit marker): one new single-row table, purely additive.
 * No existing table is touched, and an empty `restore_marker` is exactly what
 * "no restore is outstanding" means, so a device arriving here has nothing to
 * backfill.
 *
 * The table exists so a full restore's destructive transaction can record
 * *itself* — the one fact Room and DataStore can agree on afterwards; see
 * [cloud.trotter.log.strength.data.db.entity.RestoreMarkerEntity]. It is
 * absent from the full backup: it is recovery bookkeeping about this device
 * mid-restore, not something a user owns.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `restore_marker` (" +
                "`id` INTEGER NOT NULL, `nonce` TEXT NOT NULL, PRIMARY KEY(`id`))",
        )
    }
}

/** v5 → v6 (cardio history): additive append-only cardio sessions. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `cardio_session` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` TEXT, " +
                "`mode` TEXT NOT NULL, `hard` INTEGER NOT NULL, `label` TEXT NOT NULL, " +
                "`startedAt` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, " +
                "`seconds` INTEGER NOT NULL, `stepsCompleted` INTEGER NOT NULL)",
        )
    }
}

/** v6 -> v7: classify program days; every existing day is a strength day. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE program_day ADD COLUMN kind TEXT NOT NULL DEFAULT 'STRENGTH'")
    }
}

/** v7 → v8 (saved plans): the existing program becomes plan 1. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `plan` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`origin` TEXT NOT NULL, `position` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`archived` INTEGER NOT NULL)",
        )
        db.execSQL(
            "INSERT INTO `plan` (id, name, origin, position, createdAt, archived) " +
                "SELECT 1, 'My plan', 'GENERATED', 0, ?, 0 " +
                "WHERE EXISTS (SELECT 1 FROM `program_day` LIMIT 1)",
            arrayOf(System.currentTimeMillis()),
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `program_day_new` (" +
                "`planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, `emphasisLine` TEXT NOT NULL, `cardioJson` TEXT, " +
                "`kind` TEXT NOT NULL, PRIMARY KEY(`planId`, `dayId`))",
        )
        db.execSQL(
            "INSERT INTO `program_day_new` (planId, dayId, position, title, emphasisLine, cardioJson, kind) " +
                "SELECT 1, dayId, position, title, emphasisLine, cardioJson, kind FROM `program_day`",
        )
        db.execSQL("DROP TABLE `program_day`")
        db.execSQL("ALTER TABLE `program_day_new` RENAME TO `program_day`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_program_day_planId` ON `program_day` (`planId`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `program_exercise_new` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `planId` INTEGER NOT NULL, " +
                "`dayId` TEXT NOT NULL, `position` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, " +
                "`isMain` INTEGER NOT NULL, `targetSets` INTEGER NOT NULL, " +
                "`repSchemeLabel` TEXT NOT NULL, `hasWarmupHint` INTEGER NOT NULL, " +
                "`supersetExerciseId` TEXT, `note` TEXT NOT NULL)",
        )
        db.execSQL(
            "INSERT INTO `program_exercise_new` (id, planId, dayId, position, exerciseId, isMain, " +
                "targetSets, repSchemeLabel, hasWarmupHint, supersetExerciseId, note) " +
                "SELECT id, 1, dayId, position, exerciseId, isMain, targetSets, repSchemeLabel, " +
                "hasWarmupHint, supersetExerciseId, note FROM `program_exercise`",
        )
        // Keep AUTOINCREMENT's old high-water mark even when its highest issued
        // exercise row had already been deleted before this migration.
        db.execSQL(
            "INSERT INTO sqlite_sequence(name, seq) " +
                "SELECT 'program_exercise_new', old.seq FROM sqlite_sequence AS old " +
                "WHERE old.name = 'program_exercise' " +
                "AND NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = 'program_exercise_new')",
        )
        db.execSQL(
            "UPDATE sqlite_sequence SET seq = " +
                "(SELECT old.seq FROM sqlite_sequence AS old WHERE old.name = 'program_exercise') " +
                "WHERE name = 'program_exercise_new' " +
                "AND seq < (SELECT old.seq FROM sqlite_sequence AS old WHERE old.name = 'program_exercise')",
        )
        db.execSQL("DROP INDEX IF EXISTS `index_program_exercise_dayId`")
        db.execSQL("DROP TABLE `program_exercise`")
        db.execSQL("ALTER TABLE `program_exercise_new` RENAME TO `program_exercise`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_program_exercise_planId_dayId` " +
                "ON `program_exercise` (`planId`, `dayId`)",
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `exercise_log_new` (" +
                "`planId` INTEGER NOT NULL, `dayId` TEXT NOT NULL, `programExerciseId` INTEGER NOT NULL, " +
                "`slot` TEXT NOT NULL, `setsJson` TEXT NOT NULL, `checkDate` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`planId`, `dayId`, `programExerciseId`, `slot`))",
        )
        db.execSQL(
            "INSERT INTO `exercise_log_new` (planId, dayId, programExerciseId, slot, setsJson, checkDate, updatedAt) " +
                "SELECT 1, dayId, programExerciseId, slot, setsJson, checkDate, updatedAt FROM `exercise_log`",
        )
        db.execSQL("DROP INDEX IF EXISTS `index_exercise_log_dayId`")
        db.execSQL("DROP TABLE `exercise_log`")
        db.execSQL("ALTER TABLE `exercise_log_new` RENAME TO `exercise_log`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_exercise_log_planId_dayId` " +
                "ON `exercise_log` (`planId`, `dayId`)",
        )

        db.execSQL("ALTER TABLE `workout_session` ADD COLUMN `planId` INTEGER")
        db.execSQL("ALTER TABLE `workout_session` ADD COLUMN `planName` TEXT")
        db.execSQL("ALTER TABLE `cardio_session` ADD COLUMN `planId` INTEGER")
        db.execSQL("ALTER TABLE `cardio_session` ADD COLUMN `planName` TEXT")
    }
}

/** v8 → v9: plan-owned generator inputs and rotation position. */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE plan ADD COLUMN recipeJson TEXT")
        db.execSQL("ALTER TABLE plan ADD COLUMN suggestedDayId TEXT")
    }
}

/** Authored targets are absent on all existing, generated slots. */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE program_exercise ADD COLUMN prescriptionJson TEXT")
    }
}

/** v10 → v11: authored cardio blocks; legacy suggestions remain unchanged. */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE program_day ADD COLUMN cardioBlockJson TEXT")
    }
}

/** v11 → v12: per-set cardio history; legacy sessions remain summaries. */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cardio_session ADD COLUMN activity TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `cardio_set_result` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `cardioSessionId` INTEGER NOT NULL, " +
                "`position` INTEGER NOT NULL, `specJson` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                "`elapsedSeconds` INTEGER NOT NULL, `metres` INTEGER, `distanceSource` TEXT)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_cardio_set_result_cardioSessionId` " +
                "ON `cardio_set_result` (`cardioSessionId`)",
        )
    }
}
