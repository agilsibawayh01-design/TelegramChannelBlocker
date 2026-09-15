package com.mas.tgblocker

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Menyimpan daftar channel yang diblokir dan status aktif/nonaktif secara lokal
 * di perangkat menggunakan SharedPreferences (tidak ada server/database online).
 */
class BlockedChannelRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isBlockingEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setBlockingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getChannels(): List<BlockedChannel> {
        val raw = prefs.getString(KEY_CHANNELS, null) ?: return defaultChannels()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> BlockedChannel(arr.getString(i)) }
        } catch (e: Exception) {
            defaultChannels()
        }
    }

    fun saveChannels(channels: List<BlockedChannel>) {
        val arr = JSONArray()
        channels.forEach { arr.put(it.username) }
        prefs.edit().putString(KEY_CHANNELS, arr.toString()).apply()
    }

    fun addChannel(username: String): Boolean {
        val clean = username.trim().removePrefix("@")
        if (clean.isBlank()) return false
        val current = getChannels().toMutableList()
        val exists = current.any { it.normalized() == clean.lowercase() }
        if (exists) return false
        current.add(BlockedChannel(clean))
        saveChannels(current)
        return true
    }

    fun updateChannel(oldUsername: String, newUsername: String): Boolean {
        val cleanNew = newUsername.trim().removePrefix("@")
        if (cleanNew.isBlank()) return false
        val current = getChannels().toMutableList()
        val index = current.indexOfFirst { it.username == oldUsername }
        if (index == -1) return false
        current[index] = BlockedChannel(cleanNew)
        saveChannels(current)
        return true
    }

    fun deleteChannel(username: String) {
        val current = getChannels().toMutableList()
        current.removeAll { it.username == username }
        saveChannels(current)
    }

    /** Entry pertama secara default sesuai permintaan: @argo */
    private fun defaultChannels(): List<BlockedChannel> = listOf(BlockedChannel("argo"))

    companion object {
        private const val PREFS_NAME = "tg_blocker_prefs"
        private const val KEY_ENABLED = "blocking_enabled"
        private const val KEY_CHANNELS = "blocked_channels"
    }
}
