package id.ac.uika.everest.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface EverestApi {
    // ---- Login ----
    @GET("api/auth/config") suspend fun authConfig(): AuthConfigDto
    @POST("api/auth/oidc") suspend fun loginOidc(@Body body: OidcLoginReq): LoginRes
    @POST("api/auth/dev-login") suspend fun loginDev(@Body body: DevLoginReq): LoginRes
    @POST("api/auth/google/link") suspend fun linkGoogle(@Body body: GoogleLinkReq): LinkedRes

    // ---- Profil & perangkat ----
    @GET("api/me") suspend fun me(): UserDto
    @PATCH("api/me") suspend fun updatePhone(@Body body: PhoneReq): UserDto
    @POST("api/devices") suspend fun registerDevice(@Body body: DeviceReq): OkRes
    @POST("api/me/consent") suspend fun consent(@Body body: ConsentReq): OkRes

    // ---- Kontak darurat ----
    @GET("api/contacts") suspend fun contacts(): List<ContactDto>
    @POST("api/contacts") suspend fun addContact(@Body body: ContactReq): ContactDto
    @DELETE("api/contacts/{id}") suspend fun deleteContact(@Path("id") id: String): OkRes

    // ---- Panic & satpam ----
    @POST("api/panic") suspend fun panic(@Body body: PanicReq): PanicRes
    @GET("api/panic/active") suspend fun activePanics(): List<PanicDto>
    @PATCH("api/panic/{id}") suspend fun panicStatus(@Path("id") id: String, @Body body: StatusReq): PanicDto
    @POST("api/guard/duty") suspend fun duty(@Body body: DutyReq): DutyRes

    // ---- Track ----
    @POST("api/tracks") suspend fun startTrack(@Body body: TrackReq): TrackRes
    @POST("api/tracks/{id}/points") suspend fun points(@Path("id") id: String, @Body body: PointsReq): PointsRes
    @PATCH("api/tracks/{id}/stop") suspend fun stopTrack(@Path("id") id: String, @Body body: StopReq): OkRes

    // ---- Laporan ----
    @Multipart
    @POST("api/reports")
    suspend fun sendReport(@Part("meta") meta: RequestBody, @Part photo: MultipartBody.Part): ReportRes
    @GET("api/reports") suspend fun reports(): List<ReportDto>
    @GET("api/reports/{id}") suspend fun report(@Path("id") id: String): ReportDto

    // ---- Catatan ----
    @POST("api/notes/sync") suspend fun syncNotes(@Body body: SyncReq): SyncRes
    @Multipart
    @POST("api/notes/{id}/audio")
    suspend fun uploadAudio(@Path("id") id: String, @Part audio: MultipartBody.Part): OkRes

    // ---- Farhan & Calendar ----
    @GET("api/farhan/status") suspend fun farhanStatus(): FarhanStatus
    @POST("api/farhan/ask") suspend fun askFarhan(@Body body: FarhanReq): FarhanRes
    @POST("api/calendar/events") suspend fun createEvent(@Body body: CalendarReq): CalendarEventDto
    @GET("api/calendar/events") suspend fun events(@Query("from") from: String, @Query("to") to: String): List<CalendarEventDto>

    // ---- Model whisper ----
    @GET("api/models") suspend fun models(): List<ModelDto>
}
