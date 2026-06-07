package sols.sync.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import sols.sync.R

class BottomSheetSelect : BottomSheetDialogFragment() {

    data class OptionItem(
        val title: String,
        val description: String? = null,
        val iconRes: Int? = null
    )

    interface OnOptionSelectedListener {
        fun onOptionSelected(index: Int)
    }

    private var title: String? = null
    private var options: List<OptionItem> = emptyList()
    private var selectedIndex: Int = -1
    private var listener: OnOptionSelectedListener? = null

    companion object {
        fun newInstance(
            title: String,
            options: List<OptionItem>,
            selectedIndex: Int,
            listener: OnOptionSelectedListener
        ): BottomSheetSelect {
            return BottomSheetSelect().apply {
                this.title = title
                this.options = options
                this.selectedIndex = selectedIndex
                this.listener = listener
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.dialog_bottom_sheet_select, container, false)
        
        val tvTitle = view.findViewById<TextView>(R.id.tv_dialog_title)
        tvTitle.text = title

        val rvOptions = view.findViewById<RecyclerView>(R.id.rv_dialog_options)
        rvOptions.layoutManager = LinearLayoutManager(context)
        rvOptions.adapter = OptionsAdapter(options, selectedIndex) { position ->
            listener?.onOptionSelected(position)
            dismiss()
        }

        return view
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? com.google.android.material.bottomsheet.BottomSheetDialog
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.background = null
    }

    private class OptionsAdapter(
        private val items: List<OptionItem>,
        private val selectedIndex: Int,
        private val onItemClick: (Int) -> Unit
    ) : RecyclerView.Adapter<OptionsAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cardView: MaterialCardView = view.findViewById(R.id.card_option)
            val flIconContainer: View = view.findViewById(R.id.fl_icon_container)
            val tvOptionTitle: TextView = view.findViewById(R.id.tv_option_title)
            val tvOptionDescription: TextView = view.findViewById(R.id.tv_option_description)
            val ivOptionIcon: ImageView = view.findViewById(R.id.iv_option_icon)
            val ivCheckmark: ImageView = view.findViewById(R.id.iv_checkmark)
        }

        private fun getColorAttr(context: android.content.Context, attr: Int): Int {
            val ta = context.obtainStyledAttributes(intArrayOf(attr))
            val color = ta.getColor(0, 0)
            ta.recycle()
            return color
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_bottom_sheet_option, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val context = holder.itemView.context

            holder.tvOptionTitle.text = item.title

            if (item.description != null) {
                holder.tvOptionDescription.text = item.description
                holder.tvOptionDescription.visibility = View.VISIBLE
            } else {
                holder.tvOptionDescription.visibility = View.GONE
            }

            if (item.iconRes != null) {
                holder.ivOptionIcon.setImageResource(item.iconRes)
                holder.flIconContainer.visibility = View.VISIBLE
            } else {
                holder.flIconContainer.visibility = View.GONE
            }

            val primaryColor = getColorAttr(context, com.google.android.material.R.attr.colorPrimary)
            val onPrimaryColor = getColorAttr(context, com.google.android.material.R.attr.colorOnPrimary)
            val primaryContainerColor = getColorAttr(context, com.google.android.material.R.attr.colorPrimaryContainer)
            val surfaceVariantColor = getColorAttr(context, com.google.android.material.R.attr.colorSurfaceVariant)
            val onSurfaceColor = getColorAttr(context, com.google.android.material.R.attr.colorOnSurface)
            val onSurfaceVariantColor = getColorAttr(context, com.google.android.material.R.attr.colorOnSurfaceVariant)

            if (position == selectedIndex) {
                holder.cardView.strokeWidth = 0
                holder.cardView.setCardBackgroundColor(ColorStateList.valueOf(primaryContainerColor))

                holder.flIconContainer.backgroundTintList = ColorStateList.valueOf(primaryColor)
                holder.ivOptionIcon.imageTintList = ColorStateList.valueOf(onPrimaryColor)

                holder.tvOptionTitle.setTextColor(primaryColor)
                holder.tvOptionDescription.setTextColor(onSurfaceVariantColor)
                holder.ivCheckmark.visibility = View.VISIBLE
            } else {
                holder.cardView.strokeWidth = 0
                holder.cardView.setCardBackgroundColor(ColorStateList.valueOf(android.graphics.Color.TRANSPARENT))

                holder.flIconContainer.backgroundTintList = ColorStateList.valueOf(surfaceVariantColor)
                holder.ivOptionIcon.imageTintList = ColorStateList.valueOf(onSurfaceVariantColor)

                holder.tvOptionTitle.setTextColor(onSurfaceColor)
                holder.tvOptionDescription.setTextColor(onSurfaceVariantColor)
                holder.ivCheckmark.visibility = View.GONE
            }

            holder.itemView.setOnClickListener {
                onItemClick(position)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}

