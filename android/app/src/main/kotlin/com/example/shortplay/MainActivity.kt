package com.example.shortplay

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.os.ParcelFileDescriptor
import java.io.File
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * Hosts the "shortplay/crypto" MethodChannel that lets the Dart layer register
 * the native "crypto://" libmpv protocol against a media_kit Player handle.
 *
 * The Dart side passes the mpv handle (from `await player.handle`) once per
 * Player instance, before `player.open(...)`. Registration resolves
 * mpv_stream_cb_add_ro at runtime from the libmpv that media_kit already
 * loaded, so no extra native linkage is required here.
 */
class MainActivity : FlutterActivity() {

    private val channelName = "shortplay/crypto"
    private val downloadsChannelName = "shortplay/downloads"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        flutterEngine.plugins.add(NativePlayerPlugin())

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            downloadsChannelName,
        ).setMethodCallHandler { call, result ->
            if (call.method == "decryptToDownloads") {
                val url = call.argument<String>("url")
                val key = call.argument<String>("key")
                val displayName = call.argument<String>("displayName")
                if (url.isNullOrEmpty() || key == null || displayName.isNullOrEmpty()) {
                    result.error("BAD_ARGS", "missing url/key/displayName", null)
                    return@setMethodCallHandler
                }

                Thread {
                    var uri: android.net.Uri? = null
                    var descriptor: ParcelFileDescriptor? = null
                    try {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                        uri = contentResolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            values,
                        ) ?: throw IllegalStateException("cannot create download entry")
                        descriptor = contentResolver.openFileDescriptor(uri, "w")
                            ?: throw IllegalStateException("cannot open download entry")
                        val status = try {
                            CryptoNative.decryptToFile(
                                url,
                                key,
                                "/proc/self/fd/${descriptor!!.fd}",
                            )
                        } finally {
                            descriptor?.close()
                            descriptor = null
                        }
                        if (status != 0) {
                            throw IllegalStateException("native decryptToFile failed: status=$status")
                        }
                        val publishedValues = ContentValues().apply {
                            put(MediaStore.Downloads.IS_PENDING, 0)
                        }
                        contentResolver.update(uri, publishedValues, null, null)
                        runOnUiThread { result.success(uri.toString()) }
                    } catch (error: Throwable) {
                        descriptor?.close()
                        if (uri != null) contentResolver.delete(uri, null, null)
                        runOnUiThread { result.error("DECRYPT_DOWNLOAD_FAILED", error.message, null) }
                    }
                }.start()
                return@setMethodCallHandler
            }
            if (call.method == "deleteFromDownloads") {
                val uriString = call.argument<String>("uri")
                if (uriString.isNullOrEmpty()) {
                    result.error("BAD_ARGS", "missing uri", null)
                    return@setMethodCallHandler
                }
                try {
                    result.success(contentResolver.delete(android.net.Uri.parse(uriString), null, null) > 0)
                } catch (error: Throwable) {
                    result.error("DELETE_FAILED", error.message, null)
                }
                return@setMethodCallHandler
            }
            if (call.method != "publishToDownloads") {
                result.notImplemented()
                return@setMethodCallHandler
            }

            val sourcePath = call.argument<String>("sourcePath")
            val displayName = call.argument<String>("displayName")
            if (sourcePath.isNullOrEmpty() || displayName.isNullOrEmpty()) {
                result.error("BAD_ARGS", "missing sourcePath/displayName", null)
                return@setMethodCallHandler
            }

            Thread {
                try {
                    val source = File(sourcePath)
                    if (!source.exists()) {
                        runOnUiThread { result.error("SOURCE_NOT_FOUND", "source file not found", null) }
                        return@Thread
                    }

                    val published = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val resolver = contentResolver
                        val uri = resolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            values,
                        ) ?: throw IllegalStateException("cannot create download entry")
                        try {
                            resolver.openOutputStream(uri)?.use { output ->
                                source.inputStream().use { input -> input.copyTo(output) }
                            } ?: throw IllegalStateException("cannot open download entry")
                            values.clear()
                            values.put(MediaStore.Downloads.IS_PENDING, 0)
                            resolver.update(uri, values, null, null)
                            uri.toString()
                        } catch (error: Throwable) {
                            resolver.delete(uri, null, null)
                            throw error
                        }
                    } else {
                        val directory = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS,
                        )
                        if (!directory.exists()) directory.mkdirs()
                        val target = File(directory, displayName)
                        source.copyTo(target, overwrite = true)
                        target.absolutePath
                    }
                    runOnUiThread { result.success(published) }
                } catch (error: Throwable) {
                    runOnUiThread { result.error("PUBLISH_FAILED", error.message, null) }
                }
            }.start()
        }

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            channelName,
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "registerCryptoProtocol" -> {
                    val handle = when (val raw = call.argument<Any?>("handle")) {
                        is Number -> raw.toLong()
                        is String -> raw.toLongOrNull() ?: 0L
                        else -> 0L
                    }
                    if (handle == 0L) {
                        result.error("BAD_HANDLE", "missing or zero mpv handle", null)
                        return@setMethodCallHandler
                    }
                    // CryptoNative may dlopen libmpv on first use; keep it off
                    // the platform thread.
                    Thread {
                        val status = try {
                            CryptoNative.registerCryptoProtocol(handle)
                        } catch (t: Throwable) {
                            -1
                        }
                        runOnUiThread { result.success(status) }
                    }.start()
                }

                "prewarm" -> {
                    val url = call.argument<String>("url")
                    val key = call.argument<String>("key")
                    if (url.isNullOrEmpty()) {
                        result.error("BAD_ARGS", "missing url", null)
                        return@setMethodCallHandler
                    }
                    Thread {
                        val status = try {
                            CryptoNative.prewarm(url, key ?: "")
                        } catch (t: Throwable) {
                            -1
                        }
                        runOnUiThread { result.success(status) }
                    }.start()
                }

                "prewarmHeaderOnly" -> {
                    val url = call.argument<String>("url")
                    val key = call.argument<String>("key")
                    if (url.isNullOrEmpty()) {
                        result.error("BAD_ARGS", "missing url", null)
                        return@setMethodCallHandler
                    }
                    Thread {
                        val status = try {
                            CryptoNative.prewarmHeaderOnly(url, key ?: "")
                        } catch (t: Throwable) {
                            -1
                        }
                        runOnUiThread { result.success(status) }
                    }.start()
                }

                "prewarmSeedMdat" -> {
                    val url = call.argument<String>("url")
                    if (url.isNullOrEmpty()) {
                        result.error("BAD_ARGS", "missing url", null)
                        return@setMethodCallHandler
                    }
                    Thread {
                        val status = try {
                            CryptoNative.prewarmSeedMdat(url)
                        } catch (t: Throwable) {
                            -1
                        }
                        runOnUiThread { result.success(status) }
                    }.start()
                }

                "decryptToFile" -> {
                    val url = call.argument<String>("url")
                    val key = call.argument<String>("key")
                    val outputPath = call.argument<String>("outputPath")
                    if (url.isNullOrEmpty() || key.isNullOrEmpty() || outputPath.isNullOrEmpty()) {
                        result.error("BAD_ARGS", "missing url/key/outputPath", null)
                        return@setMethodCallHandler
                    }
                    Thread {
                        val status = try {
                            CryptoNative.decryptToFile(url, key, outputPath)
                        } catch (t: Throwable) {
                            -1
                        }
                        runOnUiThread { result.success(status) }
                    }.start()
                }

                else -> result.notImplemented()
            }
        }
    }
}
