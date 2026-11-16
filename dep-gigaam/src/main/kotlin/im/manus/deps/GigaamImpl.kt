package im.manus.deps

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import im.manus.universalhost.dep.FillerEngine
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Поиск «эээ» через русское распознавание речи GigaAM v2 (CTC, int8) на sherpa-onnx, без сервера.
 *
 * Обычное распознавание «эээ» почти не пишет, поэтому ищутся места, где голос есть, а слов нет: между соседними
 * словами, найденными моделью, остаётся провал, в котором звук громкий. Из провала убираются поля (хвост предыдущего слова
 * и разгон следующего), остаток длиннее MIN_SEC и по громкости похожий на речь считается «эээ». Пороги подобраны
 * без проверки на живой русской речи: их придётся подкручивать по результатам.
 *
 * Модель (~230 МБ) скачивается при первом запуске в modelDir напрямую из Hugging Face. Распознавание идёт кусками по 20 секунд.
 */
class GigaamImpl : FillerEngine {

    private var recognizer: OfflineRecognizer? = null

    override fun open(app: Context, modelDir: String) {
        close()
        val dir = File(modelDir)
        if (!dir.exists() && !dir.mkdirs()) throw java.io.IOException("Не создать папку $dir")
        for ((name, url) in FILES) {
            val f = File(dir, name)
            if (!f.isFile || f.length() == 0L) download(url, f)
        }
        val model = OfflineModelConfig(
            nemo = OfflineNemoEncDecCtcModelConfig(model = File(dir, "model.int8.onnx").absolutePath),
            tokens = File(dir, "tokens.txt").absolutePath,
            numThreads = 2,
            debug = false,
            provider = "cpu",
        )
        val cfg = OfflineRecognizerConfig(
            featConfig = FeatureConfig(),   // как в примерах sherpa-onnx для GigaAM: размерность признаков берётся из модели
            modelConfig = model,
        )
        recognizer = OfflineRecognizer(null, cfg)
    }

    override fun close() {
        try { recognizer?.release() } catch (_: Throwable) { }
        recognizer = null
    }

    override fun detect(mono16k: FloatArray): MutableList<DoubleArray> {
        val rec = recognizer ?: throw IllegalStateException("GigaAM не открыт")
        val n = mono16k.size
        val out = ArrayList<DoubleArray>()
        if (n < SR) return out

        // громкость по кадрам в 10 мс и «пол» шума
        val nfr = n / HOP
        val db = DoubleArray(nfr)
        for (i in 0 until nfr) {
            var s = 0.0
            for (k in 0 until HOP) { val v = mono16k[i * HOP + k].toDouble(); s += v * v }
            db[i] = 20 * log10(sqrt(s / HOP) + 1e-9)
        }
        val sorted = db.sortedArray()
        val floor = sorted[(0.10 * (nfr - 1)).toInt()]
        val voiced = BooleanArray(nfr) { db[it] > floor + ABOVE_FLOOR_DB }

        // времена слов (токенов) по всему куску
        val marks = ArrayList<Double>()
        var start = 0
        while (start < n) {
            val end = minOf(n, start + SEG)
            if (end - start >= SR / 2) {
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(mono16k.copyOfRange(start, end), SR)
                    rec.decode(stream)
                    val r = rec.getResult(stream)
                    val off = start.toDouble() / SR
                    for (t in r.timestamps) marks.add(off + t)
                } finally {
                    stream.release()
                }
            }
            start = end
        }
        marks.sort()

        // провалы между словами: от начала записи, между словами и до конца
        val total = n.toDouble() / SR
        val bounds = ArrayList<Double>()
        bounds.add(-1.0)
        bounds.addAll(marks)
        bounds.add(-1.0)
        for (i in 0 until bounds.size - 1) {
            val first = i == 0
            val last = i == bounds.size - 2
            val a = (if (first) 0.0 else bounds[i] + PAD_AFTER_WORD) + (if (first) 0.0 else 0.0)
            val b = if (last) total - 0.05 else bounds[i + 1] - PAD_BEFORE_WORD
            val d = b - a
            if (d < MIN_SEC || d > MAX_SEC) continue
            var v = 0
            var cnt = 0
            var f = (a / 0.01).toInt()
            val f1 = minOf(nfr, (b / 0.01).toInt())
            while (f < f1) { if (voiced[f]) v++; cnt++; f++ }
            if (cnt > 0 && v.toDouble() / cnt >= MIN_VOICED) out.add(doubleArrayOf(a, b))
        }
        return out
    }

    private fun download(url: String, dest: File) {
        val part = File(dest.path + ".part")
        var c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        val code = c.responseCode
        if (code !in 200..299) throw java.io.IOException("Модель GigaAM: HTTP $code")
        c.inputStream.use { ins ->
            FileOutputStream(part).use { os ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val r = ins.read(buf)
                    if (r < 0) break
                    os.write(buf, 0, r)
                }
            }
        }
        c.disconnect()
        if (!part.renameTo(dest)) throw java.io.IOException("Не сохранить ${dest.name}")
    }

    companion object {
        private const val SR = 16000
        private const val HOP = 160                        // кадр 10 мс
        private const val SEG = 20 * SR                    // кусок для распознавания: 20 секунд
        private const val ABOVE_FLOOR_DB = 12.0            // громче «пола» шума на столько дБ: считается звуком
        private const val PAD_AFTER_WORD = 0.35            // поле после слова (его хвост)
        private const val PAD_BEFORE_WORD = 0.20           // поле перед словом (разгон)
        private const val MIN_SEC = 0.45                   // короче провал не считается «эээ»
        private const val MAX_SEC = 3.0
        private const val MIN_VOICED = 0.70                // доля громких кадров в провале

        private const val BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19/resolve/main/"
        private val FILES = listOf("model.int8.onnx" to BASE + "model.int8.onnx", "tokens.txt" to BASE + "tokens.txt")
    }
}
