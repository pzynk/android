package sols.sync.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.MaterialToolbar
import org.json.JSONObject
import sols.sync.R
import sols.sync.SyncApp
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket

/**
 * Line-based command executor activity.
 *
 * Sends commands to the desktop using JSON:
 *   → {"type":"auth","token":"<pw>","device_id":"<id>"}
 *   ← {"type":"auth_response","ok":true}
 *   → {"type":"command","command":"ls -la"}
 *   ← {"type":"output","text":"output line\n"}
 *   ← {"type":"complete","exit_code":0}
 *   → {"type":"interrupt"}
 */
class TerminalActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_DEVICE_ID = "device_id"

        fun start(context: Context, deviceId: String) {
            context.startActivity(
                Intent(context, TerminalActivity::class.java)
                    .putExtra(EXTRA_DEVICE_ID, deviceId)
            )
        }
    }

    private lateinit var deviceId: String
    private lateinit var consoleScroll: NestedScrollView
    private lateinit var tvConsoleOutput: TextView
    private lateinit var etCommandInput: EditText
    private lateinit var llInputLine: android.view.View
    private lateinit var tvCurrentPrompt: TextView

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null

    @Volatile private var isConnected = false

    private val consoleText = SpannableStringBuilder()

    private var username = "user"
    private var hostname = "host"
    private var cwd = "~"

    // Command history navigation
    private val commandHistory = ArrayList<String>()
    private var historyIndex = -1
    private var draftCommand = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_terminal)

        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: run { finish(); return }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.terminal_root)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomPadding = if (ime.bottom > 0) ime.bottom else systemBars.bottom
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, bottomPadding)
            insets
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        consoleScroll  = findViewById(R.id.console_scroll)
        tvConsoleOutput = findViewById(R.id.tv_console_output)
        etCommandInput  = findViewById(R.id.et_command_input)
        llInputLine     = findViewById(R.id.ll_input_line)
        tvCurrentPrompt = findViewById(R.id.tv_current_prompt)

        etCommandInput.setOnClickListener {
            focusCommandInput()
        }

        etCommandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                sendInputField()
                true
            } else false
        }

        // Custom keyboard toolbar helpers
        findViewById<android.view.View>(R.id.btn_key_tab).setOnClickListener {
            etCommandInput.append("\t")
        }
        findViewById<android.view.View>(R.id.btn_key_ctrl_c).setOnClickListener {
            // Send interrupt signal (Ctrl+C)
            sendInterrupt()
        }
        findViewById<android.view.View>(R.id.btn_key_ctrl_d).setOnClickListener {
            // Ctrl+D behaves as disconnect/exit in standard shells
            appendOutput("\n[Exit requested]\n")
            finish()
        }
        findViewById<android.view.View>(R.id.btn_key_esc).setOnClickListener {
            etCommandInput.setText("")
        }
        findViewById<android.view.View>(R.id.btn_key_up).setOnClickListener {
            navigateHistory(up = true)
        }
        findViewById<android.view.View>(R.id.btn_key_down).setOnClickListener {
            navigateHistory(up = false)
        }

        connectTerminal()
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    // ─── Connection & Reader ──────────────────────────────────────────────────

    private fun connectTerminal() {
        val app  = applicationContext as SyncApp
        val info = app.terminalInfos[deviceId]
        val live = app.discoveredCache[deviceId]

        if (info == null || live == null) {
            appendOutput("Error: terminal info not found or device offline.\n")
            return
        }

        val host     = live.ip
        val port     = info.port
        val password = info.password ?: ""

        Thread {
            try {
                isConnected = true
                runOnUiThread { appendOutput("Connecting to $host:$port…\n") }

                val sock = Socket()
                sock.connect(java.net.InetSocketAddress(host, port), 10_000)
                sock.soTimeout = 0
                socket = sock

                val outStream = sock.getOutputStream()
                outputStream  = outStream

                // Send Auth Handshake
                val authJson = JSONObject()
                    .put("type", "auth")
                    .put("token", password)
                    .put("device_id", deviceId)
                    .toString() + "\n"
                outStream.write(authJson.toByteArray(Charsets.UTF_8))
                outStream.flush()

                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                
                // Read Auth Response
                val response = reader.readLine() ?: throw Exception("Disconnected during handshake")
                val responseObj = JSONObject(response)
                if (responseObj.optString("type") != "auth_response" || !responseObj.optBoolean("ok", false)) {
                    runOnUiThread { appendOutput("Auth rejected by desktop.\n") }
                    disconnect()
                    return@Thread
                }

                runOnUiThread { appendOutput("Connected. Ready to execute commands.\n") }

                // Read line-by-line responses from server
                while (isConnected) {
                    val line = reader.readLine() ?: break
                    val msg = JSONObject(line)
                    runOnUiThread {
                        when (msg.optString("type")) {
                            "prompt_info" -> {
                                username = msg.optString("username", "user")
                                hostname = msg.optString("hostname", "host")
                                cwd = msg.optString("cwd", "~")
                                updatePrompt()
                                llInputLine.visibility = android.view.View.VISIBLE
                                etCommandInput.isEnabled = true
                                focusCommandInput()
                            }
                            "output" -> {
                                appendOutput(msg.optString("text"))
                            }
                            "complete" -> {
                                val code = msg.optInt("exit_code", 0)
                                cwd = msg.optString("cwd", cwd)
                                appendOutput("\n[Command exited with code $code]\n")
                                updatePrompt()
                                llInputLine.visibility = android.view.View.VISIBLE
                                etCommandInput.isEnabled = true
                                focusCommandInput()
                            }
                            "error" -> {
                                appendOutput("\n[Error: ${msg.optString("message")}]\n")
                                llInputLine.visibility = android.view.View.VISIBLE
                                etCommandInput.isEnabled = true
                                focusCommandInput()
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                if (isConnected) {
                    runOnUiThread { appendOutput("\nConnection error: ${e.localizedMessage}\n") }
                }
            } finally {
                disconnect()
                runOnUiThread { appendOutput("\nSession disconnected.\n") }
            }
        }.start()
    }

    // ─── Command Handling ─────────────────────────────────────────────────────

    private fun sendInputField() {
        val cmd = etCommandInput.text.toString().trim()
        if (cmd.isEmpty()) return

        // Add to history
        if (commandHistory.isEmpty() || commandHistory.last() != cmd) {
            commandHistory.add(cmd)
        }
        historyIndex = -1
        draftCommand = ""

        appendPromptToHistory(cmd)

        llInputLine.visibility = android.view.View.GONE
        etCommandInput.isEnabled = false
        etCommandInput.setText("")

        val stream = outputStream ?: run {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
            return
        }

        Thread {
            try {
                val payload = JSONObject()
                    .put("type", "command")
                    .put("command", cmd)
                    .toString() + "\n"
                stream.write(payload.toByteArray(Charsets.UTF_8))
                stream.flush()
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Send failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    llInputLine.visibility = android.view.View.VISIBLE
                    etCommandInput.isEnabled = true
                        focusCommandInput()
                }
            }
        }.start()
    }

    private fun sendInterrupt() {
        val stream = outputStream ?: return
        Thread {
            try {
                val payload = JSONObject()
                    .put("type", "interrupt")
                    .toString() + "\n"
                stream.write(payload.toByteArray(Charsets.UTF_8))
                stream.flush()
            } catch (_: Exception) {}
        }.start()
    }

    // ─── History Navigation ───────────────────────────────────────────────────

    private fun navigateHistory(up: Boolean) {
        if (commandHistory.isEmpty()) return

        if (historyIndex == -1) {
            draftCommand = etCommandInput.text.toString()
        }

        if (up) {
            if (historyIndex == -1) {
                historyIndex = commandHistory.size - 1
            } else if (historyIndex > 0) {
                historyIndex--
            }
        } else {
            if (historyIndex != -1) {
                if (historyIndex < commandHistory.size - 1) {
                    historyIndex++
                } else {
                    historyIndex = -1
                }
            }
        }

        val textToSet = if (historyIndex == -1) draftCommand else commandHistory[historyIndex]
        etCommandInput.setText(textToSet)
        etCommandInput.setSelection(textToSet.length)
    }

    // ─── Clean Disconnect ─────────────────────────────────────────────────────

    private fun disconnect() {
        isConnected = false
        Thread {
            try { outputStream?.close() } catch (_: Exception) {}
            outputStream = null
            try { socket?.close() } catch (_: Exception) {}
            socket = null
        }.start()
    }

    // ─── Output View ──────────────────────────────────────────────────────────

    private fun updatePrompt() {
        val builder = SpannableStringBuilder()
        
        // username@hostname in green
        val userHost = "$username@$hostname"
        builder.append(userHost)
        builder.setSpan(
            ForegroundColorSpan(Color.parseColor("#4CAF50")),
            0,
            userHost.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            StyleSpan(Typeface.BOLD),
            0,
            userHost.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        
        builder.append(":")
        
        // cwd in blue
        val pathStart = builder.length
        builder.append(cwd)
        val pathEnd = builder.length
        
        builder.setSpan(
            ForegroundColorSpan(Color.parseColor("#2196F3")),
            pathStart,
            pathEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            StyleSpan(Typeface.BOLD),
            pathStart,
            pathEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        
        builder.append("$ ")
        tvCurrentPrompt.text = builder
    }

    private fun appendPromptToHistory(cmd: String) {
        val builder = SpannableStringBuilder()
        
        // username@hostname in green
        val userHost = "$username@$hostname"
        builder.append(userHost)
        builder.setSpan(
            ForegroundColorSpan(Color.parseColor("#4CAF50")),
            builder.length - userHost.length,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            StyleSpan(Typeface.BOLD),
            builder.length - userHost.length,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        
        builder.append(":")
        
        // cwd in blue
        val pathStart = builder.length
        builder.append(cwd)
        val pathEnd = builder.length
        
        builder.setSpan(
            ForegroundColorSpan(Color.parseColor("#2196F3")),
            pathStart,
            pathEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            StyleSpan(Typeface.BOLD),
            pathStart,
            pathEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        
        builder.append("$ $cmd\n")
        appendOutput(builder)
    }

    private fun appendOutput(text: CharSequence) {
        if (text is String) {
            // Strip carriage returns, keep newlines
            val cleaned = text.replace("\r\n", "\n").replace("\r", "\n")
            consoleText.append(cleaned)
        } else {
            consoleText.append(text)
        }
        
        // Cap memory buffer
        if (consoleText.length > 50_000) {
            consoleText.delete(0, consoleText.length - 30_000)
        }
        tvConsoleOutput.text = consoleText
        consoleScroll.post { consoleScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun focusCommandInput() {
        etCommandInput.post {
            etCommandInput.requestFocus()
            etCommandInput.isFocusableInTouchMode = true
            val controller = WindowInsetsControllerCompat(window, etCommandInput)
            controller.show(WindowInsetsCompat.Type.ime())
        }
    }
}
