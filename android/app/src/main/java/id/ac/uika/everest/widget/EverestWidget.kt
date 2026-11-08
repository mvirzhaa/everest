package id.ac.uika.everest.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import id.ac.uika.everest.Locator
import id.ac.uika.everest.R
import id.ac.uika.everest.farhan.FarhanActivity
import id.ac.uika.everest.panic.PanicActivity
import id.ac.uika.everest.report.ReportActivity
import id.ac.uika.everest.siren.SirenActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Nuansa putih + oranye cerah
private val WIDGET_BG = Color(0xFFFFFFFF)
private val NEUTRAL = Color(0xFFFFE3CC) // tombol biasa: oranye muda
private val BRAND = Color(0xFFF26B0F) // tombol aktif (Track menyala)
private val ICON = Color(0xFFDD5B00) // ikon oranye di atas tombol muda (kontras ≥ 3:1)
private val LABEL = Color(0xFF4A2A12) // tulisan gelap di atas oranye muda
private val RED = Color(0xFFC0352B)
private val AMBER = Color(0xFFFFB300)
private val ON_AMBER = Color(0xFF2B1700)
private val WHITE = Color.White

/**
 * Widget layar utama, 6 tombol: Rekam · Track · Farhan · Report · Sirine · Panic.
 * Ukuran tinggi → 2 baris × 3 tombol; ukuran pendek → 1 baris. Panic dan Sirine
 * diletakkan paling kanan bawah dan berwarna mencolok agar tidak tersentuh tanpa sengaja.
 */
class EverestWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(ONE_ROW, TWO_ROWS))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val recording = Locator.prefs.recordingNoteId != null
            val tracking = Locator.prefs.trackSessionId != null
            val siren = Locator.prefs.sirenOn
            GlanceTheme { Content(recording, tracking, siren) }
        }
    }

    private data class Btn(
        val icon: Int,
        val label: String,
        val color: Color,
        val action: Action,
        val iconTint: Color = ICON,
        val textColor: Color = LABEL,
    )

    @Composable
    private fun Content(recording: Boolean, tracking: Boolean, siren: Boolean) {
        val buttons = listOf(
            if (recording) Btn(R.drawable.ic_stop, "Stop", RED, actionStartActivity<RecordToggleActivity>(), WHITE, WHITE)
            else Btn(R.drawable.ic_mic, "Rekam", NEUTRAL, actionStartActivity<RecordToggleActivity>()),
            if (tracking) Btn(R.drawable.ic_track, "Tracking", BRAND, actionStartActivity<TrackToggleActivity>(), WHITE, WHITE)
            else Btn(R.drawable.ic_track, "Track", NEUTRAL, actionStartActivity<TrackToggleActivity>()),
            Btn(R.drawable.ic_farhan, "Farhan", NEUTRAL, actionStartActivity<FarhanActivity>()),
            Btn(R.drawable.ic_report, "Report", NEUTRAL, actionStartActivity<ReportActivity>()),
            Btn(if (siren) R.drawable.ic_stop else R.drawable.ic_siren, if (siren) "Matikan" else "Sirine", AMBER, actionStartActivity<SirenActivity>(), ON_AMBER, ON_AMBER),
            Btn(R.drawable.ic_panic, "Panic", RED, actionStartActivity<PanicActivity>(), WHITE, WHITE),
        )
        val twoRows = LocalSize.current.height >= TWO_ROWS.height
        Column(
            modifier = GlanceModifier.fillMaxSize().background(ColorProvider(WIDGET_BG)).cornerRadius(24.dp).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (twoRows) {
                Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                    buttons.take(3).forEach { Button(it) }
                }
                Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                    buttons.drop(3).forEach { Button(it) }
                }
            } else {
                Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    buttons.forEach { Button(it, compact = true) }
                }
            }
        }
    }

    @Composable
    private fun RowScope.Button(b: Btn, compact: Boolean = false) {
        Box(modifier = GlanceModifier.defaultWeight().padding(3.dp), contentAlignment = Alignment.Center) {
            Column(
                modifier = GlanceModifier.fillMaxSize().background(ColorProvider(b.color)).cornerRadius(18.dp).padding(vertical = 4.dp).clickable(b.action),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    provider = ImageProvider(b.icon),
                    contentDescription = b.label,
                    modifier = GlanceModifier.size(if (compact) 20.dp else 26.dp),
                    colorFilter = ColorFilter.tint(ColorProvider(b.iconTint)),
                )
                Box(modifier = GlanceModifier.height(2.dp)) {}
                Text(b.label, style = TextStyle(color = ColorProvider(b.textColor), fontSize = if (compact) 10.sp else 12.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            }
        }
    }

    private companion object {
        val ONE_ROW = DpSize(250.dp, 50.dp)
        val TWO_ROWS = DpSize(180.dp, 110.dp)
    }
}

class EverestWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EverestWidget()
}

object WidgetState {
    /** Perbarui tampilan semua widget (status merekam/tracking/sirine berubah). */
    fun refresh(context: Context) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            runCatching {
                if (GlanceAppWidgetManager(app).getGlanceIds(EverestWidget::class.java).isNotEmpty()) EverestWidget().updateAll(app)
            }
        }
    }
}
