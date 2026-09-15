package com.mas.tgblocker

/**
 * Merepresentasikan satu entri channel Telegram yang diblokir.
 * [username] disimpan tanpa karakter "@" di depan, dalam bentuk asli (bukan lowercase)
 * untuk ditampilkan, tapi pencocokan selalu dilakukan setelah normalisasi.
 */
data class BlockedChannel(
    val username: String
) {
    /** Bentuk yang dinormalisasi (lowercase, tanpa spasi) untuk pencocokan. */
    fun normalized(): String = username.trim().lowercase()

    /** Bentuk tampilan dengan prefix "@". */
    fun display(): String = "@${username.trim()}"
}
