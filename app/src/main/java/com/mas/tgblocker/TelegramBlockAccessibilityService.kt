package com.mas.tgblocker

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Service yang membaca tampilan aplikasi Telegram (dan varian sejenisnya, lihat
 * accessibility_service_config.xml) untuk mendeteksi channel yang ada di daftar
 * blokir, lalu menjalankan aksi BACK agar halaman channel tersebut tidak bisa
 * dibuka/dilanjutkan.
 *
 * Service ini TIDAK melakukan apa pun terhadap aplikasi selain Telegram —
 * dibatasi melalui android:packageNames pada accessibility_service_config.xml,
 * dan divalidasi ulang di sini sebagai lapisan keamanan tambahan.
 */
class TelegramBlockAccessibilityService : AccessibilityService() {

    private lateinit var repository: BlockedChannelRepository

    private val allowedPackages = setOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.plus",
        "nekox.messenger",
        "tw.nekomimi.nekogram"
    )

    override fun onCreate() {
        super.onCreate()
        repository = BlockedChannelRepository(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Lapisan keamanan tambahan: hanya proses event dari Telegram.
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in allowedPackages) return

        if (repository.getMode() == BlockingMode.OFF) return

        val root = rootInActiveWindow ?: return

        try {
            val screenTexts = ChannelDetector.collectTexts(root)
            if (screenTexts.normalized.isEmpty()) return

            val blockedList = repository.getChannels()

            val matchedUsername = ChannelDetector.containsBlockedUsername(screenTexts.normalized, blockedList)
            val matchedName = ChannelDetector.containsBlockedChannelName(screenTexts.raw, blockedList)
            val matchedArgo = blockedList.any { it.type == ChannelType.USERNAME && it.normalized() == "argo" } &&
                ArgoSearchDetector.isArgoSearchPresent(screenTexts.normalized)

            if (matchedUsername != null || matchedName != null || matchedArgo) {
                Log.d(TAG, "Channel diblokir terdeteksi, menjalankan BACK")
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saat memproses event aksesibilitas", e)
        } finally {
            root.recycle()
        }
    }

    override fun onInterrupt() {
        // Tidak ada state khusus yang perlu dibersihkan.
    }

    companion object {
        private const val TAG = "TgChannelBlocker"
    }
}
