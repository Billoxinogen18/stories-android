/*
 * Copyright 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.automattic.photoeditor.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.CamcorderProfile
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.core.content.ContextCompat
import com.automattic.photoeditor.util.FileUtils
import com.automattic.photoeditor.R
import com.automattic.photoeditor.camera.interfaces.CameraSelection
import com.automattic.photoeditor.camera.interfaces.FlashIndicatorState
import com.automattic.photoeditor.camera.interfaces.FlashIndicatorState.AUTO
import com.automattic.photoeditor.camera.interfaces.FlashIndicatorState.OFF
import com.automattic.photoeditor.camera.interfaces.FlashIndicatorState.ON
import com.automattic.photoeditor.camera.interfaces.ImageCaptureListener
import com.automattic.photoeditor.camera.interfaces.VideoRecorderFragment
import com.automattic.photoeditor.camera.interfaces.camera2LensFacingFromPortkeyCameraSelection
import com.automattic.photoeditor.camera.interfaces.portkeyCameraSelectionFromCamera2LensFacing
import com.automattic.photoeditor.util.PermissionUtils
import com.automattic.photoeditor.views.background.video.AutoFitTextureView
import java.io.IOException
import java.util.Arrays
import java.util.Collections
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

class Camera2BasicHandling : VideoRecorderFragment(), View.OnClickListener {
    /**
     * [TextureView.SurfaceTextureListener] handles several lifecycle events on a
     * [TextureView].
     */
    val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            startUp()
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            // only try running configureTransform if the surface has already been initialized
            // and it has changed its size while already live. configureTransform() can only
            // be run once surface is stable so, on all other cases let's just wait for startUp()
            // to run the full process.
            if (textureView.isAvailable && active) {
                configureTransform(width, height)
            }
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    private var imageCapturedListener: ImageCaptureListener? = null

    /**
     * ID of the current [CameraDevice].
     */
    private lateinit var cameraId: String

    /**
     * A [CameraCaptureSession] for camera preview.
     */
    private var captureSession: CameraCaptureSession? = null

    /**
     * A reference to the opened [CameraDevice].
     */
    private var cameraDevice: CameraDevice? = null

    /**
     * The [android.util.Size] of camera preview.
     */
    private lateinit var previewSize: Size

    /**
     * [CameraDevice.StateCallback] is called when [CameraDevice] changes its state.
     */
    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            this@Camera2BasicHandling.cameraDevice = cameraDevice
            createCameraPreviewSession()
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@Camera2BasicHandling.cameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            onDisconnected(cameraDevice)
            this@Camera2BasicHandling.activity?.finish()
            // TODO decide whether to inform user about the error
        }
    }

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private var backgroundThread: HandlerThread? = null

    /**
     * A [Handler] for running tasks in the background.
     */
    private var backgroundHandler: Handler? = null

    /**
     * An [ImageReader] that handles still image capture.
     */
    private var imageReader: ImageReader? = null

    /**
     * This a callback object for the [ImageReader]. "onImageAvailable" will be called when a
     * still image is ready to be saved.
     */
    private val onImageAvailableListener = ImageReader.OnImageAvailableListener {
        backgroundHandler?.post(ImageSaver(it.acquireNextImage(), currentFile!!, imageCapturedListener))
    }

    /**
     * [CaptureRequest.Builder] for the camera preview
     */
    private lateinit var previewRequestBuilder: CaptureRequest.Builder

    /**
     * [CaptureRequest] generated by [.previewRequestBuilder]
     */
    private lateinit var previewRequest: CaptureRequest

    /**
     * The current state of camera state for taking pictures.
     *
     * @see .captureCallback
     */
    private var state = STATE_PREVIEW

    /**
     * A [Semaphore] to prevent the app from exiting before closing the camera.
     */
    private val cameraOpenCloseLock = Semaphore(1)

    /**
     * Orientation of the camera sensor
     */
    private var sensorOrientation = 0

    private var lensFacing = CameraMetadata.LENS_FACING_BACK

    /*
    * Media recorder
    * */
    private var mediaRecorder: MediaRecorder = MediaRecorder()

    /**
     * A [CameraCaptureSession.CaptureCallback] that handles events related to JPEG capture.
     */
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        private fun process(result: CaptureResult) {
            when (state) {
                STATE_PREVIEW -> Unit // Do nothing when the camera preview is working normally.
                STATE_WAITING_LOCK -> capturePicture(result)
                STATE_WAITING_PRECAPTURE -> {
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        state =
                            STATE_WAITING_NON_PRECAPTURE
                    }
                }
                STATE_WAITING_NON_PRECAPTURE -> {
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        state = STATE_PICTURE_TAKEN
                        captureStillPicture()
                    }
                }
            }
        }

        private fun capturePicture(result: CaptureResult) {
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            if (afState == null) {
                captureStillPicture()
            } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                // CONTROL_AE_STATE can be null on some devices
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                    state = STATE_PICTURE_TAKEN
                    captureStillPicture()
                } else {
                    runPrecaptureSequence()
                }
            }
        }

        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult
        ) {
            process(partialResult)
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            process(result)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true
    }

    override fun onResume() {
        super.onResume()
        startUp()
    }

    override fun onPause() {
        windDown()
        super.onPause()
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
        if (textureView.isAvailable && active) {
            startBackgroundThread()
            openCamera(textureView.width, textureView.height)
        }
    }

    private fun windDown() {
        captureSession?.apply {
            stopRepeating()
            abortCaptures()
        }
        closeCamera()
        stopBackgroundThread()
    }

    /**
     * Sets up member variables related to camera.
     *
     * @param width The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    private fun setUpCameraOutputs(width: Int, height: Int) {
        val manager = activity?.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)

                val cameraDirection = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (cameraDirection != null && lensFacing != cameraDirection) {
                    continue
                }

                val map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue

                // For still image captures, we use the largest available size.
                val largest = Collections.max(
                        Arrays.asList(*map.getOutputSizes(ImageFormat.JPEG)),
                    CompareSizesByArea()
                )
                imageReader = ImageReader.newInstance(largest.width, largest.height,
                        ImageFormat.JPEG, /*maxImages*/ 2).apply {
                    setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
                }

                // Find out if we need to swap dimension to get the preview size relative to sensor
                // coordinate.
                val displayRotation = activity!!.windowManager.defaultDisplay.rotation

                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: continue
                val swappedDimensions = areDimensionsSwapped(displayRotation)

                val metrics = DisplayMetrics().also { textureView.display.getRealMetrics(it) }
                val displaySize = Point(metrics.widthPixels, metrics.heightPixels)

                val rotatedPreviewWidth = if (swappedDimensions) height else width
                val rotatedPreviewHeight = if (swappedDimensions) width else height
                var maxPreviewWidth = if (swappedDimensions) displaySize.y else displaySize.x
                var maxPreviewHeight = if (swappedDimensions) displaySize.x else displaySize.y

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) maxPreviewWidth =
                    MAX_PREVIEW_WIDTH
                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) maxPreviewHeight =
                    MAX_PREVIEW_HEIGHT

                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                previewSize = chooseOptimalSize(
                    map.getOutputSizes(SurfaceTexture::class.java),
                    rotatedPreviewWidth, rotatedPreviewHeight,
                    maxPreviewWidth, maxPreviewHeight,
                    largest
                )

                // We fit the aspect ratio of TextureView to the size of preview we picked.
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    textureView.setAspectRatio(previewSize.width, previewSize.height)
                } else {
                    textureView.setAspectRatio(previewSize.height, previewSize.width)
                }

                // Check if the flash is supported.
                flashSupported =
                        characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

                this.cameraId = cameraId

                // We've found a viable camera and finished setting up member variables,
                // so we don't need to iterate through other available cameras.
                return
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
            // TODO inform the user
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                    .show(childFragmentManager,
                        FRAGMENT_DIALOG
                    )
        }
    }

    /**
     * Determines if the dimensions are swapped given the phone's current rotation.
     *
     * @param displayRotation The current rotation of the display
     *
     * @return true if the dimensions are swapped, false otherwise.
     */
    private fun areDimensionsSwapped(displayRotation: Int): Boolean {
        var swappedDimensions = false
        when (displayRotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                if (sensorOrientation == 90 || sensorOrientation == 270) {
                    swappedDimensions = true
                }
            }
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                if (sensorOrientation == 0 || sensorOrientation == 180) {
                    swappedDimensions = true
                }
            }
            else -> {
                Log.e(TAG, "Display rotation is invalid: $displayRotation")
            }
        }
        return swappedDimensions
    }

    /**
     * Opens the camera specified by [Camera2BasicHandling.cameraId].
     */
    private fun openCamera(width: Int, height: Int) {
        activity?.let { activity ->
            val permission = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
            if (permission != PackageManager.PERMISSION_GRANTED) {
                PermissionUtils.requestAllRequiredPermissions(activity)
                return
            }
            setUpCameraOutputs(width, height)
            configureTransform(width, height)
            val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            try {
                // Wait for camera to open - 2.5 seconds is sufficient
                if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                    throw RuntimeException("Time out waiting to lock camera opening.")
                }
                manager.openCamera(cameraId, stateCallback, backgroundHandler)
            } catch (e: CameraAccessException) {
                Log.e(TAG, e.toString())
            } catch (e: InterruptedException) {
                throw RuntimeException("Interrupted while trying to lock camera opening.", e)
            }
        }
    }

    /**
     * Closes the current [CameraDevice].
     */
    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    /**
     * Starts a background thread and its [Handler].
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread?.looper!!)
    }

    /**
     * Stops the background thread and its [Handler].
     */
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, e.toString())
        }
    }

    /**
     * Creates a new [CameraCaptureSession] for camera preview.
     */
    private fun createCameraPreviewSession() {
        try {
            val texture = textureView.surfaceTexture

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)

            // This is the output Surface we need to start preview.
            val surface = Surface(texture)

            // We set up a CaptureRequest.Builder with the output Surface.
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW
            )
            previewRequestBuilder.addTarget(surface)

            // Here, we create a CameraCaptureSession for camera preview.
            cameraDevice?.createCaptureSession(Arrays.asList(surface, imageReader?.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                            // The camera is already closed
                            if (cameraDevice == null) return

                            // When the session is ready, we start displaying the preview.
                            captureSession = cameraCaptureSession
                            try {
                                // Auto focus should be continuous for camera preview.
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                // set flash mode
                                setCamera2FlashModeFromPortkeyRequestedFlashMode(previewRequestBuilder)

                                // Finally, we start displaying the camera preview.
                                previewRequest = previewRequestBuilder.build()
                                captureSession?.setRepeatingRequest(previewRequest,
                                        captureCallback, backgroundHandler)
                            } catch (e: CameraAccessException) {
                                Log.e(TAG, e.toString())
                                // TODO: capture error, inform the user about it
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            // TODO: capture error, inform the user about it
                        }
                    }, null)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
    }

    /**
     * Configures the necessary [android.graphics.Matrix] transformation to `textureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `textureView` is fixed.
     *
     * @param viewWidth The width of `textureView`
     * @param viewHeight The height of `textureView`
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        activity ?: return
        val rotation = activity!!.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            val scale = Math.max(
                    viewHeight.toFloat() / previewSize.height,
                    viewWidth.toFloat() / previewSize.width)
            with(matrix) {
                setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                postScale(scale, scale, centerX, centerY)
                postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            }
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        textureView.setTransform(matrix)
    }

    /**
     * Lock the focus as the first step for a still image capture.
     */
    private fun lockFocus() {
        try {
            // This is how to tell the camera to lock focus.
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START)
            // Tell #captureCallback to wait for the lock.
            state = STATE_WAITING_LOCK
            captureSession?.capture(previewRequestBuilder.build(), captureCallback,
                    backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
    }

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when
     * we get a response in [.captureCallback] from [.lockFocus].
     */
    private fun runPrecaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            setCamera2FlashModeFromPortkeyRequestedFlashMode(previewRequestBuilder)
            // Tell #captureCallback to wait for the precapture sequence to be set.
            state = STATE_WAITING_PRECAPTURE
            captureSession?.capture(previewRequestBuilder.build(), captureCallback,
                    backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
    }

    /**
     * Capture a still picture. This method should be called when we get a response in
     * [.captureCallback] from both [.lockFocus].
     */
    private fun captureStillPicture() {
        try {
            if (activity == null || cameraDevice == null) return
            val rotation = activity!!.windowManager.defaultDisplay.rotation

            // This is the CaptureRequest.Builder that we use to take a picture.
            val captureBuilder = cameraDevice!!.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imageReader?.surface!!)

                // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
                // We have to take that into account and rotate JPEG properly.
                // For devices with orientation of 90, we return our mapping from ORIENTATIONS.
                // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
                set(CaptureRequest.JPEG_ORIENTATION,
                        (ORIENTATIONS.get(rotation) + sensorOrientation + 270) % 360)

                // Use the same AE and AF modes as the preview.
                set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }.also { setCamera2FlashModeFromPortkeyRequestedFlashMode(it) }

            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    Log.d(TAG, currentFile.toString())
                    unlockFocus()
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    super.onCaptureFailed(session, request, failure)
                    imageCapturedListener?.onError(failure.toString(), null)
                }
            }

            captureSession?.apply {
                stopRepeating()
                abortCaptures()
                capture(captureBuilder.build(), captureCallback, null)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
            imageCapturedListener?.onError(e.message.orEmpty(), e)
        }
    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is
     * finished.
     */
    private fun unlockFocus() {
        try {
            // Reset the auto-focus trigger
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            // set flash mode
            setCamera2FlashModeFromPortkeyRequestedFlashMode(previewRequestBuilder)
            captureSession?.capture(previewRequestBuilder.build(), captureCallback,
                    backgroundHandler)
            // After this, the camera will go back to the normal state of preview.
            state = STATE_PREVIEW
            captureSession?.setRepeatingRequest(previewRequest, captureCallback,
                    backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
    }

    override fun onClick(view: View) {
//        when (view.id) {
//            R.id.picture -> lockFocus()
//            R.id.info -> {
//                if (activity != null) {
//                    AlertDialog.Builder(activity)
//                            .setMessage(R.string.intro_message)
//                            .setPositiveButton(android.R.string.ok, null)
//                            .show()
//                }
//            }
//        }
    }

    private fun setCamera2FlashModeFromPortkeyRequestedFlashMode(requestBuilder: CaptureRequest.Builder) {
        if (flashSupported) {
            when (currentFlashState.currentFlashState()) {
                AUTO -> requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                ON -> requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                OFF -> requestBuilder.set(CaptureRequest.FLASH_MODE,
                    CaptureRequest.FLASH_MODE_OFF)
            }
        }
    }

    /******************************************/
    /* methods for video recording start here */
    /******************************************/

    @Throws(IOException::class)
    private fun setUpMediaRecorder() {
        val activity: Activity? = getActivity()
        if (activity == null) return

        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        /**
        * create video output file
        */
        currentFile = FileUtils.getLoopFrameFile(true, "orig_")
        currentFile?.createNewFile()

        /**
        * set output file in media recorder
        */
        mediaRecorder.setOutputFile(currentFile?.getAbsolutePath())
        val profile: CamcorderProfile = CamcorderProfile.get(CamcorderProfile.QUALITY_480P)
        mediaRecorder.setVideoFrameRate(profile.videoFrameRate)
        mediaRecorder.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight)
        mediaRecorder.setVideoEncodingBitRate(profile.videoBitRate)
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mediaRecorder.setAudioEncodingBitRate(profile.audioBitRate)
        mediaRecorder.setAudioSamplingRate(profile.audioSampleRate)

        val rotation = activity.getWindowManager().getDefaultDisplay().getRotation()
        when (sensorOrientation) {
            SENSOR_ORIENTATION_DEFAULT_DEGREES ->
                mediaRecorder.setOrientationHint(ORIENTATIONS.get(rotation))
            SENSOR_ORIENTATION_INVERSE_DEGREES ->
                mediaRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation))
        }
        mediaRecorder.prepare()
    }

    /**
     * Creates a new [CameraCaptureSession] for actual video recording.
     */
    override fun startRecordingVideo() {
        try {
            closePreviewSession()
            setUpMediaRecorder()

            val texture = textureView.surfaceTexture

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)

            // This is the output Surface we need to start preview.
            val surface = Surface(texture)

            // We set up a CaptureRequest.Builder with the output Surface.
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(
                CameraDevice.TEMPLATE_RECORD
            )
            previewRequestBuilder.addTarget(surface)

            val recorderSurface = mediaRecorder.surface
            previewRequestBuilder.addTarget(recorderSurface)

            // Here, we create a CameraCaptureSession for camera recording + preview.
            cameraDevice?.createCaptureSession(Arrays.asList(surface, recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        // The camera is already closed
                        if (cameraDevice == null) return

                        // When the session is ready, we start displaying the preview.
                        captureSession = cameraCaptureSession
                        try {
                            // Auto focus should be continuous for camera preview.
                            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            // set flash mode
                            setCamera2FlashModeFromPortkeyRequestedFlashMode(previewRequestBuilder)

                            // Finally, we start displaying the camera preview.
                            previewRequest = previewRequestBuilder.build()
                            captureSession?.setRepeatingRequest(previewRequest,
                                captureCallback, backgroundHandler)

                            mediaRecorder.start()
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, e.toString())
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        // TODO: capture error, inform the user about it
                    }
                }, null)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
    }

    override fun stopRecordingVideo() {
        captureSession?.apply {
            stopRepeating()
            abortCaptures()
        }

        mediaRecorder.apply {
            stop()
            reset()
        }
    }

    fun closePreviewSession() {
        captureSession?.close()
        captureSession = null
    }

    override fun takePicture(onImageCapturedListener: ImageCaptureListener) {
        // Create output file to hold the image
        currentFile = FileUtils.getLoopFrameFile(false, "orig_")
        currentFile?.createNewFile()
        imageCapturedListener = onImageCapturedListener
        lockFocus()
    }

    override fun flipCamera(): CameraSelection {
        lensFacing = if (CameraMetadata.LENS_FACING_FRONT == lensFacing) {
            CameraMetadata.LENS_FACING_BACK
        } else {
            CameraMetadata.LENS_FACING_FRONT
        }
        windDown()
        startUp()
        return portkeyCameraSelectionFromCamera2LensFacing(lensFacing)
    }

    override fun selectCamera(camera: CameraSelection) {
        lensFacing = camera2LensFacingFromPortkeyCameraSelection(camera)
    }

    override fun currentCamera(): CameraSelection {
        return portkeyCameraSelectionFromCamera2LensFacing(lensFacing)
    }

    override fun isFlashAvailable(): Boolean {
        return flashSupported
    }

    override fun advanceFlashState() {
        super.advanceFlashState()
        if (active) {
            windDown()
            startUp()
        }
    }

    override fun setFlashState(flashIndicatorState: FlashIndicatorState) {
        super.setFlashState(flashIndicatorState)
        if (active) {
            windDown()
            startUp()
        }
    }

    companion object {
        /**
         * Conversion from screen rotation to JPEG orientation.
         */
        private val ORIENTATIONS = SparseIntArray()
        private val INVERSE_ORIENTATIONS = SparseIntArray()
        private val FRAGMENT_DIALOG = "dialog"
        private val SENSOR_ORIENTATION_DEFAULT_DEGREES = 90
        private val SENSOR_ORIENTATION_INVERSE_DEGREES = 270
        private val instance = Camera2BasicHandling()

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)

            INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0)
            INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90)
            INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180)
            INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270)
        }

        /**
         * Tag for the [Log].
         */
        private val TAG = "Camera2BasicHandling"

        /**
         * Camera state: Showing camera preview.
         */
        private val STATE_PREVIEW = 0

        /**
         * Camera state: Waiting for the focus to be locked.
         */
        private val STATE_WAITING_LOCK = 1

        /**
         * Camera state: Waiting for the exposure to be precapture state.
         */
        private val STATE_WAITING_PRECAPTURE = 2

        /**
         * Camera state: Waiting for the exposure state to be something other than precapture.
         */
        private val STATE_WAITING_NON_PRECAPTURE = 3

        /**
         * Camera state: Picture was taken.
         */
        private val STATE_PICTURE_TAKEN = 4

        /**
         * Max preview width that is guaranteed by Camera2 API
         */
        private val MAX_PREVIEW_WIDTH = 1920

        /**
         * Max preview height that is guaranteed by Camera2 API
         */
        private val MAX_PREVIEW_HEIGHT = 1080

        /**
         * Given `choices` of `Size`s supported by a camera, choose the smallest one that
         * is at least as large as the respective texture view size, and that is at most as large as
         * the respective max size, and whose aspect ratio matches with the specified value. If such
         * size doesn't exist, choose the largest one that is at most as large as the respective max
         * size, and whose aspect ratio matches with the specified value.
         *
         * @param choices The list of sizes that the camera supports for the intended
         *                          output class
         * @param textureViewWidth The width of the texture view relative to sensor coordinate
         * @param textureViewHeight The height of the texture view relative to sensor coordinate
         * @param maxWidth The maximum width that can be chosen
         * @param maxHeight The maximum height that can be chosen
         * @param aspectRatio The aspect ratio
         * @return The optimal `Size`, or an arbitrary one if none were big enough
         */
        @JvmStatic private fun chooseOptimalSize(
            choices: Array<Size>,
            textureViewWidth: Int,
            textureViewHeight: Int,
            maxWidth: Int,
            maxHeight: Int,
            aspectRatio: Size
        ): Size {
            // Collect the supported resolutions that are at least as big as the preview Surface
            val bigEnough = ArrayList<Size>()
            // Collect the supported resolutions that are smaller than the preview Surface
            val notBigEnough = ArrayList<Size>()
            val w = aspectRatio.width
            val h = aspectRatio.height
            for (option in choices) {
                if (option.width <= maxWidth && option.height <= maxHeight &&
                        option.height == option.width * h / w) {
                    if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                        bigEnough.add(option)
                    } else {
                        notBigEnough.add(option)
                    }
                }
            }

            // Pick the smallest of those big enough. If there is no one big enough, pick the
            // largest of those not big enough.
            if (bigEnough.size > 0) {
                return Collections.min(bigEnough, CompareSizesByArea())
            } else if (notBigEnough.size > 0) {
                return Collections.max(notBigEnough, CompareSizesByArea())
            } else {
                Log.e(TAG, "Couldn't find any suitable preview size")
                return choices[0]
            }
        }

        @JvmStatic fun getInstance(
            textureView: AutoFitTextureView,
            flashSupportChangeListener: FlashSupportChangeListener
        ): Camera2BasicHandling {
            instance.textureView = textureView
            instance.flashSupportChangeListener = flashSupportChangeListener
            return instance
        }
    }
}
