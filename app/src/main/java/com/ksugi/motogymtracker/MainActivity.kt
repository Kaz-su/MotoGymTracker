package com.ksugi.motogymtracker

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

// 各ラップの情報を保持するデータクラス
data class LapInfo(
    val startTime: Long, // ラップが開始された時刻
    var currentElapsedTime: Long = 0L, // 現在の経過時間 (stopwatchRunnable で更新)
    val lapNumber: Int // ラップ番号
)

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var stopwatchDisplay: TextView
    private lateinit var lapHistoryList: RecyclerView
    private lateinit var lapAdapter: LapAdapter
    private lateinit var buttonStart: Button
    private lateinit var buttonStop: Button

    private val handler = Handler(Looper.getMainLooper())
    private val activeLaps = mutableListOf<LapInfo>() // 計測中のラップリスト
    private val lapTimes = mutableListOf<String>() // 完了したラップタイムの履歴

    private var tts: TextToSpeech? = null // TextToSpeech インスタンス用

    private val stopwatchRunnable = object : Runnable {
        override fun run() {
            if (activeLaps.isEmpty()) {
                return
            }
            val currentTime = System.currentTimeMillis()
            activeLaps.forEach { lap ->
                lap.currentElapsedTime = currentTime - lap.startTime
            }
            updateStopwatchDisplay()
            handler.postDelayed(this, 10)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        stopwatchDisplay = findViewById(R.id.stopwatch_display)
        lapHistoryList = findViewById(R.id.lap_history_list)
        buttonStart = findViewById(R.id.button_start)
        buttonStop = findViewById(R.id.button_stop)

        lapAdapter = LapAdapter(lapTimes)
        lapHistoryList.layoutManager = LinearLayoutManager(this)
        lapHistoryList.adapter = lapAdapter

        tts = TextToSpeech(this, this) // TTS の初期化

        updateStopwatchDisplay() // 初期表示

        buttonStart.setOnClickListener {
            startNewLap()
        }

        buttonStop.setOnClickListener {
            stopOldestLapAndRecord()
        }
    }

    // TextToSpeech.OnInitListener の実装
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.JAPANESE)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Log.e("TTS", "日本語はサポートされていません、またはデータがありません。")
            } else {
                // 読み上げ準備完了
            }
        } else {
            // Log.e("TTS", "TextToSpeech の初期化に失敗しました。")
        }
    }

    private fun speakOut(text: String) {
        if (tts?.isSpeaking == true) {
            tts?.stop()
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "")
    }


    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (event?.keyCode) {
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> {
                startNewLap()
                return true
            }
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> {
                stopOldestLapAndRecord()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startNewLap() {
        val newLapStartTime = System.currentTimeMillis()
        // 新しいラップの番号を決定 (既存の完了ラップ数 + 既存の進行中ラップ数 + 1)
        val lapNumber = lapTimes.size + activeLaps.size + 1
        val newLap = LapInfo(startTime = newLapStartTime, currentElapsedTime = 0L, lapNumber = lapNumber)
        activeLaps.add(newLap)

        handler.removeCallbacks(stopwatchRunnable)
        handler.post(stopwatchRunnable)
        updateStopwatchDisplay()
    }

    private fun stopOldestLapAndRecord() {
        if (activeLaps.isNotEmpty()) {
            val oldestLap = activeLaps.removeAt(0)
            val finalElapsedTime = System.currentTimeMillis() - oldestLap.startTime

            val formattedRecord = formatLapDisplayString(oldestLap.lapNumber, finalElapsedTime)
            lapTimes.add(0, formattedRecord)
            lapAdapter.notifyItemInserted(0)
            lapHistoryList.scrollToPosition(0)

            // 音声読み上げ処理を追加
            val speechText = formatTimeForSpeech(finalElapsedTime)
            if (speechText.isNotBlank()) { // 空文字列でない場合のみ読み上げ
                speakOut(speechText)
            }

            handler.removeCallbacks(stopwatchRunnable)

            if (activeLaps.isEmpty()) {
                // これが最後のラップだった場合、そのラップ番号とタイムを表示したままにする
                stopwatchDisplay.text = formattedRecord
            } else {
                updateStopwatchDisplay()
                handler.post(stopwatchRunnable)
            }
        }
    }

    private fun updateStopwatchDisplay() {
        val lapToDisplay = activeLaps.lastOrNull()
        if (lapToDisplay != null) {
            // アクティブなラップがある場合、ラップ番号とタイムを表示
            stopwatchDisplay.text = formatLapDisplayString(lapToDisplay.lapNumber, lapToDisplay.currentElapsedTime)
        } else {
            // アクティブなラップがない場合（初期状態や全ラップ停止後で明示的に表示が設定されていない場合）
            // 最後のラップが停止された場合の表示は stopOldestLapAndRecord 内で処理される
            if (lapTimes.isEmpty()) { // まだ何も記録されていない初期状態
                stopwatchDisplay.text = formatTime(0L) // ラップ番号なしで 0:00.000
            }
        }
    }

    private fun formatTime(timeInMillis: Long): String {
        val minutes = (timeInMillis / (1000 * 60)) % 60
        val seconds = (timeInMillis / 1000) % 60
        val milliseconds = (timeInMillis % 1000)
        return String.format(Locale.getDefault(), "%d:%02d.%03d", minutes, seconds, milliseconds)
    }

    private fun formatLapDisplayString(lapNumber: Int, timeInMillis: Long): String {
        return "[Lap $lapNumber] ${formatTime(timeInMillis)}"
    }

    private fun formatTimeForSpeech(timeInMillis: Long): String {
        if (timeInMillis < 0) return ""

        val minutes = (timeInMillis / (1000 * 60)) % 60
        val seconds = (timeInMillis / 1000) % 60
        val milliseconds = (timeInMillis % 1000)

        val speechParts = mutableListOf<String>()

        speechParts.add("${minutes}分")

        if (minutes > 0 && seconds == 0L && milliseconds == 0L) {
            speechParts.add("0秒")
        } else if (seconds > 0 || (seconds == 0L && minutes == 0L)) { // 秒が0でも分がなければ「れいびょう」
             speechParts.add("${seconds}秒")
        }


        // ミリ秒がすべてゼロの場合は読まない
        if (milliseconds > 0) {
            val msStr = String.format("%03d", milliseconds)
            for (i in msStr.indices) {
                // .123 のようなときに「ひゃくにじゅうさん」と読み上げられるのを防ぐ
                speechParts.add("${msStr[i]} ")
            }
        }
        
        // 全て0の場合は「れいびょう」
        if (minutes == 0L && seconds == 0L && milliseconds == 0L) {
            return "0秒"
        }

        return speechParts.joinToString(" ")
    }


    override fun onDestroy() {
        if (tts != null) {
            tts!!.stop()
            tts!!.shutdown()
        }
        super.onDestroy()
    }

    class LapAdapter(private val lapTimes: List<String>) :
        RecyclerView.Adapter<LapAdapter.LapViewHolder>() {
        class LapViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val lapTimeText: TextView = itemView.findViewById(R.id.lap_time_text)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LapViewHolder {
            val itemView = LayoutInflater.from(parent.context)
                .inflate(R.layout.lap_item, parent, false)
            return LapViewHolder(itemView)
        }
        override fun onBindViewHolder(holder: LapViewHolder, position: Int) {
            holder.lapTimeText.text = lapTimes[position]
        }
        override fun getItemCount() = lapTimes.size
    }
}
