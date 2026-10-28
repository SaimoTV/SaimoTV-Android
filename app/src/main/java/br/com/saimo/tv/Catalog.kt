package br.com.saimo.tv

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

data class Source(
    val url: String,
    val referer: String? = null,
    val userAgent: String? = null,
    val keyId: String? = null,
    val key: String? = null,
    val quality: String? = null,
    /// O que diferencia a fonte das outras do canal ("Temporada 3"), quando o
    /// catálogo sabe; senão o app mostra o número dela.
    val nome: String? = null,
) {
    val isDash: Boolean get() = url.contains(".mpd", ignoreCase = true)
    val isHls: Boolean get() =
        url.contains(".m3u8", ignoreCase = true) ||
            url.substringBefore('?').endsWith(".txt", ignoreCase = true) ||
            url.contains("dadinhodetapioca.fun", ignoreCase = true)
}

data class Channel(
    val name: String,
    val logo: String? = null,
    val sources: List<Source>,
    val categoria: String? = null,
)
