package com.batmusic.app.ui.components

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.batmusic.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun Artwork(uri: Uri?, modifier: Modifier = Modifier, contentDescription: String? = null, alpha: Float = 1f, shape: Shape = RoundedCornerShape(6.dp), contentScale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    val key = uri?.toString()
    val cached = key?.let(artworkCache::get)
    val bitmap = produceState<Bitmap?>(cached, key) {
        value = cached ?: withContext(Dispatchers.IO) {
            try {
                uri?.let { artUri ->
                    val resolver = context.contentResolver
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(artUri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
                    val sample = if (longestEdge > 512) Integer.highestOneBit(longestEdge / 512).coerceAtLeast(1) else 1
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    resolver.openInputStream(artUri)?.use { BitmapFactory.decodeStream(it, null, options) }
                }
            } catch (_: Exception) { null }
        }?.also { decoded -> if (key != null) artworkCache.put(key, decoded) }
    }.value
    Box(modifier.alpha(alpha.coerceIn(0f, 1f)).clip(shape).background(
        Brush.linearGradient(listOf(Color(0xFF240609), Color(0xFF101010), Color(0xFF080808)))
    ), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription, Modifier.fillMaxSize(), contentScale = contentScale)
        } else {
            Image(painterResource(R.drawable.yui), contentDescription, Modifier.fillMaxSize(), contentScale = contentScale)
        }
    }
}

private val artworkCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
}
