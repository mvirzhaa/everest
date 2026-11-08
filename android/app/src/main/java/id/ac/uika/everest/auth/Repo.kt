package id.ac.uika.everest.auth

import id.ac.uika.everest.Locator
import id.ac.uika.everest.data.local.ContactEntity
import id.ac.uika.everest.data.local.ReportEntity
import id.ac.uika.everest.data.remote.ContactReq
import id.ac.uika.everest.data.remote.DutyReq
import id.ac.uika.everest.data.remote.PanicDto
import id.ac.uika.everest.data.remote.PhoneReq
import id.ac.uika.everest.data.remote.ReportDto
import id.ac.uika.everest.data.remote.StatusReq
import id.ac.uika.everest.sync.OutKind
import id.ac.uika.everest.util.parseIso

/** Operasi daring kecil yang dipanggil UI (dengan cache lokal bila relevan). */
object Repo {
    private val db get() = Locator.db
    private val api get() = Locator.api

    suspend fun refreshContacts() {
        val list = api.contacts()
        db.contacts().replaceAll(list.map { ContactEntity(it.id, it.name, it.phone, it.contactUserId, it.contactEmail) })
    }

    suspend fun addContact(email: String?, phone: String?, name: String?) {
        api.addContact(ContactReq(email?.takeIf { it.isNotBlank() }, phone?.takeIf { it.isNotBlank() }, name?.takeIf { it.isNotBlank() }))
        refreshContacts()
    }

    suspend fun deleteContact(id: String) {
        api.deleteContact(id)
        db.contacts().delete(id)
    }

    suspend fun updatePhone(phone: String?) {
        Locator.prefs.user = api.updatePhone(PhoneReq(phone?.takeIf { it.isNotBlank() }))
    }

    /** Perbarui status laporan lokal dari server (status ditindaklanjuti admin). */
    suspend fun refreshReports() {
        for (r in api.reports()) {
            val local = db.reports().get(r.id)
            if (local != null) {
                val note = r.history.lastOrNull { it.note != null }?.note
                db.reports().setStatus(r.id, r.status, note)
            }
        }
    }

    suspend fun reportDetail(id: String): ReportDto = api.report(id).also { r ->
        db.reports().setStatus(id, r.status, r.history.lastOrNull { it.note != null }?.note)
    }

    suspend fun setDuty(onDuty: Boolean): Boolean {
        val res = api.duty(DutyReq(onDuty))
        Locator.prefs.user = Locator.prefs.user?.copy(onDuty = res.onDuty)
        return res.onDuty
    }

    suspend fun activePanics(): List<PanicDto> = api.activePanics()

    /** Satpam/admin mengubah status panic. Lewat antrean agar tetap terkirim walau sinyal putus. */
    suspend fun setPanicStatus(panicId: String, status: String) {
        runCatching { api.panicStatus(panicId, StatusReq(status)) }
            .onFailure { Locator.outbox.enqueue(OutKind.PANIC_STATUS, panicId, status) }
        db.panics().setStatus(panicId, status)
    }

    fun ReportDto.toEntityStatus(local: ReportEntity) = local.copy(status = status, updatedAt = (updatedAt ?: createdAt).parseIso())
}
