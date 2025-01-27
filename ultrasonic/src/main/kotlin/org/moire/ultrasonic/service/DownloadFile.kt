/*
 * DownloadFile.kt
 * Copyright (C) 2009-2021 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.service

import android.content.Context
import android.net.wifi.WifiManager.WifiLock
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.text.TextUtils
import androidx.lifecycle.MutableLiveData
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.moire.ultrasonic.app.UApp
import org.moire.ultrasonic.domain.MusicDirectory
import org.moire.ultrasonic.service.MusicServiceFactory.getMusicService
import org.moire.ultrasonic.subsonic.ImageLoaderProvider
import org.moire.ultrasonic.util.CacheCleaner
import org.moire.ultrasonic.util.CancellableTask
import org.moire.ultrasonic.util.FileUtil
import org.moire.ultrasonic.util.Settings
import org.moire.ultrasonic.util.Util
import timber.log.Timber

/**
 * This class represents a singe Song or Video that can be downloaded.
 *
 * @author Sindre Mehus
 * @version $Id$
 */
class DownloadFile(
    val song: MusicDirectory.Entry,
    private val save: Boolean
) : KoinComponent, Comparable<DownloadFile> {
    val partialFile: File
    val completeFile: File
    private val saveFile: File = FileUtil.getSongFile(song)
    private var downloadTask: CancellableTask? = null
    var isFailed = false
    private var retryCount = MAX_RETRIES

    private val desiredBitRate: Int = Settings.maxBitRate

    var priority = 100

    @Volatile
    private var isPlaying = false

    @Volatile
    private var saveWhenDone = false

    @Volatile
    private var completeWhenDone = false

    private val downloader: Downloader by inject()
    private val imageLoaderProvider: ImageLoaderProvider by inject()

    val progress: MutableLiveData<Int> = MutableLiveData(0)

    init {
        partialFile = File(saveFile.parent, FileUtil.getPartialFile(saveFile.name))
        completeFile = File(saveFile.parent, FileUtil.getCompleteFile(saveFile.name))
    }

    /**
     * Returns the effective bit rate.
     */
    fun getBitRate(): Int {
        return if (song.bitRate == null) desiredBitRate else song.bitRate!!
    }

    @Synchronized
    fun download() {
        FileUtil.createDirectoryForParent(saveFile)
        isFailed = false
        downloadTask = DownloadTask()
        downloadTask!!.start()
    }

    @Synchronized
    fun cancelDownload() {
        if (downloadTask != null) {
            downloadTask!!.cancel()
        }
    }

    val completeOrSaveFile: File
        get() = if (saveFile.exists()) {
            saveFile
        } else {
            completeFile
        }

    val completeOrPartialFile: File
        get() = if (isCompleteFileAvailable) {
            completeOrSaveFile
        } else {
            partialFile
        }

    val isSaved: Boolean
        get() = saveFile.exists()

    @get:Synchronized
    val isCompleteFileAvailable: Boolean
        get() = saveFile.exists() || completeFile.exists()

    @get:Synchronized
    val isWorkDone: Boolean
        get() = saveFile.exists() || completeFile.exists() && !save ||
            saveWhenDone || completeWhenDone

    @get:Synchronized
    val isDownloading: Boolean
        get() = downloadTask != null && downloadTask!!.isRunning

    @get:Synchronized
    val isDownloadCancelled: Boolean
        get() = downloadTask != null && downloadTask!!.isCancelled

    fun shouldSave(): Boolean {
        return save
    }

    fun shouldRetry(): Boolean {
        return (retryCount > 0)
    }

    fun delete() {
        cancelDownload()
        Util.delete(partialFile)
        Util.delete(completeFile)
        Util.delete(saveFile)

        Util.scanMedia(saveFile)
    }

    fun unpin() {
        if (saveFile.exists()) {
            if (!saveFile.renameTo(completeFile)) {
                Timber.w(
                    "Renaming file failed. Original file: %s; Rename to: %s",
                    saveFile.name, completeFile.name
                )
            }
        }
    }

    fun cleanup(): Boolean {
        var ok = true
        if (completeFile.exists() || saveFile.exists()) {
            ok = Util.delete(partialFile)
        }

        if (saveFile.exists()) {
            ok = ok and Util.delete(completeFile)
        }

        return ok
    }

    // In support of LRU caching.
    fun updateModificationDate() {
        updateModificationDate(saveFile)
        updateModificationDate(partialFile)
        updateModificationDate(completeFile)
    }

    fun setPlaying(isPlaying: Boolean) {
        if (!isPlaying) doPendingRename()
        this.isPlaying = isPlaying
    }

    // Do a pending rename after the song has stopped playing
    private fun doPendingRename() {
        try {
            if (saveWhenDone) {
                Util.renameFile(completeFile, saveFile)
                saveWhenDone = false
            } else if (completeWhenDone) {
                if (save) {
                    Util.renameFile(partialFile, saveFile)
                    Util.scanMedia(saveFile)
                } else {
                    Util.renameFile(partialFile, completeFile)
                }
                completeWhenDone = false
            }
        } catch (e: IOException) {
            Timber.w(e, "Failed to rename file %s to %s", completeFile, saveFile)
        }
    }

    override fun toString(): String {
        return String.format("DownloadFile (%s)", song)
    }

    private inner class DownloadTask : CancellableTask() {
        override fun execute() {
            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null
            var wakeLock: WakeLock? = null
            var wifiLock: WifiLock? = null
            try {
                wakeLock = acquireWakeLock(wakeLock)
                wifiLock = Util.createWifiLock(toString())
                wifiLock.acquire()

                if (saveFile.exists()) {
                    Timber.i("%s already exists. Skipping.", saveFile)
                    return
                }

                if (completeFile.exists()) {
                    if (save) {
                        if (isPlaying) {
                            saveWhenDone = true
                        } else {
                            Util.renameFile(completeFile, saveFile)
                        }
                    } else {
                        Timber.i("%s already exists. Skipping.", completeFile)
                    }
                    return
                }

                val musicService = getMusicService()

                // Some devices seem to throw error on partial file which doesn't exist
                val needsDownloading: Boolean
                val duration = song.duration
                var fileLength: Long = 0

                if (!partialFile.exists()) {
                    fileLength = partialFile.length()
                }

                needsDownloading = (
                    desiredBitRate == 0 || duration == null ||
                        duration == 0 || fileLength == 0L
                    )

                if (needsDownloading) {
                    // Attempt partial HTTP GET, appending to the file if it exists.
                    val (inStream, partial) = musicService.getDownloadInputStream(
                        song, partialFile.length(), desiredBitRate, save
                    )

                    inputStream = inStream

                    if (partial) {
                        Timber.i(
                            "Executed partial HTTP GET, skipping %d bytes",
                            partialFile.length()
                        )
                    }

                    outputStream = FileOutputStream(partialFile, partial)

                    val len = inputStream.copyTo(outputStream) { totalBytesCopied ->
                        setProgress(totalBytesCopied)
                    }

                    Timber.i("Downloaded %d bytes to %s", len, partialFile)

                    inputStream.close()
                    outputStream.flush()
                    outputStream.close()

                    if (isCancelled) {
                        throw Exception(String.format("Download of '%s' was cancelled", song))
                    }
                    downloadAndSaveCoverArt()
                }

                if (isPlaying) {
                    completeWhenDone = true
                } else {
                    if (save) {
                        Util.renameFile(partialFile, saveFile)
                        Util.scanMedia(saveFile)
                    } else {
                        Util.renameFile(partialFile, completeFile)
                    }
                }
            } catch (all: Exception) {
                Util.close(outputStream)
                Util.delete(completeFile)
                Util.delete(saveFile)
                if (!isCancelled) {
                    isFailed = true
                    if (retryCount > 0) {
                        --retryCount
                    }
                    Timber.w(all, "Failed to download '%s'.", song)
                }
            } finally {
                Util.close(inputStream)
                Util.close(outputStream)
                if (wakeLock != null) {
                    wakeLock.release()
                    Timber.i("Released wake lock %s", wakeLock)
                }
                wifiLock?.release()
                CacheCleaner().cleanSpace()
                downloader.checkDownloads()
            }
        }

        private fun acquireWakeLock(wakeLock: WakeLock?): WakeLock? {
            var wakeLock1 = wakeLock
            if (Settings.isScreenLitOnDownload) {
                val context = UApp.applicationContext()
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val flags = PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE
                wakeLock1 = pm.newWakeLock(flags, toString())
                wakeLock1.acquire(10 * 60 * 1000L /*10 minutes*/)
                Timber.i("Acquired wake lock %s", wakeLock1)
            }
            return wakeLock1
        }

        override fun toString(): String {
            return String.format("DownloadTask (%s)", song)
        }

        private fun downloadAndSaveCoverArt() {
            try {
                if (!TextUtils.isEmpty(song.coverArt)) {
                    // Download the largest size that we can display in the UI
                    imageLoaderProvider.getImageLoader().cacheCoverArt(song)
                }
            } catch (all: Exception) {
                Timber.e(all, "Failed to get cover art.")
            }
        }

        @Throws(IOException::class)
        fun InputStream.copyTo(out: OutputStream, onCopy: (totalBytesCopied: Long) -> Any): Long {
            var bytesCopied: Long = 0
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytes = read(buffer)
            while (!isCancelled && bytes >= 0) {
                out.write(buffer, 0, bytes)
                bytesCopied += bytes
                onCopy(bytesCopied)
                bytes = read(buffer)
            }
            return bytesCopied
        }
    }

    private fun setProgress(totalBytesCopied: Long) {
        if (song.size != null) {
            progress.postValue((totalBytesCopied * 100 / song.size!!).toInt())
        }
    }

    private fun updateModificationDate(file: File) {
        if (file.exists()) {
            val ok = file.setLastModified(System.currentTimeMillis())
            if (!ok) {
                Timber.i(
                    "Failed to set last-modified date on %s, trying alternate method",
                    file
                )
                try {
                    // Try alternate method to update last modified date to current time
                    // Found at https://code.google.com/p/android/issues/detail?id=18624
                    // According to the bug, this was fixed in Android 8.0 (API 26)
                    val raf = RandomAccessFile(file, "rw")
                    val length = raf.length()
                    raf.setLength(length + 1)
                    raf.setLength(length)
                    raf.close()
                } catch (e: Exception) {
                    Timber.w(e, "Failed to set last-modified date on %s", file)
                }
            }
        }
    }

    override fun compareTo(other: DownloadFile): Int {
        return priority.compareTo(other.priority)
    }

    companion object {
        const val MAX_RETRIES = 5
    }
}
