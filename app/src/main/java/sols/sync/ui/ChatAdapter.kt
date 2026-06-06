package sols.sync.ui

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import sols.sync.R

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val llContainer: LinearLayout = itemView.findViewById(R.id.ll_container)
        private val llBubble: LinearLayout = itemView.findViewById(R.id.ll_bubble)
        private val tvMessage: TextView = itemView.findViewById(R.id.tv_message)
        private val ivScreenshot: ImageView = itemView.findViewById(R.id.iv_screenshot)

        fun bind(message: ChatMessage) {
            val radius = 16f * itemView.context.resources.displayMetrics.density
            val bubbleDrawable = android.graphics.drawable.GradientDrawable()
            bubbleDrawable.cornerRadius = radius

            when (message.type) {
                ChatMessageType.USER -> {
                    llContainer.gravity = Gravity.END
                    bubbleDrawable.setColor(Color.parseColor("#1976D2")) // Blue
                    llBubble.background = bubbleDrawable
                    tvMessage.setTextColor(Color.WHITE)
                    tvMessage.typeface = android.graphics.Typeface.DEFAULT
                    tvMessage.text = message.text
                    tvMessage.visibility = View.VISIBLE
                    ivScreenshot.visibility = View.GONE
                }
                ChatMessageType.AI -> {
                    llContainer.gravity = Gravity.START
                    bubbleDrawable.setColor(Color.parseColor("#2E2E2E")) // Dark gray
                    llBubble.background = bubbleDrawable
                    tvMessage.setTextColor(Color.WHITE)
                    tvMessage.typeface = android.graphics.Typeface.DEFAULT
                    tvMessage.text = message.text
                    tvMessage.visibility = View.VISIBLE
                    ivScreenshot.visibility = View.GONE
                }
                ChatMessageType.SYSTEM -> {
                    llContainer.gravity = Gravity.CENTER
                    llBubble.background = null
                    tvMessage.setTextColor(Color.parseColor("#888888")) // Muted
                    tvMessage.typeface = android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.ITALIC)
                    tvMessage.text = message.text
                    tvMessage.visibility = View.VISIBLE
                    ivScreenshot.visibility = View.GONE
                }
                ChatMessageType.COMMAND -> {
                    llContainer.gravity = Gravity.START
                    bubbleDrawable.setColor(Color.parseColor("#121212")) // Very Dark
                    llBubble.background = bubbleDrawable
                    tvMessage.setTextColor(Color.parseColor("#00E676")) // Green
                    tvMessage.typeface = android.graphics.Typeface.MONOSPACE
                    tvMessage.text = message.text
                    tvMessage.visibility = View.VISIBLE
                    ivScreenshot.visibility = View.GONE
                }
                ChatMessageType.COMMAND_OUTPUT -> {
                    llContainer.gravity = Gravity.START
                    bubbleDrawable.setColor(Color.parseColor("#1E1E1E")) // Slightly lighter dark
                    bubbleDrawable.cornerRadius = 8f * itemView.context.resources.displayMetrics.density
                    llBubble.background = bubbleDrawable
                    tvMessage.setTextColor(Color.parseColor("#CCCCCC")) // Light gray
                    tvMessage.typeface = android.graphics.Typeface.MONOSPACE
                    tvMessage.textSize = 12f // smaller
                    tvMessage.text = message.text
                    tvMessage.visibility = View.VISIBLE
                    ivScreenshot.visibility = View.GONE
                }
                ChatMessageType.IMAGE -> {
                    llContainer.gravity = Gravity.START
                    bubbleDrawable.setColor(Color.parseColor("#2E2E2E"))
                    llBubble.background = bubbleDrawable
                    tvMessage.visibility = View.GONE
                    if (message.image != null) {
                        ivScreenshot.setImageBitmap(message.image)
                        ivScreenshot.visibility = View.VISIBLE
                    } else {
                        ivScreenshot.visibility = View.GONE
                    }
                }
            }
            
            if (message.type != ChatMessageType.COMMAND_OUTPUT) {
                tvMessage.textSize = 16f
            }
        }
    }
}
