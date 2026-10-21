package app.musikbox.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PlaylistEntity::class, PlaylistPrefs::class, SongEntity::class, PlaylistTrackEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class MusikboxDatabase : RoomDatabase() {
    // v2: per-field change stamps on playlist prefs, for device sync. Keeps favourites & download list.
    abstract fun playlistDao(): PlaylistDao
    abstract fun trackDao(): TrackDao

    companion object {
        fun build(context: Context): MusikboxDatabase =
            Room.databaseBuilder(context, MusikboxDatabase::class.java, "musikbox.db")
                // Everything except playlist prefs is a mirror of the server; safe to rebuild.
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE playlist_prefs ADD COLUMN favoriteChangedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE playlist_prefs ADD COLUMN offlineChangedAt INTEGER NOT NULL DEFAULT 0")
    }
}
