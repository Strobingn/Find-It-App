package com.strobingn.findit.data.photo

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Creates FileProvider URIs for camera capture attached to finds. */
class FindPhotoStore(private val context: Context) {
  private val photosDir: File =
    File(context.filesDir, "offline/photos").also { it.mkdirs() }

  fun createCaptureUri(): Pair<Uri, File> {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val file = File(photosDir, "find_$stamp.jpg")
    val uri =
      FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    return uri to file
  }

  fun listPhotos(): List<File> =
    photosDir.listFiles()?.filter { it.isFile && it.extension.equals("jpg", true) }.orEmpty()
}
