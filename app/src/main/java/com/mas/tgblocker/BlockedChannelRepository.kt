package com.mas.tgblocker

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class BlockingMode { OFF, NORMAL, STRICT }

/**
 * Menyimpan daftar channel yang diblokir dan mode pemblokiran secara lokal
 * di perangkat menggunakan SharedPreferences (tidak ada server/database online).
 *
 * Format: array JSON berisi objek {"type": "USERNAME"|"NAME"|"KEYWORD", "value": "..."}.
 * Data lama (array string polos, dari versi sebelum fitur tipe) tetap didukung
 * dan otomatis dikonversi jadi tipe USERNAME saat dibaca.
 */
class BlockedChannelRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMode(): BlockingMode {
        val stored = prefs.getString(KEY_MODE, null)
        if (stored != null) {
            return try {
                BlockingMode.valueOf(stored)
            } catch (e: Exception) {
                BlockingMode.NORMAL
            }
        }
        // Migrasi dari versi lama (boolean on/off): true -> NORMAL, false -> OFF.
        return if (prefs.getBoolean(KEY_ENABLED_LEGACY, true)) BlockingMode.NORMAL else BlockingMode.OFF
    }

    fun setMode(mode: BlockingMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    fun getChannels(): List<BlockedChannel> {
        val raw = prefs.getString(KEY_CHANNELS, null) ?: return defaultChannels()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                when (val element = arr.get(i)) {
                    is JSONObject -> {
                        val typeStr = element.optString("type", "USERNAME")
                        val type = try {
                            ChannelType.valueOf(typeStr)
                        } catch (e: Exception) {
                            ChannelType.USERNAME
                        }
                        BlockedChannel(type, element.optString("value"))
                    }
                    is String -> BlockedChannel(ChannelType.USERNAME, element) // data lama
                    else -> null
                }
            }
        } catch (e: Exception) {
            defaultChannels()
        }
    }

    fun saveChannels(channels: List<BlockedChannel>) {
        val arr = JSONArray()
        channels.forEach { ch ->
            val obj = JSONObject()
            obj.put("type", ch.type.name)
            obj.put("value", ch.value)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_CHANNELS, arr.toString()).apply()
    }

    fun addChannel(type: ChannelType, rawValue: String): Boolean {
        val clean = if (type == ChannelType.USERNAME) {
            rawValue.trim().removePrefix("@")
        } else {
            rawValue.trim()
        }
        if (clean.isBlank()) return false

        val current = getChannels().toMutableList()
        val exists = current.any { existing ->
            existing.type == type && when (type) {
                ChannelType.USERNAME, ChannelType.KEYWORD -> existing.normalized() == clean.lowercase()
                ChannelType.NAME -> existing.value == clean
            }
        }
        if (exists) return false
        current.add(BlockedChannel(type, clean))
        saveChannels(current)
        return true
    }

    fun updateChannel(old: BlockedChannel, newType: ChannelType, newRawValue: String): Boolean {
        val cleanNew = if (newType == ChannelType.USERNAME) {
            newRawValue.trim().removePrefix("@")
        } else {
            newRawValue.trim()
        }
        if (cleanNew.isBlank()) return false

        val current = getChannels().toMutableList()
        val index = current.indexOfFirst { it.type == old.type && it.value == old.value }
        if (index == -1) return false
        current[index] = BlockedChannel(newType, cleanNew)
        saveChannels(current)
        return true
    }

    fun deleteChannel(channel: BlockedChannel) {
        val current = getChannels().toMutableList()
        current.removeAll { it.type == channel.type && it.value == channel.value }
        saveChannels(current)
    }

    private fun defaultChannels(): List<BlockedChannel> =
        listOf(BlockedChannel(ChannelType.USERNAME, "argo"))

    companion object {
        private const val PREFS_NAME = "tg_blocker_prefs"
        private const val KEY_ENABLED_LEGACY = "blocking_enabled"
        private const val KEY_MODE = "blocking_mode"
        private const val KEY_CHANNELS = "blocked_channels"
    }
}
