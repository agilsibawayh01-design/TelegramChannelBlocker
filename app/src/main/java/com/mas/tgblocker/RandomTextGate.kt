package com.mas.tgblocker

import kotlin.random.Random

/**
 * Menghasilkan teks acak panjang (mirip mekanisme "Stay Focused") yang harus
 * diketik ulang persis oleh pengguna sebelum bisa menonaktifkan pemblokiran.
 * Tujuannya menambah friksi terhadap niat impulsif, bukan mencegah total.
 */
object RandomTextGate {

    private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private const val LENGTH = 140

    fun generate(): String {
        val sb = StringBuilder(LENGTH)
        repeat(LENGTH) {
            sb.append(CHARS[Random.nextInt(CHARS.length)])
        }
        return sb.toString()
    }
}
