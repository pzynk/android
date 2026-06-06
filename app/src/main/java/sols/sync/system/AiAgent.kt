package sols.sync.system

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.Chat
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.*
import org.json.JSONObject
import sols.sync.SyncApp
import sols.sync.ui.ChatMessage
import sols.sync.ui.ChatMessageType
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket

class AiAgent(private val context: Context, private val deviceId: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var isConnected = false
    
    private val _messages = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 64)
    val messages = _messages.asSharedFlow()
    
    private var chatSession: Chat? = null

    // For tracking ongoing tool executions
    private var currentActionDeferred: CompletableDeferred<String>? = null
    private var currentActionOutput = StringBuilder()
    private var isCapturingScreenshot = false
    private var screenshotResponse: String? = null

    init {
        connectTerminal()
    }

    private fun sendMessage(msg: ChatMessage) {
        scope.launch {
            _messages.emit(msg)
        }
    }

    private fun connectTerminal() {
        val app = context.applicationContext as SyncApp
        val info = app.terminalInfos[deviceId]
        val live = app.discoveredCache[deviceId]

        if (info == null || live == null) {
            sendMessage(ChatMessage(ChatMessageType.SYSTEM, "Error: device offline or terminal not available."))
            return
        }

        scope.launch {
            try {
                val sock = Socket()
                withContext(Dispatchers.IO) {
                    sock.connect(java.net.InetSocketAddress(live.ip, info.port), 10_000)
                    sock.soTimeout = 0
                }
                socket = sock
                val out = sock.getOutputStream()
                outputStream = out

                val authJson = buildJsonObject {
                    put("type", "auth")
                    put("token", info.password ?: "")
                    put("device_id", deviceId)
                }.toString() + "\n"
                
                withContext(Dispatchers.IO) {
                    out.write(authJson.toByteArray(Charsets.UTF_8))
                    out.flush()
                }

                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                val response = withContext(Dispatchers.IO) { reader.readLine() } ?: throw Exception("Disconnected")
                val responseObj = JSONObject(response)
                
                if (responseObj.optBoolean("ok", false)) {
                    isConnected = true
                    sendMessage(ChatMessage(ChatMessageType.SYSTEM, "System ready. Connected to desktop terminal."))
                    startResponseListener(reader)
                } else {
                    sendMessage(ChatMessage(ChatMessageType.SYSTEM, "Desktop rejected auth."))
                }
            } catch (e: Exception) {
                sendMessage(ChatMessage(ChatMessageType.SYSTEM, "Connection Error: ${e.localizedMessage}"))
            }
        }
    }

    private fun startResponseListener(reader: BufferedReader) {
        scope.launch {
            try {
                while (isActive && isConnected) {
                    val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
                    val msg = JSONObject(line)
                    val type = msg.optString("type")
                    
                    when (type) {
                        "capabilities" -> handleCapabilities(msg)
                        "output" -> handleOutput(msg.optString("text"))
                        "complete" -> handleComplete()
                        "error" -> handleError(msg.optString("message"))
                    }
                }
            } catch (e: Exception) {
                if (isConnected) {
                    sendMessage(ChatMessage(ChatMessageType.SYSTEM, "Response Listener Error: ${e.localizedMessage}"))
                }
            } finally {
                isConnected = false
            }
        }
    }

    private fun handleCapabilities(msg: JSONObject) {
        val toolsArr = msg.optJSONArray("tools")
        val declarations = mutableListOf<FunctionDeclaration>()
        
        if (toolsArr != null) {
            for (i in 0 until toolsArr.length()) {
                val toolObj = toolsArr.getJSONObject(i)
                val name = toolObj.optString("name")
                val description = toolObj.optString("description")
                val paramsArr = toolObj.optJSONArray("parameters")
                
                val properties = mutableMapOf<String, Schema>()
                val optionalParams = mutableListOf<String>()
                
                if (paramsArr != null) {
                    for (j in 0 until paramsArr.length()) {
                        val p = paramsArr.getJSONObject(j)
                        val pName = p.optString("name")
                        val pType = p.optString("type")
                        val pDesc = p.optString("description")
                        val pReq = p.optBoolean("required", false)
                        
                        val schema = when (pType) {
                            "integer" -> Schema.integer(pDesc)
                            "number" -> Schema.double(pDesc)
                            "boolean" -> Schema.boolean(pDesc)
                            else -> Schema.string(pDesc)
                        }
                        properties[pName] = schema
                        if (!pReq) {
                            optionalParams.add(pName)
                        }
                    }
                }
                
                declarations.add(
                    FunctionDeclaration(
                        name = name,
                        description = description,
                        parameters = properties,
                        optionalParameters = optionalParams
                    )
                )
            }
        }
        
        val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        val selectedModel = prefs.getString("selected_model", "gemini-3.1-flash-lite") ?: "gemini-3.1-flash-lite"
        
        val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = selectedModel,
            tools = listOf(Tool.functionDeclarations(declarations))
        )
        chatSession = model.startChat()
        sendMessage(ChatMessage(ChatMessageType.SYSTEM, "AI initialized with native desktop tools. Model: $selectedModel"))
    }

    private fun handleOutput(text: String) {
        if (isCapturingScreenshot) {
            screenshotResponse = text
        } else if (currentActionDeferred != null) {
            currentActionOutput.append(text)
        } else {
            sendMessage(ChatMessage(ChatMessageType.SYSTEM, text.trimEnd()))
        }
    }

    private fun handleComplete() {
        val deferred = currentActionDeferred
        if (isCapturingScreenshot) {
            isCapturingScreenshot = false
            val res = screenshotResponse ?: "Error: Screenshot timeout"
            deferred?.complete(res)
        } else if (deferred != null) {
            deferred.complete(currentActionOutput.toString())
        }
        currentActionDeferred = null
    }

    private fun handleError(message: String) {
        sendMessage(ChatMessage(ChatMessageType.SYSTEM, "⚠️ Desktop Error: $message"))
        currentActionDeferred?.complete("Error: $message")
        currentActionDeferred = null
        isCapturingScreenshot = false
    }

    private suspend fun executeDesktopAction(action: String, args: Map<String, JsonElement>): String {
        if (!isConnected) {
            return "Error: Not connected to desktop"
        }
        
        val deferred = CompletableDeferred<String>()
        currentActionDeferred = deferred
        currentActionOutput.clear()
        
        try {
            val payload = when (action) {
                "command" -> {
                    sendMessage(ChatMessage(ChatMessageType.SYSTEM, "▶️ Running command: ${args["command"]?.jsonPrimitive?.content}"))
                    buildJsonObject {
                        put("type", "command")
                        put("command", args["command"]?.jsonPrimitive?.content ?: "")
                    }.toString() + "\n"
                }
                "screenshot" -> {
                    isCapturingScreenshot = true
                    screenshotResponse = null
                    sendMessage(ChatMessage(ChatMessageType.SYSTEM, "📸 Capturing screenshot..."))
                    buildJsonObject {
                        put("type", "action")
                        put("action", "screenshot")
                    }.toString() + "\n"
                }
                else -> {
                    sendMessage(ChatMessage(ChatMessageType.SYSTEM, "⚡ Executing action: $action"))
                    buildJsonObject {
                        put("type", "action")
                        put("action", action)
                        for ((k, v) in args) {
                            put(k, v)
                        }
                    }.toString() + "\n"
                }
            }
            
            sendMessage(ChatMessage(ChatMessageType.SYSTEM, "📤 Request Payload:\n${payload.trimEnd()}"))
            
            withContext(Dispatchers.IO) {
                outputStream?.write(payload.toByteArray(Charsets.UTF_8))
                outputStream?.flush()
            }
            
            val result = deferred.await()
            val debugResult = if (result.length > 500) result.substring(0, 500) + "... [truncated, total ${result.length} chars]" else result
            sendMessage(ChatMessage(ChatMessageType.SYSTEM, "📥 Response Result:\n$debugResult"))
            
            return result
        } catch (e: Exception) {
            currentActionDeferred = null
            isCapturingScreenshot = false
            sendMessage(ChatMessage(ChatMessageType.SYSTEM, "❌ Action Error: ${e.message}"))
            return "Error: ${e.message}"
        }
    }

    fun process(text: String) {
        val session = chatSession
        if (session == null) {
            sendMessage(ChatMessage(ChatMessageType.SYSTEM, "AI is not initialized yet. Waiting for desktop capabilities."))
            return
        }

        sendMessage(ChatMessage(ChatMessageType.SYSTEM, "🤔 Thinking..."))

        scope.launch {
            try {
                var response = session.sendMessage(text)
                
                while (response.functionCalls.isNotEmpty()) {
                    val call = response.functionCalls.first()
                    val args = call.args
                    
                    sendMessage(ChatMessage(ChatMessageType.SYSTEM, "⚙️ Calling tool: ${call.name}"))
                    
                    val resultString = executeDesktopAction(call.name, args)
                    
                    if (call.name == "screenshot" && resultString.startsWith("✓ Screenshot:")) {
                        val b64 = resultString.substring("✓ Screenshot:".length)
                        try {
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                sendMessage(ChatMessage(ChatMessageType.IMAGE, image = bitmap))
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    
                    val functionResponse = buildJsonObject {
                        put("result", resultString)
                    }
                    val part = FunctionResponsePart(call.name, functionResponse, call.id)
                    
                    sendMessage(ChatMessage(ChatMessageType.SYSTEM, "🧠 Analyzing results..."))
                    
                    response = session.sendMessage(content {
                        part(part)
                    })
                }
                
                val responseText = response.text
                if (!responseText.isNullOrBlank()) {
                    sendMessage(ChatMessage(ChatMessageType.AI, responseText))
                }

            } catch (e: Exception) {
                sendMessage(ChatMessage(ChatMessageType.SYSTEM, "❌ Error: ${e.localizedMessage}"))
                e.printStackTrace()
            }
        }
    }

    fun destroy() {
        scope.cancel()
        try {
            socket?.close()
        } catch (e: Exception) {}
    }
}
