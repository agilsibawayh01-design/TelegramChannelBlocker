package com.mas.tgblocker

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Mendeteksi apakah layar Telegram saat ini sedang menampilkan salah satu
 * channel yang ada di daftar blokir, berdasarkan teks/contentDescription node.
 *
 * Pencocokan dibuat cukup ketat (word-boundary pada "@username") agar tidak
 * memblokir channel lain yang kebetulan memiliki substring nama yang sama.
 */
object ChannelDetector {

    private const val MAX_DEPTH = 40
    private const val MAX_NODES = 800

    /**
     * Mengumpulkan semua teks (text + contentDescription) yang tampil di layar,
     * sudah dinormalisasi (lowercase, whitespace dirapikan).
     */
    fun collectNormalizedTexts(root: AccessibilityNodeInfo?): List<String> {
        if (root == null) return emptyList()
        val results = mutableListOf<String>()
        val visited = HashSet<AccessibilityNodeInfo>()
        traverse(root, depth = 0, visited = visited, out = results)
        return results
    }

    private fun traverse(
        node: AccessibilityNodeInfo?,
        depth: Int,
        visited: MutableSet<AccessibilityNodeInfo>,
        out: MutableList<String>
    ) {
        if (node == null) return
        if (depth > MAX_DEPTH) return
        if (out.size > MAX_NODES) return
        if (!visited.add(node)) return

        node.text?.let { t -> normalize(t.toString()).takeIf { it.isNotBlank() }?.let(out::add) }
        node.contentDescription?.let { cd ->
            normalize(cd.toString()).takeIf { it.isNotBlank() }?.let(out::add)
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (out.size > MAX_NODES) return
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            }
            traverse(child, depth + 1, visited, out)
        }
    }

    private fun normalize(text: String): String {
        return text.trim().lowercase().replace(Regex("\\s+"), " ")
    }

    /**
     * Mengecek apakah salah satu teks di layar mengandung referensi eksplisit
     * ke username channel (format "@username"), dengan word-boundary agar
     * tidak cocok pada substring dari username lain yang lebih panjang.
     */
    fun containsBlockedUsername(texts: List<String>, blocked: List<BlockedChannel>): BlockedChannel? {
        for (channel in blocked) {
            val target = channel.normalized()
            if (target.isBlank()) continue
            val pattern = Regex("(^|[^a-z0-9_])@${Regex.escape(target)}([^a-z0-9_]|$)")
            for (text in texts) {
                if (pattern.containsMatchIn(text)) {
                    return channel
                }
            }
        }
        return null
    }
}
