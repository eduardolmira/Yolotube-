package com.example.yolotube

import ai.onnxruntime.*
import android.graphics.*
import java.nio.FloatBuffer

data class Det(val box: RectF, val label: String, val score: Float)

/** Suporta ONNX de detecção YOLOv8/YOLO11 (saída [1, 4+nc, N]). */
class YoloDetector(bytes: ByteArray) {
    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(bytes)
    private val input = session.inputNames.first()
    private val shape = (session.inputInfo[input]!!.info as TensorInfo).shape
    private val h = if (shape[2] > 0) shape[2].toInt() else 640
    private val w = if (shape[3] > 0) shape[3].toInt() else 640

    // O export do Ultralytics grava os nomes das classes nos metadados do ONNX.
    val names: List<String> = Regex("(\\d+):\\s*['\"](.+?)['\"]")
        .findAll(session.metadata.customMetadata["names"] ?: "")
        .sortedBy { it.groupValues[1].toInt() }.map { it.groupValues[2] }.toList()

    fun detect(src: Bitmap, conf: Float = 0.25f, iou: Float = 0.45f): List<Det> {
        val s = minOf(w / src.width.toFloat(), h / src.height.toFloat())
        val nw = (src.width * s).toInt(); val nh = (src.height * s).toInt()
        val px = (w - nw) / 2f; val py = (h - nh) / 2f
        val lb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(lb).apply { drawColor(Color.rgb(114, 114, 114)); drawBitmap(src, null, RectF(px, py, px + nw, py + nh), null) }
        val pix = IntArray(w * h); lb.getPixels(pix, 0, w, 0, 0, w, h); lb.recycle()
        val data = FloatArray(3 * w * h)
        for (i in pix.indices) {
            val p = pix[i]
            data[i] = (p shr 16 and 255) / 255f
            data[w * h + i] = (p shr 8 and 255) / 255f
            data[2 * w * h + i] = (p and 255) / 255f
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, h.toLong(), w.toLong())).use { t ->
            session.run(mapOf(input to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                val out = (r[0].value as Array<Array<FloatArray>>)[0]
                val cands = ArrayList<Det>()
                for (i in out[0].indices) {
                    var best = -1; var bs = conf
                    for (c in 4 until out.size) if (out[c][i] > bs) { bs = out[c][i]; best = c - 4 }
                    if (best < 0) continue
                    val cx = out[0][i]; val cy = out[1][i]; val bw = out[2][i]; val bh = out[3][i]
                    cands += Det(
                        RectF((cx - bw / 2 - px) / s, (cy - bh / 2 - py) / s, (cx + bw / 2 - px) / s, (cy + bh / 2 - py) / s),
                        names.getOrElse(best) { "classe$best" }, bs
                    )
                }
                return nms(cands, iou)
            }
        }
    }

    private fun nms(d: List<Det>, thr: Float): List<Det> {
        val keep = ArrayList<Det>()
        for (a in d.sortedByDescending { it.score })
            if (keep.none { it.label == a.label && iou(it.box, a.box) > thr }) keep += a
        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val iw = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val ih = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (iw <= 0 || ih <= 0) return 0f
        val i = iw * ih
        return i / (a.width() * a.height() + b.width() * b.height() - i)
    }
}
