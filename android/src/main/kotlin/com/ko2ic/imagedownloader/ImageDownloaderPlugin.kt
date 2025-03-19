package com.ko2ic.imagedownloader

import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.ko2ic.imagedownloader.TemporaryDatabase.Companion.COLUMNS
import com.ko2ic.imagedownloader.TemporaryDatabase.Companion.TABLE_NAME
import io.flutter.BuildConfig
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class ImageDownloaderPlugin : FlutterPlugin, ActivityAware, MethodCallHandler {
    companion object {

        private const val CHANNEL = "plugins.ko2ic.com/image_downloader"
        private const val LOGGER_TAG = "image_downloader"
    }

    private lateinit var channel: MethodChannel
    private lateinit var permissionListener: ImageDownloaderPermissionListener
    private lateinit var pluginBinding: FlutterPlugin.FlutterPluginBinding

    private var activityBinding: ActivityPluginBinding? = null
    private var applicationContext: Context? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        pluginBinding = binding
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        tearDown()
    }

    override fun onAttachedToActivity(activityPluginBinding: ActivityPluginBinding) {
        setup(
            pluginBinding.binaryMessenger,
            pluginBinding.applicationContext,
            activityPluginBinding.activity,
            activityPluginBinding
        )
    }

    override fun onDetachedFromActivity() {
        tearDown()
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    private fun setup(
        messenger: BinaryMessenger,
        applicationContext: Context,
        activity: Activity,
        activityBinding: ActivityPluginBinding?
    ) {
        this.applicationContext = applicationContext
        channel = MethodChannel(messenger, CHANNEL)
        channel.setMethodCallHandler(this)
        permissionListener = ImageDownloaderPermissionListener(activity)

        this.activityBinding = activityBinding
        this.activityBinding?.addRequestPermissionsResultListener(permissionListener)

    }

    private fun tearDown() {
        activityBinding?.removeRequestPermissionsResultListener(permissionListener)
        channel.setMethodCallHandler(null)
        applicationContext = null
    }

    private var inPublicDir: Boolean = true

    private var callback: CallbackImpl? = null

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (BuildConfig.DEBUG) {
            Log.d(LOGGER_TAG, "Handling method call: ${call.method}, with args ${call.arguments}")
        }

        when (call.method) {
            "downloadImage" -> {
                inPublicDir = call.argument<Boolean>("inPublicDir") != false

                val permissionCallback =
                    applicationContext?.let { CallbackImpl(call, result, channel, it) }
                this.callback = permissionCallback
                if (inPublicDir) {
                    this.permissionListener.callback = permissionCallback
                    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q || permissionListener.alreadyGranted()) {
                        permissionCallback?.granted()
                    }
                } else {
                    permissionCallback?.granted()
                }
            }

            "cancel" -> {
                callback?.downloader?.cancel()
            }

            "open" -> {
                open(call, result)
            }

            "findPath" -> {
                val id = call.argument<String>("id")
                    ?: throw IllegalArgumentException("id is required.")
                val isVideo = call.argument<Boolean?>("isVideo")
                val filePath = applicationContext?.let { findPath(id, it, isVideo) }
                result.success(filePath)
            }

            "findName" -> {
                val id = call.argument<String>("id")
                    ?: throw IllegalArgumentException("id is required.")
                val isVideo = call.argument<Boolean?>("isVideo")
                val fileName = applicationContext?.let { findName(id, it, isVideo) }
                result.success(fileName)
            }

            "findByteSize" -> {
                val id = call.argument<String>("id")
                    ?: throw IllegalArgumentException("imageId is required.")
                val isVideo = call.argument<Boolean?>("isVideo")
                val fileSize = applicationContext?.let { findByteSize(id, it, isVideo) }
                result.success(fileSize)
            }

            "findMimeType" -> {
                val id = call.argument<String>("id")
                    ?: throw IllegalArgumentException("id is required.")
                val isVideo = call.argument<Boolean?>("isVideo")
                val mimeType = applicationContext?.let { findMimeType(id, it, isVideo) }
                result.success(mimeType)
            }

            else -> result.notImplemented()
        }
    }

    private fun open(call: MethodCall, result: MethodChannel.Result) {

        val path = call.argument<String>("path")
            ?: throw IllegalArgumentException("path is required.")

        val file = File(path)
        val intent = Intent(Intent.ACTION_VIEW)

        val fileExtension = MimeTypeMap.getFileExtensionFromUrl(file.path)
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension)

        val uri = applicationContext?.let {
            FileProvider.getUriForFile(
                it,
                "${applicationContext?.packageName}.image_downloader.provider",
                file
            )
        }
        intent.setDataAndType(uri, mimeType)

        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val manager = applicationContext?.packageManager
        if (manager != null) {
            if (manager.queryIntentActivities(intent, PackageManager.MATCH_ALL).isEmpty()) {
                result.error("preview_error", "This file is not supported for previewing", null)
            } else {
                applicationContext?.startActivity(intent)
            }
        }

    }

    private fun findPath(imageId: String, context: Context, isVideo: Boolean?): String? {
        return findFileData(imageId, context, isVideo)?.path
    }

    private fun findName(imageId: String, context: Context, isVideo: Boolean?): String? {
        return findFileData(imageId, context, isVideo)?.name
    }

    private fun findByteSize(imageId: String, context: Context, isVideo: Boolean?): Int? {
        return findFileData(imageId, context, isVideo)?.byteSize
    }

    private fun findMimeType(imageId: String, context: Context, isVideo: Boolean?): String? {
        return findFileData(imageId, context, isVideo)?.mimeType
    }

    private fun findFileData(
        imageId: String,
        context: Context,
        isVideo: Boolean?
    ): FileData? {

        if (inPublicDir) {
            val contentResolver = context.contentResolver
            return contentResolver.query(
                if (isVideo != false) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                null,
                "${MediaStore.MediaColumns._ID}=?",
                arrayOf(imageId),
                null
            ).use {
                checkNotNull(it) { "$imageId is an imageId that does not exist." }
                if (!it.moveToFirst()) return null

                it.moveToFirst()
                val path = it.getString(it.getColumnIndex(MediaStore.MediaColumns.DATA))
                val name = it.getString(it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME))
                val size = it.getInt(it.getColumnIndex(MediaStore.MediaColumns.SIZE))
                val mimeType = it.getString(it.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE))
                FileData(path = path, name = name, byteSize = size, mimeType = mimeType)
            }
        } else {
            val db = TemporaryDatabase(context).readableDatabase
            return db.query(
                TABLE_NAME,
                COLUMNS,
                "${MediaStore.MediaColumns._ID}=?",
                arrayOf(imageId),
                null,
                null,
                null,
                null
            )
                .use {
                    if (!it.moveToFirst()) return null
                    it.moveToFirst()
                    val path = it.getString(it.getColumnIndex(MediaStore.MediaColumns.DATA))
                    val name = it.getString(it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME))
                    val size = it.getInt(it.getColumnIndex(MediaStore.MediaColumns.SIZE))
                    val mimeType =
                        it.getString(it.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE))
                    FileData(path = path, name = name, byteSize = size, mimeType = mimeType)
                }
        }
    }

    private class CallbackImpl(
        private val call: MethodCall,
        private val result: MethodChannel.Result,
        private val channel: MethodChannel,
        private val context: Context
    ) :
        ImageDownloaderPermissionListener.Callback {

        var downloader: Downloader? = null

        override fun granted() {
            val url = call.argument<String>("url")
                ?: throw IllegalArgumentException("url is required.")

            val headers: Map<String, String>? = call.argument<Map<String, String>>("headers")

            val outputMimeType = call.argument<String>("mimeType")
            val inPublicDir = call.argument<Boolean>("inPublicDir") != false
            val directoryType = call.argument<String>("directory") ?: "DIRECTORY_DOWNLOADS"
            val subDirectory = call.argument<String>("subDirectory")
            val tempSubDirectory = subDirectory ?: SimpleDateFormat(
                "yyyy-MM-dd.HH.mm.sss",
                Locale.getDefault()
            ).format(Date())

            val downloadUri = url.toUri()
            val request = DownloadManager.Request(downloadUri)

            //request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) request.allowScanningByMediaScanner()


            if (headers != null) {
                for ((key, value) in headers) {
                    request.addRequestHeader(key, value)
                }
            }

            val directory = convertToDirectory(directoryType)
            if (inPublicDir) {
                request.setDestinationInExternalPublicDir(directory, tempSubDirectory)
            } else {
                TemporaryDatabase(context).writableDatabase.delete(TABLE_NAME, null, null)
                request.setDestinationInExternalFilesDir(context, directory, tempSubDirectory)
            }

            val downloader = Downloader(context, request)
            this.downloader = downloader

            downloader.execute(onNext = {
                if (BuildConfig.DEBUG) {
                    Log.d(LOGGER_TAG, "${it.javaClass.simpleName} ${it.result}")
                }
                when (it) {
                    is Downloader.DownloadStatus.Failed -> Log.d(LOGGER_TAG, it.reason)
                    is Downloader.DownloadStatus.Paused -> Log.d(LOGGER_TAG, it.reason)
                    is Downloader.DownloadStatus.Successful -> {}
                    is Downloader.DownloadStatus.Pending -> {}
                    is Downloader.DownloadStatus.Running -> {
                        Log.d(LOGGER_TAG, "Progress ${it.progress}")
                        val args = HashMap<String, Any>()
                        args["id"] = it.result.id.toString()
                        args["progress"] = it.progress

                        val uiThreadHandler = Handler(Looper.getMainLooper())

                        uiThreadHandler.post {
                            channel.invokeMethod("onProgressUpdate", args)
                        }
                    }
                }

            }, onError = {
                result.error(it.code, it.message, null)
            }, onComplete = {

                val file = if (inPublicDir) {
                    File("${Environment.getExternalStoragePublicDirectory(directory)}/$tempSubDirectory")
                } else {
                    File("${context.getExternalFilesDir(directory)}/$tempSubDirectory")
                }

                if (!file.exists()) {
                    result.error(
                        "save_error",
                        "Couldn't save ${file.absolutePath ?: tempSubDirectory} ",
                        null
                    )
                } else {
                    var mimeType: String? = null
                    if (outputMimeType == null) {
                        try {
                            val stream = BufferedInputStream(FileInputStream(file))
                            mimeType = URLConnection.guessContentTypeFromStream(stream)
                        } catch (e: Throwable) {
                            Log.e(LOGGER_TAG, "${e.printStackTrace()}")
                        }
                    } else {
                        mimeType = outputMimeType
                    }
                    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)

                    val fileName = when {
                        subDirectory != null -> subDirectory
                        extension != null -> "$tempSubDirectory.$extension"
                        else -> downloadUri.lastPathSegment?.split("/")?.last() ?: "file"
                    }

                    val newFile = if (inPublicDir) {
                        File("${Environment.getExternalStoragePublicDirectory(directory)}/$fileName")
                    } else {
                        File("${context.getExternalFilesDir(directory)}/$fileName")
                    }

                    file.renameTo(newFile)
                    val newMimeType = mimeType
                        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(newFile.extension)
                        ?: ""
                    val imageId = saveToDatabase(newFile, mimeType ?: newMimeType, inPublicDir)

                    result.success(imageId)
                }
            })
        }

        override fun denied() {
            result.success(null)
        }

        private fun convertToDirectory(directoryType: String): String {
            return when (directoryType) {
                "DIRECTORY_DOWNLOADS" -> Environment.DIRECTORY_DOWNLOADS
                "DIRECTORY_PICTURES" -> Environment.DIRECTORY_PICTURES
                "DIRECTORY_DCIM" -> Environment.DIRECTORY_DCIM
                "DIRECTORY_MOVIES" -> Environment.DIRECTORY_MOVIES
                else -> directoryType
            }
        }

        private fun saveToDatabase(file: File, mimeType: String, inPublicDir: Boolean): String? {
            val path = file.absolutePath
            val name = file.name
            val size = file.length()

            val contentValues = ContentValues()
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            contentValues.put(MediaStore.MediaColumns.SIZE, size)

            var contentUri: android.net.Uri?
            val isVideo = mimeType.startsWith("video")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
                contentUri =
                    if (isVideo) {
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    }
                contentValues.put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                );
            } else {
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                contentValues.put(MediaStore.MediaColumns.DATA, path)
                contentUri = if (isVideo) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
            }

            if (inPublicDir) {
                val uri = context.contentResolver.insert(
                    contentUri,
                    contentValues
                )
                if (BuildConfig.DEBUG) {
                    Log.d(LOGGER_TAG, "Successfully inserted: $uri")
                }

                return context.contentResolver.query(
                    contentUri,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA),
                    "${MediaStore.MediaColumns.DATA}=?",
                    arrayOf(file.absolutePath),
                    null
                ).use {
                    checkNotNull(it) { "${file.absolutePath} is not found." }
                    if (!it.moveToFirst()) return null
                    it.moveToFirst()
                    it.getString(it.getColumnIndex(MediaStore.MediaColumns._ID))

                }
            } else {
                val db = TemporaryDatabase(context)
                contentValues.put(MediaStore.MediaColumns.DATA, path)
                val allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXTZabcdefghiklmnopqrstuvwxyz0123456789"
                val id = (1..20)
                    .map { allowedChars.random() }
                    .joinToString("")
                contentValues.put(MediaStore.Images.Media._ID, id)
                db.writableDatabase.insert(TABLE_NAME, null, contentValues)
                return id
            }
        }
    }

    private data class FileData(
        val path: String,
        val name: String,
        val byteSize: Int,
        val mimeType: String
    )
}
