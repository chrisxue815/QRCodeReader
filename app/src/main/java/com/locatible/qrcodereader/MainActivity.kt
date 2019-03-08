package com.locatible.qrcodereader

import android.content.Context
import android.databinding.BaseObservable
import android.databinding.Bindable
import android.databinding.DataBindingUtil
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import com.locatible.qrcodereader.databinding.ActivityMainBinding
import com.scandit.barcodepicker.ScanSettings
import com.scandit.recognition.*
import org.bytedeco.javacpp.avutil.*
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import java.net.URL
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    internal var model: MainActivityViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        init()
    }

    private fun init() {
        model = MainActivityViewModel(this)

        val binding: ActivityMainBinding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        binding.model = model
    }
}

internal class MainActivityViewModel(private val activity: MainActivity) : BaseObservable() {

    private val pref = activity.getPreferences(Context.MODE_PRIVATE)
    private var videoProcessor: VideoProcessor? = null
    private var videoProcessorThread: Thread? = null

    @get:Bindable
    var videoUrl: String = pref.getString(activity.getString(R.string.pref_video_url_key), activity.getString(R.string.pref_video_url_default))
        set(value) {
            field = value
            notifyPropertyChanged(BR.videoUrl)
            with(pref.edit()) {
                putString(activity.getString(R.string.pref_video_url_key), value)
                apply()
            }
        }

    @get:Bindable
    var scanditLicense: String = pref.getString(activity.getString(R.string.pref_scandit_license_key), activity.getString(R.string.pref_scandit_license_default))
        set(value) {
            field = value
            notifyPropertyChanged(BR.scanditLicense)
            with(pref.edit()) {
                putString(activity.getString(R.string.pref_scandit_license_key), value)
                apply()
            }
        }

    @get:Bindable
    var status: String = ""
        set(value) {
            field = value
            notifyPropertyChanged(BR.status)
        }

    @get:Bindable
    var recognizedCode: String = ""
        set(value) {
            field = value
            notifyPropertyChanged(BR.recognizedCode)
        }

    @get:Bindable
    var running: Boolean = false
        set(value) {
            field = value
            notifyPropertyChanged(BR.running)
        }

    fun reset() {
        videoUrl = activity.getString(R.string.pref_video_url_default)
        scanditLicense = activity.getString(R.string.pref_scandit_license_default)
    }

    fun toggle() {
        if (running) {
            running = false
            status = activity.getString(R.string.stopping)
            if (videoProcessor != null) {
                videoProcessor!!.stop()
                videoProcessor = null
                videoProcessorThread = null
            }
        } else {
            running = true
            status = activity.getString(R.string.starting)
            videoProcessor = VideoProcessor(activity)
            videoProcessorThread = Thread(videoProcessor, "VideoProcessor")
            videoProcessorThread!!.start()
        }
    }
}

internal class VideoProcessor(private val activity: MainActivity) : Runnable {

    private val duplicateFilterDurationMs = 5000
    private val sessionClearCycle = 0xfff // Must be 2^x-1

    private val model: MainActivityViewModel = activity.model!!
    private var scanner: BarcodeScanner
    private var recognitionContext: RecognitionContext
    private var scannerSession: BarcodeScannerSession
    private var imageDescription = ImageDescription()
    private var frameData: ByteArray
    private var running = true

    init {
        val settings = ScanSettings.create()
        settings.setSymbologyEnabled(Barcode.SYMBOLOGY_QR, true)
        settings.codeDuplicateFilter = duplicateFilterDurationMs

        recognitionContext = RecognitionContext.create(activity, model.scanditLicense, activity.noBackupFilesDir)
        recognitionContext.startNewFrameSequence()

        scanner = BarcodeScanner.create(recognitionContext, settings.barcodeScannerSettings)

        scannerSession = scanner.session

        frameData = ByteArray(0)
    }

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
        while (running) {
            openVideo(model.videoUrl).use { video ->
                video.setOption("rtsp_transport", "tcp")
                video.start()

                setImageDescription(video)
                model.status = activity.getString(R.string.streaming)
                var cycle = 0

                while (running) {
                    // Clear session periodically to avoid memory overflow
                    if (cycle and sessionClearCycle == 0) {
                        scannerSession.clear()
                    }

                    val frame = video.grabImage() ?: break

                    if (updateFrame(frame)) {
                        recognitionContext.processFrame(imageDescription, frameData)

                        for (barcode in scannerSession.newlyRecognizedCodes) {
                            println(barcode.data)
                        }

                        if (scannerSession.newlyRecognizedCodes.size > 0) {
                            model.recognizedCode = scannerSession.newlyRecognizedCodes.last().data
                        }
                    }

                    cycle++
                }

                model.status = activity.getString(R.string.done)
            }
        }

        model.status = activity.getString(R.string.stopped)
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
        return when (pixelFormat) {
            AV_PIX_FMT_GRAY8 -> ImageDescription.IMAGE_LAYOUT_GRAY_8U
            AV_PIX_FMT_RGB24, AV_PIX_FMT_BGR24 -> ImageDescription.IMAGE_LAYOUT_RGB_8U
            AV_PIX_FMT_RGBA -> ImageDescription.IMAGE_LAYOUT_RGBA_8U
            AV_PIX_FMT_ARGB -> ImageDescription.IMAGE_LAYOUT_ARGB_8U
            else -> ImageDescription.IMAGE_LAYOUT_UNKNOWN
        }
    }

    private fun updateFrame(frame: Frame): Boolean {
        if (frame.image == null || frame.image.isEmpty()) {
            return false
        }

        val frameBuffer = frame.image[0] as ByteBuffer
        frameBuffer.position(0)
        val size = frameBuffer.remaining()

        if (size == 0) {
            return false
        }

        if (size > frameData.size) {
            frameData = ByteArray(size * 2)
        }

        frameBuffer.get(frameData, 0, size)
        imageDescription.setMemorySize(size)

        return true
    }
}
