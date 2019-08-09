package com.automattic.photoeditor.camera

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.camera.core.*
import com.automattic.photoeditor.R
import com.automattic.photoeditor.camera.interfaces.VideoRecorderFragment
import com.automattic.photoeditor.util.FileUtils
import com.automattic.photoeditor.util.PermissionUtils
import com.automattic.photoeditor.views.background.video.AutoFitTextureView
import java.io.File

class CameraXBasicHandling : VideoRecorderFragment(),
        ActivityCompat.OnRequestPermissionsResultCallback {
    /**
     * An [AutoFitTextureView] for camera preview.
     */
    lateinit var textureView: AutoFitTextureView

    private lateinit var videoCapture: VideoCapture

    private var active: Boolean = false

    var currentFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true
    }

    override fun activate() {
        active = true
        startUp()
    }

    override fun deactivate() {
        active = false
        windDown()
    }

    private fun startUp() {
        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
//        if (textureView.isAvailable && active) {
//            startCamera()
//        }
        if (active) {
            startCamera()
        }
    }

    private fun windDown() {
        if (CameraX.isBound(videoCapture)) {
            CameraX.unbind(videoCapture)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (!PermissionUtils.allRequiredPermissionsGranted(activity!!)) {
            ErrorDialog.newInstance(getString(R.string.request_permissions))
                .show(childFragmentManager,
                    FRAGMENT_DIALOG
                )
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    // TODO remove this RestrictedApi annotation once androidx.camera:camera moves out of alpha
    @SuppressLint("RestrictedApi")
    private fun startCamera() {
        // Create configuration object for the viewfinder use case
        val previewConfig = PreviewConfig.Builder().build()
        // Build the viewfinder use case
        val preview = Preview(previewConfig)

        // Create a configuration object for the video use case
        val videoCaptureConfig = VideoCaptureConfig.Builder().apply {
            setTargetRotation(textureView.display.rotation)
        }.build()
        videoCapture = VideoCapture(videoCaptureConfig)

        preview.setOnPreviewOutputUpdateListener {
            textureView.surfaceTexture = it.surfaceTexture
        }

        // Bind use cases to lifecycle
        // CameraX.bindToLifecycle(this, preview, videoCapture)
        CameraX.bindToLifecycle(activity, preview, videoCapture)
    }

    @SuppressLint("RestrictedApi")
    override fun startRecordingVideo() {
        currentFile = FileUtils.getLoopFrameFile(true, "orig_")
        currentFile?.createNewFile()

        videoCapture.startRecording(currentFile, object: VideoCapture.OnVideoSavedListener{
            override fun onVideoSaved(file: File?) {
                Log.i(tag, "Video File : $file")
            }
            override fun onError(useCaseError: VideoCapture.UseCaseError?, message: String?, cause: Throwable?) {
                Log.i(tag, "Video Error: $message")
            }
        })
    }

    @SuppressLint("RestrictedApi")
    override fun stopRecordingVideo() {
        videoCapture.stopRecording()
    }

    companion object {
        private val instance = CameraXBasicHandling()

        private val FRAGMENT_DIALOG = "dialog"
        /**
         * Tag for the [Log].
         */
        private val TAG = "CameraXBasicHandling"

        @JvmStatic fun getInstance(textureView: AutoFitTextureView): CameraXBasicHandling {
            instance.textureView = textureView
            return instance
        }
    }
}