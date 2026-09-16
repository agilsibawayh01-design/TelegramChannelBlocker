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
 * Ditambah lapisan baru: kalau aplikasi yang terbuka adalah salah satu KLON
 * Telegram (Telegram X, Nekogram, Plus Messenger, dst — lihat CLONE_PACKAGES),
 * service langsung menekan tombol Home, apa pun mode blokirnya, apa pun isi
 * layarnya. Hanya [OFFICIAL_PACKAGE] yang diproses lewat logic deteksi channel
 * biasa di bawah.
 *
 * Service ini TIDAK melakukan apa pun terhadap aplikasi selain yang tercantum
 * di sini — dibatasi melalui android:packageNames pada
 * accessibility_service_config.xml, dan divalidasi ulang di sini sebagai
 * lapisan keamanan tambahan.
 */
class TelegramBlockAccessibilityService : AccessibilityService() {

    private lateinit var repository: BlockedChannelRepository

    companion object {
        private const val TAG = "TgChannelBlocker"
        private const val OFFICIAL_PACKAGE = "org.telegram.messenger"

        // Klon/fork Telegram yang langsung ditendang ke Home begitu dibuka.
        private val CLONE_PACKAGES = setOf(
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta",
            "org.telegram.plus",
            "nekox.messenger",
            "tw.nekomimi.nekogram",
            "org.thunderdog.challegram",   // Telegram X
            "org.forkclient.messenger",
            "com.exteragram.messenger",
            "it.owlgram.android",
            "ua.itaysonlab.messenger",
            "top.qwq2333.nullgram",
            "com.cool2645.nekolite",
            "me.ninjagram.messenger",
            "org.ninjagram.messenger",
            "org.telegram.mdgram",
            "org.telegram.mdgramyou",
            "org.telegram.BifToGram",
            "ellipi.messenger",
            "belloworld.mercurygram"
        )

        private val ALLOWED_PACKAGES = CLONE_PACKAGES + OFFICIAL_PACKAGE
    }

    override fun onCreate() {
        super.onCreate()
        repository = BlockedChannelRepository(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg !in ALLOWED_PACKAGES) return

        if (repository.getMode() == BlockingMode.OFF) return

        // Aplikasi klon: langsung tendang ke Home, tidak perlu baca layar sama sekali.
        if (pkg in CLONE_PACKAGES) {
            Log.d(TAG, "Aplikasi klon Telegram terdeteksi ($pkg), kembali ke Home")
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        // Dari sini seterusnya khusus OFFICIAL_PACKAGE saja.
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
}
