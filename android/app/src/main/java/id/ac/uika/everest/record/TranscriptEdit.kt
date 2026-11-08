package id.ac.uika.everest.record

import id.ac.uika.everest.Locator
import id.ac.uika.everest.sync.OutKind
import id.ac.uika.everest.util.now

/**
 * Perbaikan transkrip oleh pengguna (salah dengar whisper).
 * Disimpan di HP dulu (offline-first), lalu catatan utuh dikirim ulang lewat antrean;
 * server memakai last-write-wins berdasarkan updatedAt, jadi versi editan ini yang menang.
 */
object TranscriptEdit {

    fun editable(status: String) = TranscriptText.editable(status)
    fun clean(text: String) = TranscriptText.clean(text)

    suspend fun save(noteId: String, segmentId: String, text: String) {
        val db = Locator.db
        val note = db.notes().get(noteId) ?: return
        if (!editable(note.status)) return
        val before = db.segments().forNote(noteId).joinToString(" ") { it.text }
        val value = clean(text)
        if (value.isEmpty()) db.segments().delete(segmentId) else db.segments().updateText(segmentId, value)
        val after = db.segments().forNote(noteId).joinToString(" ") { it.text }
        if (after == before) return
        db.notes().upsert(
            note.copy(
                keywords = Keywords.extract(after).joinToString(","),
                summary = TranscriptText.nextSummary(note.summary, before, after),
                updatedAt = now(),
                synced = false,
            ),
        )
        if (note.status == "selesai") Locator.outbox.enqueue(OutKind.NOTE, noteId)
    }
}
