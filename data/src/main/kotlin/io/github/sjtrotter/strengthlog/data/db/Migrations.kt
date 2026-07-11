package io.github.sjtrotter.strengthlog.data.db

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
