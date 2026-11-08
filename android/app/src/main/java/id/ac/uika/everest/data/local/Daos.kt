package id.ac.uika.everest.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class SegmentHit(
    val noteId: String,
    val title: String,
    val startedAt: Long,
    val startMs: Long,
    val text: String,
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE deleted = 0 ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observe(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE deleted = 0 AND startedAt >= :from AND startedAt < :to ORDER BY startedAt")
    suspend fun between(from: Long, to: Long): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE deleted = 0 AND status = 'selesai' ORDER BY startedAt DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<NoteEntity>

    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Query("UPDATE notes SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: String, status: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE notes SET synced = 1 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long)

    @Query("UPDATE notes SET audioUploaded = 1 WHERE id = :id")
    suspend fun markAudioUploaded(id: String)

    @Query("SELECT * FROM notes WHERE synced = 0 AND status IN ('selesai','gagal') LIMIT :limit")
    suspend fun unsynced(limit: Int): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE status IN ('memproses','menunggu_model')")
    suspend fun pending(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE status = 'merekam'")
    suspend fun orphanRecordings(): List<NoteEntity>
}

@Dao
interface ChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chunk: ChunkEntity)

    @Query("SELECT * FROM chunks WHERE noteId = :noteId ORDER BY idx")
    suspend fun forNote(noteId: String): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE done = 0 ORDER BY noteId, idx")
    suspend fun allPending(): List<ChunkEntity>

    @Query("UPDATE chunks SET done = 1 WHERE noteId = :noteId AND idx = :idx")
    suspend fun markDone(noteId: String, idx: Int)

    @Query("SELECT COUNT(*) FROM chunks WHERE noteId = :noteId AND done = 0")
    suspend fun remaining(noteId: String): Int

    @Query("DELETE FROM chunks WHERE noteId = :noteId")
    suspend fun deleteForNote(noteId: String)
}

@Dao
interface SegmentDao {
    @Query("SELECT * FROM segments WHERE noteId = :noteId ORDER BY startMs")
    fun observeForNote(noteId: String): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segments WHERE noteId = :noteId ORDER BY startMs")
    suspend fun forNote(noteId: String): List<SegmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(segments: List<SegmentEntity>)

    @Query("DELETE FROM segments WHERE noteId = :noteId")
    suspend fun deleteForNote(noteId: String)

    @Query("DELETE FROM segments WHERE noteId = :noteId AND startMs >= :fromMs AND startMs < :toMs")
    suspend fun deleteRange(noteId: String, fromMs: Long, toMs: Long)

    @Query("UPDATE segments SET text = :text WHERE id = :id")
    suspend fun updateText(id: String, text: String)

    @Query("DELETE FROM segments WHERE id = :id")
    suspend fun delete(id: String)

    /** Cari teks penuh (sintaks MATCH FTS4), opsional dibatasi rentang tanggal rekaman. */
    @Query(
        """SELECT s.noteId AS noteId, n.title AS title, n.startedAt AS startedAt, s.startMs AS startMs, s.text AS text
           FROM segments_fts f
           JOIN segments s ON s.rowid = f.rowid
           JOIN notes n ON n.id = s.noteId
           WHERE segments_fts MATCH :query AND n.deleted = 0 AND n.startedAt >= :from AND n.startedAt < :to
           ORDER BY n.startedAt DESC, s.startMs
           LIMIT :limit""",
    )
    suspend fun search(query: String, from: Long = 0, to: Long = Long.MAX_VALUE, limit: Int = 30): List<SegmentHit>

    @Query(
        """SELECT s.noteId AS noteId, n.title AS title, n.startedAt AS startedAt, s.startMs AS startMs, s.text AS text
           FROM segments s JOIN notes n ON n.id = s.noteId
           WHERE n.deleted = 0 AND n.startedAt >= :from AND n.startedAt < :to
           ORDER BY n.startedAt, s.startMs LIMIT :limit""",
    )
    suspend fun inRange(from: Long, to: Long, limit: Int = 60): List<SegmentHit>
}

@Dao
interface TrackDao {
    @Upsert
    suspend fun upsertSession(s: TrackSessionEntity)

    @Query("SELECT * FROM track_sessions WHERE id = :id")
    suspend fun session(id: String): TrackSessionEntity?

    @Query("SELECT * FROM track_sessions ORDER BY startedAt DESC LIMIT 100")
    fun observeSessions(): Flow<List<TrackSessionEntity>>

    @Query("UPDATE track_sessions SET endedAt = :endedAt WHERE id = :id AND endedAt IS NULL")
    suspend fun end(id: String, endedAt: Long)

    @Query("UPDATE track_sessions SET liveUrl = :url WHERE id = :id")
    suspend fun setLiveUrl(id: String, url: String?)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPoint(p: LocationPointEntity)

    @Query("SELECT * FROM location_points WHERE sessionId = :sessionId AND sent = 0 ORDER BY recordedAt LIMIT :limit")
    suspend fun unsentPoints(sessionId: String, limit: Int): List<LocationPointEntity>

    @Query("UPDATE location_points SET sent = 1 WHERE id IN (:ids)")
    suspend fun markSent(ids: List<String>)

    @Query("SELECT * FROM location_points WHERE recordedAt >= :from AND recordedAt < :to ORDER BY recordedAt")
    suspend fun pointsBetween(from: Long, to: Long): List<LocationPointEntity>

    @Query("SELECT COUNT(*) FROM location_points WHERE sessionId = :sessionId")
    suspend fun pointCount(sessionId: String): Int

    @Query("DELETE FROM location_points WHERE recordedAt < :before AND sent = 1")
    suspend fun prune(before: Long)
}

@Dao
interface PanicDao {
    @Upsert
    suspend fun upsert(p: PanicEntity)

    @Query("SELECT * FROM panics WHERE id = :id")
    suspend fun get(id: String): PanicEntity?

    @Query("SELECT * FROM panics ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PanicEntity>>

    @Query("UPDATE panics SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)
}

@Dao
interface ReportDao {
    @Upsert
    suspend fun upsert(r: ReportEntity)

    @Query("SELECT * FROM reports WHERE id = :id")
    suspend fun get(id: String): ReportEntity?

    @Query("SELECT * FROM reports WHERE id = :id")
    fun observe(id: String): Flow<ReportEntity?>

    @Query("SELECT * FROM reports ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ReportEntity>>

    @Query("UPDATE reports SET status = :status, adminNote = COALESCE(:note, adminNote), updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: String, status: String, note: String?, now: Long = System.currentTimeMillis())
}

@Dao
abstract class ContactDao {
    @Query("SELECT * FROM contacts ORDER BY name")
    abstract fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts ORDER BY name")
    abstract suspend fun all(): List<ContactEntity>

    @Transaction
    open suspend fun replaceAll(list: List<ContactEntity>) {
        clear()
        insertAll(list)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(list: List<ContactEntity>)

    @Query("DELETE FROM contacts")
    abstract suspend fun clear()

    @Query("DELETE FROM contacts WHERE id = :id")
    abstract suspend fun delete(id: String)
}

@Dao
interface FarhanDao {
    @Query("SELECT * FROM farhan_messages ORDER BY createdAt DESC LIMIT 200")
    fun observeRecent(): Flow<List<FarhanMessageEntity>>

    @Insert
    suspend fun insert(m: FarhanMessageEntity): Long

    @Query("DELETE FROM farhan_messages")
    suspend fun clear()
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(item: OutboxEntity): Long

    @Query("UPDATE outbox SET nextTryAt = 0, payload = COALESCE(:payload, payload) WHERE kind = :kind AND refId = :refId")
    suspend fun bump(kind: String, refId: String, payload: String?)

    @Query("SELECT * FROM outbox WHERE nextTryAt <= :now ORDER BY priority, createdAt LIMIT :limit")
    suspend fun due(now: Long, limit: Int = 50): List<OutboxEntity>

    @Query("SELECT MIN(nextTryAt) FROM outbox")
    suspend fun nextDue(): Long?

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM outbox WHERE kind = :kind AND refId = :refId")
    suspend fun deleteByRef(kind: String, refId: String)

    @Query("UPDATE outbox SET attempts = attempts + 1, nextTryAt = :nextTryAt, lastError = :error WHERE id = :id")
    suspend fun fail(id: Long, nextTryAt: Long, error: String?)

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox WHERE kind = :kind AND refId = :refId")
    suspend fun exists(kind: String, refId: String): Int
}
