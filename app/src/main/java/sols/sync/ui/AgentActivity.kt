package sols.sync.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import sols.sync.R
import sols.sync.system.AiAgent

class AgentActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 200
    }

    private lateinit var aiAgent: AiAgent
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tvStatus: TextView
    private lateinit var rvChat: androidx.recyclerview.widget.RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var fabMic: FloatingActionButton

    private var isListening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_agent)
        
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: run { finish(); return }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        tvStatus = findViewById(R.id.tv_status)
        rvChat = findViewById(R.id.rv_chat)
        fabMic = findViewById(R.id.fab_mic)

        chatAdapter = ChatAdapter()
        rvChat.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        rvChat.adapter = chatAdapter

        aiAgent = AiAgent(this, deviceId)
        
        lifecycleScope.launch {
            aiAgent.messages.collect { msg ->
                chatAdapter.addMessage(msg)
                rvChat.scrollToPosition(chatAdapter.itemCount - 1)
            }
        }

        setupSpeechRecognizer()

        findViewById<android.widget.Button>(R.id.btn_change_model).setOnClickListener {
            val models = arrayOf(
                "gemini-3.1-flash-lite",
                "gemini-flash-lite-latest",
                "gemini-2.5-flash-lite",
                "gemini-3.5-flash",
                "gemini-3-flash-preview",
                "gemini-flash-latest",
                "gemini-2.5-flash"
            )
            val prefs = getSharedPreferences("ai_prefs", android.content.Context.MODE_PRIVATE)
            val currentModel = prefs.getString("selected_model", "gemini-3.1-flash-lite")
            val checkedItem = models.indexOf(currentModel).takeIf { it >= 0 } ?: 0

            android.app.AlertDialog.Builder(this)
                .setTitle("Select Model")
                .setSingleChoiceItems(models, checkedItem) { dialog, which ->
                    prefs.edit().putString("selected_model", models[which]).apply()
                    android.widget.Toast.makeText(this, "Model selected: ${models[which]}. Reconnect to apply.", android.widget.Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .show()
        }

        fabMic.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO_PERMISSION)
            } else {
                toggleListening()
            }
        }
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            tvStatus.text = "Speech Recognition Not Available"
            fabMic.isEnabled = false
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                tvStatus.text = "Listening... Speak now."
                fabMic.alpha = 0.5f
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                tvStatus.text = "Processing voice..."
                isListening = false
                fabMic.alpha = 1.0f
                fabMic.setImageResource(R.drawable.ic_mic)
            }
            override fun onError(error: Int) {
                tvStatus.text = "Tap Mic to Speak"
                isListening = false
                fabMic.alpha = 1.0f
                fabMic.setImageResource(R.drawable.ic_mic)
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    chatAdapter.addMessage(ChatMessage(ChatMessageType.USER, text))
                    rvChat.scrollToPosition(chatAdapter.itemCount - 1)
                    aiAgent.process(text)
                }
                tvStatus.text = "Tap Mic to Speak"
                isListening = false
                fabMic.alpha = 1.0f
                fabMic.setImageResource(R.drawable.ic_mic)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun toggleListening() {
        if (isListening) {
            speechRecognizer.stopListening()
            isListening = false
            fabMic.alpha = 1.0f
            fabMic.setImageResource(R.drawable.ic_mic)
            tvStatus.text = "Tap Mic to Speak"
        } else {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            }
            speechRecognizer.startListening(intent)
            isListening = true
            fabMic.alpha = 0.5f
            fabMic.setImageResource(R.drawable.ic_mic_off)
            tvStatus.text = "Initializing... "
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toggleListening()
            } else {
                tvStatus.text = "Microphone Permission Denied"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
        aiAgent.destroy()
    }
}
