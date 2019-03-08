package com.locatible.qrcodereader

import android.content.Context
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.widget.Button
import android.widget.EditText
import com.scandit.barcodepicker.ScanSettings
import com.scandit.recognition.*
import org.bytedeco.javacpp.avutil.*
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.ByteBuffer
import android.R.id.edit
import android.content.SharedPreferences
import android.app.Activity
import android.databinding.DataBindingUtil
import com.locatible.qrcodereader.databinding.ActivityMainBinding
import android.databinding.BaseObservable
import android.databinding.Bindable
import com.locatible.qrcodereader.BR
import android.databinding.ObservableBoolean
import android.databinding.ObservableField

class MainActivity : AppCompatActivity() {

    internal var model: MainActivityViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        init()
    }

    private fun init() {
        val binding: ActivityMainBinding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        model = MainActivityViewModel(this)
        binding.model = model
    }
}

internal class MainActivityViewModel(private val activity: MainActivity) {

    private val pref = activity.getPreferences(Context.MODE_PRIVATE)
    private var videoProcessor: VideoProcessor? = null
    private var videoProcessorThread: Thread? = null

    var videoUrl: ObservableField<String> = ObservableField<String>(pref.getString(activity.getString(R.string.pref_video_url_key), activity.getString(R.string.pref_video_url_default)))
        set(value) {
            field = value
            with(pref.edit()) {
                putString(activity.getString(R.string.pref_video_url_key), value.get())
                commit()
            }
        }

    var scanditLicense: ObservableField<String> = ObservableField<String>(pref.getString(activity.getString(R.string.pref_scandit_license_key), activity.getString(R.string.pref_scandit_license_default)))
        set(value) {
            field = value
            with(pref.edit()) {
                putString(activity.getString(R.string.pref_scandit_license_key), value.get())
                commit()
            }
        }

    var status: ObservableField<String> = ObservableField<String>("")

    var recognizedCode: ObservableField<String> = ObservableField<String>("")

    var running: ObservableBoolean = ObservableBoolean(false)

    fun toggle() {
        if (running.get()) {
            running.set(false)
            status.set(activity.getString(R.string.stopping))
            if (videoProcessor != null) {
                videoProcessor!!.stop()
                videoProcessor = null
                videoProcessorThread = null
            }
        } else {
            running.set(true)
            status.set(activity.getString(R.string.starting))
            videoProcessor = VideoProcessor(activity)
            videoProcessorThread = Thread(videoProcessor, "VideoProcessor")
            videoProcessorThread!!.start()
        }
    }
}

internal class VideoProcessor(private val activity: MainActivity) : Runnable {

    private val DuplicateFilterDurationMs = 5000

    private val model: MainActivityViewModel = activity.model!!
    private var scanner: BarcodeScanner? = null
    private var recognitionContext: RecognitionContext? = null
    private var scannerSession: BarcodeScannerSession? = null
    private var imageDescription = ImageDescription()
    private var frameData: ByteArray? = null
    private var running = true

    override fun run() {
        try {
            processVideo()
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    fun stop() {
        running = false
    }

    private fun processVideo() {
        createScanner()
        frameData = ByteArray(0)

        while (running) {
            openVideo(model.videoUrl.get()!!).use { video ->
                video.setOption("rtsp_transport", "tcp")
                video.start()
                setImageDescription(video)
                model.status.set(activity.getString(R.string.streaming))

                var i = 0
                while (running) {
                    val frame = video.grabImage()

                    if (frame == null) {
                        scannerSession!!.clear()
                        model.status.set(activity.getString(R.string.done))
                        break
                    }

                    if (updateFrame(frame)) {
                        recognitionContext!!.processFrame(imageDescription, frameData)

                        for (barcode in scannerSession!!.newlyRecognizedCodes) {
                            println(barcode.data)
                        }

                        if (scannerSession!!.newlyRecognizedCodes.size > 0) {
                            model.recognizedCode.set(scannerSession!!.newlyRecognizedCodes.last().data)
                        }

                        // Clear session periodically to avoid memory overflow
                        val clearCycle = 0xfff // Must be 2^x-1
                        if (i and clearCycle == clearCycle) {
                            scannerSession!!.clear()
                        }
                    }

                    i++
                }
            }
        }
    }

    private fun createScanner() {
        val settings = ScanSettings.create()
        settings.setSymbologyEnabled(Barcode.SYMBOLOGY_QR, true)
        settings.codeDuplicateFilter = DuplicateFilterDurationMs

        recognitionContext = RecognitionContext.create(activity, model.scanditLicense.get()!!, activity.noBackupFilesDir)
        recognitionContext!!.startNewFrameSequence()

        scanner = BarcodeScanner.create(recognitionContext!!, settings.barcodeScannerSettings)

        scannerSession = scanner!!.session
    }

    private fun openVideo(url: String): FFmpegFrameGrabber {
        if (url.startsWith("rtsp:")) {
            return FFmpegFrameGrabber(url)

        } else if (url.startsWith("http")) {
            val inputStream = URL(url).openConnection().getInputStream()
            return FFmpegFrameGrabber(inputStream)
        }

        return FFmpegFrameGrabber(url)
    }

    private fun setImageDescription(video: FFmpegFrameGrabber) {
        imageDescription.width = video.imageWidth
        imageDescription.height = video.imageHeight
        imageDescription.layout = getLayout(video.pixelFormat)
    }

    private fun getLayout(pixelFormat: Int): Int {
        when (pixelFormat) {
            AV_PIX_FMT_GRAY8 -> return ImageDescription.IMAGE_LAYOUT_GRAY_8U
            AV_PIX_FMT_RGB24, AV_PIX_FMT_BGR24 -> return ImageDescription.IMAGE_LAYOUT_RGB_8U
            AV_PIX_FMT_RGBA -> return ImageDescription.IMAGE_LAYOUT_RGBA_8U
            AV_PIX_FMT_ARGB -> return ImageDescription.IMAGE_LAYOUT_ARGB_8U
            else -> return ImageDescription.IMAGE_LAYOUT_UNKNOWN
        }
    }

    private fun updateFrame(frame: Frame): Boolean {
        if (frame.image == null || frame.image.size == 0) {
            return false
        }

        val frameBuffer = frame.image[0] as ByteBuffer
        frameBuffer.position(0)
        val size = frameBuffer.remaining()

        if (size == 0) {
            return false
        }

        if (size > frameData!!.size) {
            frameData = ByteArray(size * 2)
        }

        frameBuffer.get(frameData, 0, size)
        imageDescription.setMemorySize(size)

        return true
    }
}
