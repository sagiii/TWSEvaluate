package com.sagiii.twsevaluate.music

import com.sagiii.twsevaluate.R

/**
 * 同梱の試聴用BGM。Pixabay Music(https://pixabay.com/music/)の楽曲を
 * Pixabay Content License(無料・クレジット表記不要・改変可)の下で使用。
 * 出典一覧はdocs/MUSIC_CREDITS.md参照。10ジャンル x 3曲。
 */
data class BundledTrack(val genre: String, val genreLabel: String, val title: String, val resId: Int)

object BundledTracks {
    val all: List<BundledTrack> = listOf(
        genre("pop", "ポップ風", R.raw.music_pop_1, R.raw.music_pop_2, R.raw.music_pop_3),
        genre("rock", "ロック風", R.raw.music_rock_1, R.raw.music_rock_2, R.raw.music_rock_3),
        genre("jazz", "ジャズ風", R.raw.music_jazz_1, R.raw.music_jazz_2, R.raw.music_jazz_3),
        genre("classical", "クラシック風", R.raw.music_classical_1, R.raw.music_classical_2, R.raw.music_classical_3),
        genre("edm", "EDM風", R.raw.music_edm_1, R.raw.music_edm_2, R.raw.music_edm_3),
        genre("lofi", "Lo-fi風", R.raw.music_lofi_1, R.raw.music_lofi_2, R.raw.music_lofi_3),
        genre("ambient", "アンビエント風", R.raw.music_ambient_1, R.raw.music_ambient_2, R.raw.music_ambient_3),
        genre("hiphop", "ヒップホップ風", R.raw.music_hiphop_1, R.raw.music_hiphop_2, R.raw.music_hiphop_3),
        genre("folk", "アコースティック風", R.raw.music_folk_1, R.raw.music_folk_2, R.raw.music_folk_3),
        genre("bossa", "ボサノバ風", R.raw.music_bossa_1, R.raw.music_bossa_2, R.raw.music_bossa_3),
    ).flatten()

    val genreLabels: List<Pair<String, String>> = all.map { it.genre to it.genreLabel }.distinct()

    fun tracksFor(genre: String): List<BundledTrack> = all.filter { it.genre == genre }

    private fun genre(id: String, label: String, r1: Int, r2: Int, r3: Int): List<BundledTrack> =
        listOf(
            BundledTrack(id, label, "$label 1", r1),
            BundledTrack(id, label, "$label 2", r2),
            BundledTrack(id, label, "$label 3", r3),
        )
}
