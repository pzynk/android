package sols.sync.ui

import android.graphics.Bitmap

enum class ChatMessageType {
    USER, AI, SYSTEM, IMAGE, COMMAND, COMMAND_OUTPUT
}

data class ChatMessage(
    val type: ChatMessageType,
    val text: String = "",
    val image: Bitmap? = null
)
