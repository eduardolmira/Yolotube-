package com.example.yolotube

import android.graphics.*
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.schabi.newpipe.extractor.NewPipe

class MainActivity : ComponentActivity() {
    private var detector: YoloDetector? = null
    private var job: Job? = null
    private lateinit var status: TextView
    private lateinit var image: ImageView
    private lateinit var url: EditText

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            status.text = "Carregando modelo..."
            val msg = withContext(Dispatchers.IO) {
                val b = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                if (b.size > 1 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte())
                    "Arquivo .pt detectado (formato PyTorch, não roda direto no Android). Converta no PC: yolo export model=seu.pt format=onnx"
                else try {
                    detector = YoloDetector(b); "Modelo carregado: ${detector!!.names.size} classes"
                } catch (e: Exception) { "Erro ao carregar: ${e.message}" }
            }
            status.text = msg
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        NewPipe.init(Dl())
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (32 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
        }
        url = EditText(this).apply { hint = "Link do YouTube" }
        status = TextView(this).apply { text = "Anexe um modelo .onnx" }
        image = ImageView(this).apply { adjustViewBounds = true }
        root.addView(Button(this).apply { text = "Anexar modelo (.onnx)"; setOnClickListener { pick.launch(arrayOf("*/*")) } })
        root.addView(url)
        root.addView(Button(this).apply { text = "Analisar vídeo"; setOnClickListener { analyze() } })
        root.addView(status); root.addView(image)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun analyze() {
        val det = detector ?: run { status.text = "Carregue um modelo primeiro"; return }
        job?.cancel()
        job = lifecycleScope.launch(Dispatchers.Default) {
            val mmr = MediaMetadataRetriever()
            try {
                withContext(Dispatchers.Main) { status.text = "Obtendo vídeo..." }
                mmr.setDataSource(Yt.streamUrl(url.text.toString().trim()), HashMap())
                val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                var t = 0L
                while (t < dur && isActive) {          // 1 frame por segundo
                    val f = mmr.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (f != null) {
                        val dets = det.detect(f)
                        val bmp = draw(f, dets)
                        val txt = "${t / 1000}s\n" + dets.joinToString("\n") { "${it.label}: ${(it.score * 100).toInt()}%" }
                        withContext(Dispatchers.Main) { image.setImageBitmap(bmp); status.text = txt }
                    }
                    t += 1000
                }
            } catch (e: Exception) {
                withContext(NonCancellable + Dispatchers.Main) { status.text = "Erro: ${e.message}" }
            } finally { mmr.release() }
        }
    }

    private fun draw(src: Bitmap, dets: List<Det>): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(bmp)
        val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = bmp.width / 200f + 1; color = Color.GREEN }
        val txt = Paint().apply { color = Color.GREEN; textSize = bmp.width / 25f; style = Paint.Style.FILL }
        for (d in dets) {
            c.drawRect(d.box, box)
            c.drawText("${d.label} ${(d.score * 100).toInt()}%", d.box.left, maxOf(d.box.top - 4f, txt.textSize), txt)
        }
        return bmp
    }
}
