package com.everest.translator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener as AndroidRecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class MainActivity : Activity() {

    // 언어 정보: 화면 이름, ML Kit 코드, 폰 음성인식 코드, Vosk 모델 이름
    private data class Lang(val label: String, val mlkit: String, val locale: String, val vosk: String)

    private val langs = listOf(
        Lang("한국어", TranslateLanguage.KOREAN, "ko-KR", "vosk-model-small-ko-0.22"),
        Lang("중국어", TranslateLanguage.CHINESE, "zh-CN", "vosk-model-small-cn-0.22"),
        Lang("일본어", TranslateLanguage.JAPANESE, "ja-JP", "vosk-model-small-ja-0.22"),
        Lang("영어", TranslateLanguage.ENGLISH, "en-US", "vosk-model-small-en-us-0.15")
    )

    private lateinit var fromSpinner: Spinner
    private lateinit var toSpinner: Spinner
    private lateinit var modeGroup: RadioGroup
    private lateinit var modeTalk: RadioButton
    private lateinit var modeLecture: RadioButton
    private lateinit var startButton: Button
    private lateinit var statusView: TextView
    private lateinit var partialView: TextView
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView

    private val main = Handler(Looper.getMainLooper())

    private var translator: Translator? = null
    private var translatorKey = ""
    private var voskModel: Model? = null
    private var voskModelName = ""
    private var voskService: SpeechService? = null
    private var phoneRecognizer: SpeechRecognizer? = null
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        buildUi()
    }

    // ---------------- 화면 ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
            setBackgroundColor(Color.WHITE)
        }

        val names = langs.map { it.label }
        fun spinner(sel: Int) = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names)
            setSelection(sel)
        }

        val langRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fromSpinner = spinner(3)
        toSpinner = spinner(0)
        val arrow = TextView(this).apply {
            text = "  →  "
            textSize = 22f
        }
        langRow.addView(fromSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        langRow.addView(arrow)
        langRow.addView(toSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(langRow)

        modeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        modeTalk = RadioButton(this).apply { text = "대화 모드"; id = 1001; textSize = 17f }
        modeLecture = RadioButton(this).apply { text = "강의 모드 (계속 듣기)"; id = 1002; textSize = 17f }
        modeGroup.addView(modeTalk)
        modeGroup.addView(modeLecture)
        modeGroup.check(1002)
        root.addView(modeGroup)

        startButton = Button(this).apply {
            text = "시작"
            textSize = 22f
            setOnClickListener { if (running) stopAll() else startPressed() }
        }
        root.addView(startButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)))

        statusView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.DKGRAY)
            text = "언어와 모드를 고르고 시작을 누르세요."
        }
        root.addView(statusView)

        partialView = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.GRAY)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(partialView)

        val clearButton = Button(this).apply {
            text = "기록 지우기"
            setOnClickListener { logView.text = "" }
        }

        logView = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.BLACK)
            setTextIsSelectable(true)
        }
        scrollView = ScrollView(this).apply { addView(logView) }
        root.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(clearButton)

        setContentView(root)
    }

    private fun setStatus(s: String) = main.post { statusView.text = s }

    private fun setControls(enabled: Boolean) {
        fromSpinner.isEnabled = enabled
        toSpinner.isEnabled = enabled
        modeTalk.isEnabled = enabled
        modeLecture.isEnabled = enabled
    }

    private fun appendLine(original: String, translated: String) {
        val start = logView.text.length
        logView.append(original + "\n")
        logView.append("→ " + translated + "\n\n")
        // 번역문은 굵게 보이도록 처리
        val text = android.text.SpannableString(logView.text)
        val tStart = start + original.length + 1
        text.setSpan(android.text.style.StyleSpan(Typeface.BOLD), tStart, text.length, 0)
        text.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(0, 70, 160)), tStart, text.length, 0)
        logView.text = text
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ---------------- 시작 / 정지 ----------------

    private fun startPressed() {
        val from = langs[fromSpinner.selectedItemPosition]
        val to = langs[toSpinner.selectedItemPosition]
        if (from == to) {
            setStatus("말하는 언어와 번역할 언어가 같습니다. 다르게 골라 주세요.")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        running = true
        startButton.text = "정지"
        setControls(false)
        prepareTranslator(from, to) {
            if (!running) return@prepareTranslator
            if (modeTalk.isChecked) startTalk(from) else startLecture(from)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startPressed()
        } else {
            setStatus("마이크 권한이 있어야 들을 수 있습니다.")
        }
    }

    private fun stopAll() {
        running = false
        voskService?.let {
            it.stop()
            it.shutdown()
        }
        voskService = null
        phoneRecognizer?.destroy()
        phoneRecognizer = null
        partialView.text = ""
        startButton.text = "시작"
        setControls(true)
        setStatus("정지했습니다.")
    }

    override fun onDestroy() {
        stopAll()
        translator?.close()
        voskModel?.close()
        super.onDestroy()
    }

    // ---------------- 번역 (ML Kit) ----------------

    private fun prepareTranslator(from: Lang, to: Lang, done: () -> Unit) {
        val key = from.mlkit + ">" + to.mlkit
        if (translator != null && translatorKey == key) {
            done()
            return
        }
        translator?.close()
        val t = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(from.mlkit)
                .setTargetLanguage(to.mlkit)
                .build()
        )
        translator = t
        translatorKey = key
        setStatus("번역 언어 파일 확인 중… (처음 한 번은 인터넷으로 내려받습니다)")
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { done() }
            .addOnFailureListener {
                translator = null
                translatorKey = ""
                setStatus("번역 언어 파일을 받지 못했습니다. 인터넷 연결을 확인하세요.")
                main.post { stopAll() }
            }
    }

    private fun cleanText(text: String, lang: Lang): String {
        val t = text.trim()
        // Vosk 중국어·일본어 결과는 글자 사이에 띄어쓰기가 들어가므로 제거
        return if (lang.mlkit == TranslateLanguage.CHINESE || lang.mlkit == TranslateLanguage.JAPANESE) {
            t.replace(" ", "")
        } else t
    }

    private fun translateAndShow(text: String) {
        val t = translator ?: return
        if (text.isBlank()) return
        t.translate(text)
            .addOnSuccessListener { appendLine(text, it) }
            .addOnFailureListener { appendLine(text, "(번역 실패)") }
    }

    // ---------------- 대화 모드 (폰 기본 음성인식, 한 문장) ----------------

    private fun startTalk(from: Lang) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("이 폰에는 기본 음성인식이 없습니다. 강의 모드를 써 주세요.")
            stopAll()
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        phoneRecognizer = r
        r.setRecognitionListener(object : AndroidRecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { setStatus("말씀하세요… (한 문장)") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { setStatus("번역 중…") }
            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말소리를 못 알아들었습니다. 다시 시작을 누르세요."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "음성인식에 인터넷이 필요합니다."
                    else -> "음성인식 오류($error). 다시 시작을 누르세요."
                }
                stopAll()
                setStatus(msg)
            }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull().orEmpty()
                partialView.text = ""
                translateAndShow(text)
                stopAll()
                setStatus("완료. 다음 문장은 시작을 다시 누르세요.")
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                partialView.text = list?.firstOrNull().orEmpty()
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, from.locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        r.startListening(intent)
    }

    // ---------------- 강의 모드 (Vosk, 정지할 때까지 계속) ----------------

    private fun startLecture(from: Lang) {
        Thread {
            try {
                val model = loadVoskModel(from)
                if (!running) return@Thread
                main.post {
                    if (!running) return@post
                    try {
                        val rec = Recognizer(model, 16000.0f)
                        val service = SpeechService(rec, 16000.0f)
                        voskService = service
                        service.startListening(object : RecognitionListener {
                            override fun onPartialResult(hypothesis: String?) {
                                val p = JSONObject(hypothesis ?: "{}").optString("partial")
                                partialView.text = cleanText(p, from)
                            }
                            override fun onResult(hypothesis: String?) {
                                val t = cleanText(JSONObject(hypothesis ?: "{}").optString("text"), from)
                                partialView.text = ""
                                translateAndShow(t)
                            }
                            override fun onFinalResult(hypothesis: String?) {
                                val t = cleanText(JSONObject(hypothesis ?: "{}").optString("text"), from)
                                translateAndShow(t)
                            }
                            override fun onError(exception: Exception?) {
                                setStatus("음성인식 오류: ${exception?.message}")
                                stopAll()
                            }
                            override fun onTimeout() {}
                        })
                        setStatus("듣는 중… 정지를 누를 때까지 계속 번역합니다.")
                    } catch (e: Exception) {
                        setStatus("음성인식 시작 실패: ${e.message}")
                        stopAll()
                    }
                }
            } catch (e: Exception) {
                setStatus("음성 모델 준비 실패: ${e.message}")
                main.post { stopAll() }
            }
        }.start()
    }

    private fun loadVoskModel(lang: Lang): Model {
        voskModel?.let { if (voskModelName == lang.vosk) return it }
        val baseDir = File(filesDir, "vosk")
        val modelDir = File(baseDir, lang.vosk)
        val doneMark = File(modelDir, ".ok")
        if (!doneMark.exists()) {
            modelDir.deleteRecursively()
            baseDir.mkdirs()
            val zip = File(baseDir, lang.vosk + ".zip")
            download("https://alphacephei.com/vosk/models/${lang.vosk}.zip", zip, "${lang.label} 음성 모델")
            setStatus("${lang.label} 음성 모델 압축 푸는 중…")
            unzip(zip, baseDir)
            zip.delete()
            if (!modelDir.exists()) throw IllegalStateException("모델 폴더가 없습니다: ${lang.vosk}")
            doneMark.writeText("ok")
        }
        setStatus("${lang.label} 음성 모델 불러오는 중…")
        voskModel?.close()
        val m = Model(modelDir.absolutePath)
        voskModel = m
        voskModelName = lang.vosk
        return m
    }

    private fun download(url: String, target: File, label: String) {
        var conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        // http -> https 같은 넘어가기(리다이렉트) 직접 처리
        var redirects = 0
        while (conn.responseCode in 300..399 && redirects < 5) {
            val next = conn.getHeaderField("Location")
            conn.disconnect()
            conn = URL(URL(url), next).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            redirects++
        }
        if (conn.responseCode != 200) throw IllegalStateException("내려받기 실패 (${conn.responseCode})")
        val total = conn.contentLengthLong
        val tmp = File(target.path + ".part")
        conn.inputStream.use { input ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(64 * 1024)
                var sum = 0L
                var lastShown = -1L
                while (true) {
                    if (!running) throw IllegalStateException("정지됨")
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sum += n
                    val mb = sum / (1024 * 1024)
                    if (mb != lastShown) {
                        lastShown = mb
                        val totalMb = if (total > 0) " / ${total / (1024 * 1024)}MB" else "MB"
                        setStatus("$label 내려받는 중… ${mb}$totalMb (처음 한 번만)")
                    }
                }
            }
        }
        conn.disconnect()
        if (!tmp.renameTo(target)) throw IllegalStateException("파일 저장 실패")
    }

    private fun unzip(zip: File, destDir: File) {
        val destPath = destDir.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val outFile = File(destDir, entry.name)
                if (!outFile.canonicalPath.startsWith(destPath)) continue
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { zis.copyTo(it) }
                }
            }
        }
    }
}
