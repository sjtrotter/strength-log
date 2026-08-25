package cloud.trotter.log.strength.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2 (tracking types, P3): purely additive columns for the new REPS/TIMED
 * exercise tracking. Every column carries a DEFAULT so existing rows keep their
 * exact meaning — a pre-v2 `session_set` reads back as weight×reps with
 * `seconds = 0`, and a pre-v2 `custom_exercise` stays WEIGHTED. No row is
 * rewritten or dropped, so there is nothing to lose.
 *
 * The reinterpretation of *legacy live logs* for entries that were reclassified
 * to TIMED (reps→seconds) is deliberately NOT done here: it is a one-shot,
 * DataStore-flagged data fixup (see `LegacyTimedFixup`), not a schema change, so
 * it can be reasoned about and tested on its own and never runs twice.
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
 * v2 → v3 (per-set timing, #85): two nullable columns on archived sets holding the
 * wall-clock millis the watch observed for a set's start and its tick. Nullable
 * with no DEFAULT deliberately — every already-archived set genuinely has no
 * observed timing, and NULL ("not observed") must stay distinguishable from 0
 * ("started at the epoch"), which any downstream active-time math would swallow.
 * The live log needs no migration: it is JSON in `exercise_log.setsJson` and the
 * two `SetDto` fields default to null there.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE session_set ADD COLUMN startedAtMillis INTEGER")
        db.execSQL("ALTER TABLE session_set ADD COLUMN completedAtMillis INTEGER")
    }
}

/**
 * v3 → v4 (unknown bodyweight, #171): `workout_session.bodyweightLb` becomes
 * nullable. SQLite can't relax NOT NULL in place, so the table is rebuilt and
 * copied — it carries no index and nothing references it by foreign key, so the
 * rebuild is a straight copy.
 *
 * The copy also rewrites `0` to NULL. A session written by the app always
 * recorded the configured (positive) bodyweight; a 0 could only come from a CSV
 * import, which has no bodyweight column and used to fabricate one. Those rows
 * are exactly the unknown ones, and leaving them at 0 would keep poisoning the
 * calorie estimate and every full backup.
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
        // issued — carry the old high-water mark forward when it's higher
        // (sessions may have been deleted above the survivors).
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
 * v4 → v5 (restore commit marker, #172): one new single-row table, purely
 * additive. No existing table is touched, and an empty `restore_marker` is
 * exactly what "no restore is outstanding" means, so a device arriving here has
 * nothing to backfill.
 *
 * The table exists so a full restore's destructive transaction can record
 * *itself* — the one fact Room and DataStore can agree on afterwards; see
 * [cloud.trotter.log.strength.data.db.entity.RestoreMarkerEntity]. It is
 * deliberately absent from the full backup: it is recovery bookkeeping about
 * this device mid-restore, not something a user owns.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `restore_marker` (" +
                "`id` INTEGER NOT NULL, `nonce` TEXT NOT NULL, PRIMARY KEY(`id`))",
        )
    }
}

/** v5 → v6 (cardio history, #154): additive append-only cardio sessions. */
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
