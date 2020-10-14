package com.novoda.spikes.arcore

import android.graphics.Bitmap
import android.media.Image
import android.opengl.GLException
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageProxy
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.novoda.spikes.arcore.google.helper.TapHelper
import com.novoda.spikes.arcore.helper.ARCoreDependenciesHelper
import com.novoda.spikes.arcore.helper.ARCoreDependenciesHelper.Result.Failure
import com.novoda.spikes.arcore.helper.ARCoreDependenciesHelper.Result.Success
import com.novoda.spikes.arcore.helper.CameraPermissionHelper
import com.novoda.spikes.arcore.ml.ObjectDetectorProcessor
import com.novoda.spikes.arcore.ml.VisionImageProcessor
import com.novoda.spikes.arcore.rendering.NovodaSurfaceViewRenderer
import kotlinx.android.synthetic.main.activity_main.*
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.opengles.GL10


class MainActivity : AppCompatActivity() {
    private var session: Session? = null
    private var imageAnalyzer: VisionImageProcessor? = null


    private val renderer: NovodaSurfaceViewRenderer by lazy {
        NovodaSurfaceViewRenderer(this, debugViewDisplayer, tapHelper)
    }
    private var snapshotBitmap: Bitmap? = null

    private interface BitmapReadyCallbacks {
        fun onBitmapReady(bitmap: Bitmap?)
    }

    // supporting methods
    private fun captureBitmap(bitmapReadyCallbacks: BitmapReadyCallbacks) {
        surfaceView.queueEvent(Runnable {
            val egl = EGLContext.getEGL() as EGL10
            val gl = egl.eglGetCurrentContext().gl as GL10
            snapshotBitmap = createBitmapFromGLSurface(0, 0, surfaceView.width, surfaceView.height, gl)
            runOnUiThread { bitmapReadyCallbacks.onBitmapReady(snapshotBitmap) }
        })
    }

    @Throws(OutOfMemoryError::class)
    private fun createBitmapFromGLSurface(x: Int, y: Int, w: Int, h: Int, gl: GL10): Bitmap? {
        val bitmapBuffer = IntArray(w * h)
        val bitmapSource = IntArray(w * h)
        val intBuffer: IntBuffer = IntBuffer.wrap(bitmapBuffer)
        intBuffer.position(0)
        try {
            gl.glReadPixels(x, y, w, h, GL10.GL_RGBA, GL10.GL_UNSIGNED_BYTE, intBuffer)
            var offset1: Int
            var offset2: Int
            for (i in 0 until h) {
                offset1 = i * w
                offset2 = (h - i - 1) * w
                for (j in 0 until w) {
                    val texturePixel = bitmapBuffer[offset1 + j]
                    val blue = texturePixel shr 16 and 0xff
                    val red = texturePixel shl 16 and 0x00ff0000
                    val pixel = texturePixel and -0xff0100 or red or blue
                    bitmapSource[offset2 + j] = pixel
                }
            }
        } catch (e: GLException) {
            return null
        }
        return Bitmap.createBitmap(bitmapSource, w, h, Bitmap.Config.ARGB_8888)
    }

    private val debugViewDisplayer: DebugViewDisplayer by lazy {
        DebugViewDisplayer(debugTextView)
    }
    private val tapHelper: TapHelper by lazy {
        TapHelper(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupSurfaceView()

        val options = ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableClassification()  // Optional
                .build()
        imageAnalyzer = ObjectDetectorProcessor(this, options)

        testButton.setOnClickListener {
            captureBitmap(object : BitmapReadyCallbacks {
                override fun onBitmapReady(bitmap: Bitmap?) {
                    testImageView.setImageBitmap(bitmap)
                    imageAnalyzer!!.processBitmap(bitmap!!, graphic_overlay)
                }
            })
        }
    }

    private fun setupSurfaceView() {
        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0) // Alpha used for plane blending.
        surfaceView.setRenderer(renderer)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surfaceView.setOnTouchListener(tapHelper)
    }

    override fun onResume() {
        super.onResume()
        checkAREnvironment()
    }

    private fun checkAREnvironment() {
        ARCoreDependenciesHelper.isARCoreIsInstalled(this).apply {
            when {
                this is Failure -> showMessage(message)
                this === Success && CameraPermissionHelper.isCameraPermissionGranted(this@MainActivity) -> createOrResumeARSession()
            }
        }
    }

    private fun createOrResumeARSession() {
        if (session == null) {
            session = Session(this).apply {
                renderer.setSession(this)
            }

        }
        // Note that order matters - see the note in onPause(), the reverse applies here.
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            // In some cases the camera may be given to a different app instead. Recreate the session at the next iteration.
            showMessage("Camera not available. Please restart the app.")
            return
        }
        surfaceView.onResume()
    }

    public override fun onPause() {
        super.onPause()
        if (session != null) {
            // Note that the order matters - GLSurfaceView is paused first so that it does not try
            // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
            // still call session.update() and get a SessionPausedException.
            surfaceView.onPause()
            session?.pause()
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

}
