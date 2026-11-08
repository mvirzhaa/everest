package id.ac.uika.everest.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val name: String,
    val phone: String? = null,
    val role: String = "user",
    @SerialName("google_linked") val googleLinked: Boolean = false,
    @SerialName("on_duty") val onDuty: Boolean = false,
    @SerialName("consent_version") val consentVersion: String? = null,
)

@Serializable data class ConsentReq(val version: String, @SerialName("agreed_at") val agreedAt: String, val scopes: List<String>)

@Serializable data class AuthConfigDto(val sso: Boolean = false, val devLogin: Boolean = false, val googleCalendar: Boolean = false)
@Serializable data class OidcLoginReq(@SerialName("id_token") val idToken: String)
@Serializable data class DevLoginReq(val email: String, val name: String? = null)
@Serializable data class LoginRes(val token: String, val user: UserDto)
@Serializable data class GoogleLinkReq(@SerialName("auth_code") val authCode: String)
@Serializable data class LinkedRes(val linked: Boolean)
@Serializable data class PhoneReq(val phone: String?)
@Serializable data class DeviceReq(@SerialName("fcm_token") val fcmToken: String, val model: String?)
@Serializable data class OkRes(val ok: Boolean = true)

@Serializable
data class ContactDto(
    val id: String,
    val name: String,
    val phone: String? = null,
    @SerialName("contact_user_id") val contactUserId: String? = null,
    @SerialName("contact_email") val contactEmail: String? = null,
)

@Serializable data class ContactReq(val email: String? = null, val phone: String? = null, val name: String? = null)

@Serializable
data class PanicReq(
    val id: String,
    val lat: Double?,
    val lng: Double?,
    @SerialName("accuracy_m") val accuracyM: Float?,
    val battery: Int?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("track_session_id") val trackSessionId: String?,
    @SerialName("sms_sent") val smsSent: Boolean,
)

@Serializable
data class PanicDto(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val battery: Int? = null,
    @SerialName("created_at") val createdAt: String,
    val status: String,
    val note: String? = null,
    @SerialName("user_name") val userName: String? = null,
    @SerialName("user_phone") val userPhone: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("last_lat") val lastLat: Double? = null,
    @SerialName("last_lng") val lastLng: Double? = null,
    @SerialName("last_at") val lastAt: String? = null,
    @SerialName("live_url") val liveUrl: String? = null,
    @SerialName("escalation_level") val escalationLevel: Int = 0,
)

@Serializable data class PanicRes(val panic: PanicDto, @SerialName("live_url") val liveUrl: String? = null, val recipients: Int = 0)
@Serializable data class StatusReq(val status: String, val note: String? = null)
@Serializable data class DutyReq(@SerialName("on_duty") val onDuty: Boolean)
@Serializable data class DutyRes(@SerialName("on_duty") val onDuty: Boolean)

@Serializable
data class TrackReq(
    val id: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("recipient_user_ids") val recipientUserIds: List<String>,
)

@Serializable data class TrackRes(@SerialName("live_url") val liveUrl: String? = null, val recipients: Int = 0)

@Serializable
data class PointDto(
    val id: String,
    val lat: Double,
    val lng: Double,
    @SerialName("accuracy_m") val accuracyM: Float? = null,
    val speed: Float? = null,
    val battery: Int? = null,
    @SerialName("recorded_at") val recordedAt: String,
)

@Serializable data class PointsReq(val points: List<PointDto>)
@Serializable data class PointsRes(val received: Int = 0, val inserted: Int = 0)
@Serializable data class StopReq(@SerialName("ended_at") val endedAt: String)

@Serializable
data class ReportMeta(
    val id: String,
    val description: String,
    val category: String,
    val lat: Double?,
    val lng: Double?,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class ReportDto(
    val id: String,
    val description: String,
    val category: String? = null,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    val history: List<ReportHistoryDto> = emptyList(),
)

@Serializable
data class ReportHistoryDto(
    val status: String,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("actor_name") val actorName: String? = null,
)

@Serializable data class ReportRes(val report: ReportDto)

@Serializable data class SegmentDto(val id: String, @SerialName("start_ms") val startMs: Long, @SerialName("end_ms") val endMs: Long, val text: String)

@Serializable
data class NoteDto(
    val id: String,
    val title: String = "",
    val type: String = "pribadi",
    @SerialName("started_at") val startedAt: String,
    @SerialName("duration_s") val durationS: Int = 0,
    val summary: String? = null,
    val keywords: List<String> = emptyList(),
    @SerialName("updated_at") val updatedAt: String,
    val deleted: Boolean = false,
    val segments: List<SegmentDto> = emptyList(),
)

@Serializable data class SyncReq(val since: String?, val notes: List<NoteDto>)

@Serializable
data class SyncRes(
    val accepted: List<String> = emptyList(),
    val notes: List<NoteDto> = emptyList(),
    @SerialName("next_since") val nextSince: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable data class LocalContext(val title: String, val date: String, val text: String)
@Serializable data class FarhanReq(val question: String, val tz: String, @SerialName("local_context") val localContext: List<LocalContext>)

@Serializable
data class FarhanAction(
    val type: String,
    val title: String,
    val start: String,
    val end: String? = null,
    val location: String? = null,
    val description: String? = null,
)

@Serializable data class FarhanSource(@SerialName("note_id") val noteId: String, val title: String = "", @SerialName("start_ms") val startMs: Long = 0)
@Serializable data class FarhanRes(val answer: String, val action: FarhanAction? = null, val sources: List<FarhanSource> = emptyList(), val remaining: Int? = null)
@Serializable data class FarhanStatus(val cloud: Boolean = false)

@Serializable
data class CalendarReq(
    val id: String,
    val title: String,
    val start: String,
    val end: String? = null,
    val location: String? = null,
    val description: String? = null,
    val tz: String,
)

@Serializable data class CalendarEventDto(val id: String, val title: String, val start: String? = null, val end: String? = null, val location: String? = null)
@Serializable data class ModelDto(val name: String, val size: Long, val url: String)
@Serializable data class ErrorDto(val error: String? = null)
