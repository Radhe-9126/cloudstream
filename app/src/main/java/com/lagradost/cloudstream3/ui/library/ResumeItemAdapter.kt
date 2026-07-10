package com.lagradost.cloudstream3.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.HomeRemoveGridBinding
import com.lagradost.cloudstream3.databinding.HomeRemoveGridExpandedBinding
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.home.HomeScrollViewHolderState
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.UIHelper.isBottomLayout

class ResumeItemAdapter(
    nextFocusUp: Int? = null,
    nextFocusDown: Int? = null,
    clickCallback: (SearchClickCallback) -> Unit,
    private val removeCallback: (View) -> Unit,
) : HomeChildItemAdapter(
    id = "resumeAdapter".hashCode(),
    nextFocusUp = nextFocusUp,
    nextFocusDown = nextFocusDown,
    clickCallback = clickCallback
) {
    // As there is no popup on TV we instead use the footer to clear
    override val footers = if (isLayout(TV or EMULATOR)) 1 else 0

    override fun onCreateFooter(parent: ViewGroup): ViewHolderState<Boolean> {
        val expanded = parent.context.isBottomLayout()
        val inflater = LayoutInflater.from(parent.context)
        val binding = if (expanded) HomeRemoveGridExpandedBinding.inflate(
            inflater,
            parent,
            false
        ) else HomeRemoveGridBinding.inflate(inflater, parent, false)
        return HomeScrollViewHolderState(binding)
    }

    override fun onClearView(holder: ViewHolderState<Boolean>) {
        // Clear the image, idk if this saves ram or not, but I guess?
        clearImage(holder.view.root.findViewById(R.id.imageView))
    }

    override fun onBindFooter(holder: ViewHolderState<Boolean>) {
        this.applyBinding(holder, false)
        when (val binding = holder.view) {
            is HomeRemoveGridBinding -> {
                updateLayoutParms(binding.backgroundCard, setWidth, setHeight)
            }

            is HomeRemoveGridExpandedBinding -> {
                updateLayoutParms(binding.backgroundCard, setWidth, setHeight)
            }
        }
        holder.itemView.apply {
            if (isLayout(TV)) {
                isFocusableInTouchMode = true
                isFocusable = true
            }
            nextFocusUp?.let {
                nextFocusUpId = it
            }
            nextFocusDown?.let {
                nextFocusDownId = it
            }

            setOnClickListener { v ->
                removeCallback.invoke(v ?: return@setOnClickListener)
            }
        }
    }
}
