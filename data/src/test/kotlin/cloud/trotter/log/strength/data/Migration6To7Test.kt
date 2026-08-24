package cloud.trotter.log.strength.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.MIGRATION_6_7
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration6To7Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-6-to-7-test.db"

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun `existing program days become strength days`() {
        val callback = object : SupportSQLiteOpenHelper.Callback(6) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE program_day (dayId TEXT NOT NULL PRIMARY KEY, position INTEGER NOT NULL, title TEXT NOT NULL, emphasisLine TEXT NOT NULL, cardioJson TEXT)")
                db.execSQL("INSERT INTO program_day VALUES ('A',0,'Day A','Push',NULL)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        )
        val db = helper.writableDatabase
        MIGRATION_6_7.migrate(db)
        db.query("SELECT kind FROM program_day WHERE dayId='A'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("STRENGTH", cursor.getString(0))
        }
        db.execSQL("INSERT INTO program_day VALUES ('C1',1,'Cardio + Core','Zone 2',NULL,'CARDIO')")
        db.query("SELECT kind FROM program_day WHERE dayId='C1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("CARDIO", cursor.getString(0))
        }
        db.close(); helper.close()
    }
}
