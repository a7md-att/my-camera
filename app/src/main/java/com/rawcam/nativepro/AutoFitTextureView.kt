package com.rawcam.nativepro

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView

/** TextureView that keeps a fixed aspect ratio so the preview is never stretched. */
class AutoFitTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextureView(context, attrs, defStyle) {

    private var ratioW = 0
    private var ratioH = 0

    fun setAspectRatio(width: Int, height: Int) {
        require(width > 0 && height > 0) { "Size cannot be negative." }
        ratioW = width
        ratioH = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        if (ratioW == 0 || ratioH == 0) {
            setMeasuredDimension(w, h)
        } else if (w * ratioH < h * ratioW) {
            setMeasuredDimension(w, w * ratioH / ratioW)
        } else {
            setMeasuredDimension(h * ratioW / ratioH, h)
        }
    }
}
