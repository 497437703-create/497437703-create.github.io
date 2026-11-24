package com.aihuiji.meeting.share

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCode {
    fun bitmap(content: String, size: Int = 512, dark: Int = 0xFF0E0A18.toInt(), light: Int = 0xFFFFFFFF.toInt()): ImageBitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) dark else light }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}

@Composable
fun QrImage(content: String, modifier: Modifier = Modifier) {
    val bmp = remember(content) { QrCode.bitmap(content) }
    Image(bmp, null, modifier, filterQuality = FilterQuality.None)
}
