package id.ac.uika.everest.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/** Catatan hasil rekaman. Waktu disimpan sebagai epoch milidetik. */
@Entity(tableName = "notes", indices = [Index("startedAt")])
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val type: String = "pribadi",
    val startedAt: Long,
    val durationS: Int = 0,
    /** merekam | memproses | menunggu_model | selesai | gagal */
    val status: String = "merekam",
    val summary: String? = null,
    val keywords: String = "",
    val audioDir: String,
    val chunkCount: Int = 0,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val synced: Boolean = false,
    val audioUploaded: Boolean = false,
)

/** Potongan audio 30 detik. Ditranskrip satu per satu oleh TranscriptionWorker. */
@Entity(tableName = "chunks", primaryKeys = ["noteId", "idx"])
data class ChunkEntity(
    val noteId: String,
    val idx: Int,
    val path: String,
    val offsetMs: Long,
    val durationMs: Long,
    val done: Boolean = false,
)

@Entity(tableName = "segments", indices = [Index("noteId")])
data class SegmentEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/** Indeks teks penuh atas segmen transkrip (tokenizer unicode61, cocok untuk bahasa Indonesia sederhana). */
@Fts4(contentEntity = SegmentEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "segments_fts")
data class SegmentFts(val text: String)

@Entity(tableName = "track_sessions")
data class TrackSessionEntity(
    @PrimaryKey val id: String,
    /** track | panic */
    val kind: String,
    val panicId: String? = null,
    val startedAt: Long,
    val endedAt: Long? = null,
    val recipientIds: String = "",
    val liveUrl: String? = null,
)

@Entity(tableName = "location_points", indices = [Index("sessionId"), Index("recordedAt")])
data class LocationPointEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float?,
    val speed: Float?,
    val battery: Int?,
    val recordedAt: Long,
    val sent: Boolean = false,
)

@Entity(tableName = "panics")
data class PanicEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val lat: Double?,
    val lng: Double?,
    val accuracyM: Float?,
    val battery: Int?,
    val trackSessionId: String?,
    /** aktif | diterima | ditangani | aman | dibatalkan */
    val status: String = "aktif",
    val sent: Boolean = false,
    val smsSent: Boolean = false,
    val liveUrl: String? = null,
)

@Entity(tableName = "reports", indices = [Index("createdAt")])
data class ReportEntity(
    @PrimaryKey val id: String,
    val photoPath: String,
    val description: String,
    val category: String,
    val lat: Double?,
    val lng: Double?,
    val createdAt: Long,
    /** menunggu_kirim | baru | diproses | selesai | ditolak */
    val status: String = "menunggu_kirim",
    val adminNote: String? = null,
    val updatedAt: Long = createdAt,
)

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String?,
    val contactUserId: String?,
    val contactEmail: String?,
)

@Entity(tableName = "farhan_messages")
data class FarhanMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** pengguna | farhan */
    val role: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Tautan ke catatan (noteId:startMs) yang jadi sumber jawaban, dipisah koma */
    val sources: String = "",
)

/**
 * Antrean kirim (outbox). Setiap aksi ditulis ke tabel datanya + satu baris di sini,
 * lalu SyncWorker mengirimnya begitu ada jaringan. (kind, refId) unik → tidak dobel.
 */
@Entity(tableName = "outbox", indices = [Index(value = ["kind", "refId"], unique = true), Index("priority")])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val refId: String,
    val priority: Int,
    val payload: String? = null,
    val attempts: Int = 0,
    val nextTryAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "NULL") val lastError: String? = null,
)
