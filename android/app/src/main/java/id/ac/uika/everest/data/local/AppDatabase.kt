package id.ac.uika.everest.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        NoteEntity::class, ChunkEntity::class, SegmentEntity::class, SegmentFts::class,
        TrackSessionEntity::class, LocationPointEntity::class, PanicEntity::class,
        ReportEntity::class, ContactEntity::class, FarhanMessageEntity::class, OutboxEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun chunks(): ChunkDao
    abstract fun segments(): SegmentDao
    abstract fun tracks(): TrackDao
    abstract fun panics(): PanicDao
    abstract fun reports(): ReportDao
    abstract fun contacts(): ContactDao
    abstract fun farhan(): FarhanDao
    abstract fun outbox(): OutboxDao

    companion object {
        fun build(context: Context, key: ByteArray): AppDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, AppDatabase::class.java, "everest.db")
                .openHelperFactory(SupportOpenHelperFactory(key))
                .build()
        }
    }
}
