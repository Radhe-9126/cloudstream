package com.lagradost.cloudstream3.ui.home

import android.view.View
import androidx.viewpager2.widget.ViewPager2

class HomeScrollTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        val absPos = Math.abs(position)

        // Parallax effect using translationX instead of padding for better performance
        page.translationX = -position * page.width / 2f

        // Slight scale and alpha effect for "Netflix" feel
        if (absPos <= 1) {
            val scaleFactor = 0.9f + (1 - absPos) * 0.1f
            page.scaleX = scaleFactor
            page.scaleY = scaleFactor
            page.alpha = 0.5f + (1 - absPos) * 0.5f
        } else {
            page.alpha = 0f
        }
    }
}