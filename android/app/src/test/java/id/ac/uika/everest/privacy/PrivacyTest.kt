package id.ac.uika.everest.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyTest {
    @Test fun farhan_cloud_opsional_lainnya_wajib() {
        val wajib = setOf("kontak", "panic", "lokasi", "rekam", "laporan")
        assertEquals(wajib, Privacy.scopes(farhanCloud = false))
        assertEquals(wajib + Privacy.FARHAN_CLOUD, Privacy.scopes(farhanCloud = true))
        assertEquals(listOf(Privacy.FARHAN_CLOUD), Privacy.SCOPES.filter { !it.required }.map { it.key })
    }

    @Test fun persetujuan_diminta_ulang_saat_versi_berubah() {
        assertTrue(Privacy.needsConsent(null))
        assertTrue(Privacy.needsConsent("2025-01-01"))
        assertFalse(Privacy.needsConsent(Privacy.VERSION))
    }

    @Test fun url_kebijakan() {
        assertEquals("https://everest.uika-bogor.ac.id/privasi", Privacy.policyUrl("https://everest.uika-bogor.ac.id/"))
    }
}
