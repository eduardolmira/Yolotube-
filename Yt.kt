package com.example.yolotube

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.StreamInfo

object Yt {
    /** Resolve o link do YouTube para uma URL direta de stream (até 720p). */
    fun streamUrl(link: String): String {
        val info = StreamInfo.getInfo(ServiceList.YouTube, link)
        val s = info.videoStreams.ifEmpty { info.videoOnlyStreams }
            .filter { it.height in 1..720 }.maxByOrNull { it.height }
            ?: error("Nenhum stream de vídeo encontrado")
        return s.content
    }
}

class Dl : Downloader() {
    private val client = OkHttpClient()
    override fun execute(r: Request): Response {
        val data = r.dataToSend() ?: if (r.httpMethod() == "POST") ByteArray(0) else null
        val b = okhttp3.Request.Builder().url(r.url()).method(r.httpMethod(), data?.toRequestBody())
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
        r.headers().forEach { (k, v) -> b.removeHeader(k); v.forEach { b.addHeader(k, it) } }
        client.newCall(b.build()).execute().use { resp ->
            if (resp.code == 429) throw ReCaptchaException("429", r.url())
            return Response(resp.code, resp.message, resp.headers.toMultimap(), resp.body?.string(), resp.request.url.toString())
        }
    }
}
