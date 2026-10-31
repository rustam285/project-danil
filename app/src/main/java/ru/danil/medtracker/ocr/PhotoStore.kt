package ru.danil.medtracker.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/** Фото рецептов: приватный каталог + сжатие до 1600 px / JPEG 80 (п. 6.1). */
object PhotoStore {

    const val MAX_SIDE = 1600
    const val JPEG_QUALITY = 80

    fun dir(context: Context): File = File(context.filesDir, "photos").apply { mkdirs() }

    /**
     * Сохраняет изображение из URI (камера/галерея) со сжатием.
     * Поворот по EXIF применяется сразу: BitmapFactory его игнорирует, а пересжатый
     * JPEG тег теряет — без этого портретные снимки хранятся повёрнутыми на 90°
     * и OCR их не читает.
     * Возвращает относительный путь (rx_<id>_<n>.jpg) или бросает понятную ошибку.
     */
    fun saveCompressed(context: Context, source: Uri, nameHint: String): String {
        val orientation = runCatching {
            context.contentResolver.openInputStream(source)?.use { ExifInterface(it).rotationDegrees }
        }.getOrDefault(0) ?: 0
        val bmp = decodeScaled(context, source)
            ?.let { if (orientation != 0) applyRotation(it, orientation) else it }
            ?: throw IllegalStateException("Не удалось прочитать изображение")
        val target = File(dir(context), "$nameHint.jpg")
        try {
            FileOutputStream(target).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        } catch (e: Exception) {
            target.delete()
            throw IllegalStateException("Не хватает места для сохранения фото")
        } finally {
            runCatching { bmp.recycle() }
        }
        return target.name
    }

    /** Поворот/отражение по градусам из EXIF (0/90/180/270 + зеркальные случаи). */
    private fun applyRotation(src: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix()
        when (degrees) {
            90 -> matrix.postRotate(90f)
            180 -> matrix.postRotate(180f)
            270 -> matrix.postRotate(270f)
            else -> return src
        }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (rotated !== src) src.recycle()
        return rotated
    }

    fun file(context: Context, relative: String): File = File(dir(context), relative)

    fun delete(context: Context, relative: String) {
        file(context, relative).delete()
    }

    /** Декодирование с уменьшением до MAX_SIDE по длинной стороне. */
    private fun decodeScaled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        var longSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (longSide / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        val longNow = maxOf(decoded.width, decoded.height)
        if (longNow <= MAX_SIDE) return decoded
        val scale = MAX_SIDE.toFloat() / longNow
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }
}
