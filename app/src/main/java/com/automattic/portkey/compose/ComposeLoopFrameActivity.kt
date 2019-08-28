package com.automattic.portkey.compose

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.hardware.Camera
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.TextUtils
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.widget.Toast
import androidx.constraintlayout.widget.Group
import androidx.core.content.ContextCompat
import com.automattic.photoeditor.OnPhotoEditorListener
import com.automattic.photoeditor.PhotoEditor
import com.automattic.photoeditor.SaveSettings
import com.automattic.photoeditor.camera.interfaces.FlashIndicatorState
import com.automattic.photoeditor.camera.interfaces.ImageCaptureListener
import com.automattic.photoeditor.camera.interfaces.VideoRecorderFragment.FlashSupportChangeListener
import com.automattic.photoeditor.state.BackgroundSurfaceManager
import com.automattic.photoeditor.util.FileUtils.Companion.getLoopFrameFile
import com.automattic.photoeditor.util.PermissionUtils
import com.automattic.photoeditor.views.ViewType
import com.automattic.portkey.BuildConfig
import com.automattic.portkey.R.color
import com.automattic.portkey.R.layout
import com.automattic.portkey.R.string
import com.bumptech.glide.Glide
import com.google.android.material.snackbar.Snackbar

import kotlinx.android.synthetic.main.content_composer.*
import java.io.File
import java.io.IOException
import android.view.Gravity
import android.view.MotionEvent
import android.view.View.OnClickListener
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import com.automattic.photoeditor.camera.interfaces.CameraSelection
import com.automattic.photoeditor.camera.interfaces.VideoRecorderFinished
import com.automattic.photoeditor.views.ViewType.TEXT
import com.automattic.portkey.R
import com.automattic.portkey.compose.text.TextEditorDialogFragment
import com.automattic.portkey.compose.emoji.EmojiPickerFragment
import com.automattic.portkey.compose.emoji.EmojiPickerFragment.EmojiListener
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners

fun Group.setAllOnClickListener(listener: View.OnClickListener?) {
    referencedIds.forEach { id ->
        rootView.findViewById<View>(id).setOnClickListener(listener)
    }
}

fun Snackbar.config(context: Context) {
    this.view.background = context.getDrawable(R.drawable.snackbar_background)

    // Workaround to adjust Snackbar position as otherwise it appears with a height above the navigation bar
    // This should be fixed in 'com.google.android.material:material:1.1.0' when it comes out of alpha
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingTop)

        val params = v.layoutParams as ViewGroup.MarginLayoutParams
        params.setMargins(12, 12, 12, 12)
        v.layoutParams = params

        insets
    }
    ViewCompat.setElevation(this.view, 6f)
}

class ComposeLoopFrameActivity : AppCompatActivity() {
    private lateinit var photoEditor: PhotoEditor
    private lateinit var backgroundSurfaceManager: BackgroundSurfaceManager
    private var currentOriginalCapturedFile: File? = null

    private val timesUpRunnable = Runnable {
        stopRecordingVideo(false) // time's up, it's not a cancellation
    }
    private val timesUpHandler = Handler()

    private var cameraSelection = CameraSelection.BACK
    private var flashModeSelection = FlashIndicatorState.OFF
    private var videoPlayerMuted = false
    private val FRAGMENT_DIALOG = "dialog"

    private lateinit var emojiPickerFragment: EmojiPickerFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layout.activity_composer)

        photoEditor = PhotoEditor.Builder(this, photoEditorView)
            .setPinchTextScalable(true) // set flag to make text scalable when pinch
            .setDeleteView(delete_view)
            .build() // build photo editor sdk

        photoEditor.setOnPhotoEditorListener(object : OnPhotoEditorListener {
            override fun onEditTextChangeListener(rootView: View, text: String, colorCode: Int, isJustAdded: Boolean) {
                editModeHideAllUIControls()
                if (isJustAdded) {
                    // hide new text views
                    rootView.visibility = View.GONE
                }
                val textEditorDialogFragment = TextEditorDialogFragment.show(
                    this@ComposeLoopFrameActivity,
                    text,
                    colorCode)
                textEditorDialogFragment.setOnTextEditorListener(object : TextEditorDialogFragment.TextEditor {
                    override fun onDone(inputText: String, colorCode: Int) {
                        // make sure to set it to visible, as newly added views are originally hidden until
                        // proper text is set
                        rootView.visibility = View.VISIBLE
                        if (TextUtils.isEmpty(inputText)) {
                            // just remove the view here, we don't need it - also don't  add to the `redo` stack
                            photoEditor.viewUndo(rootView, TEXT, false)
                        } else {
                            photoEditor.editText(rootView, inputText, colorCode)
                        }
                        // TODO hardcoded noSound parameter here
                        editModeRestoreAllUIControls(true)
                    }
                })
            }

            override fun onAddViewListener(viewType: ViewType, numberOfAddedViews: Int) {
                // only show save button if any views have been added
                save_button.visibility = View.VISIBLE
            }

            override fun onRemoveViewListener(viewType: ViewType, numberOfAddedViews: Int) {
                if (photoEditor.anyViewsAdded()) {
                    // only show save button if any views have been added
                    save_button.visibility = View.VISIBLE
                } else {
                    save_button.visibility = View.INVISIBLE
                }
            }

            override fun onStartViewChangeListener(viewType: ViewType) {
                // no op
            }

            override fun onStopViewChangeListener(viewType: ViewType) {
                // no op
            }

            override fun onRemoveViewListener(numberOfAddedViews: Int) {
                // no op
            }

            override fun onRemoveViewReadyListener(removedView: View, ready: Boolean) {
                delete_view.setReadyForDelete(ready)
            }
        })

        backgroundSurfaceManager = BackgroundSurfaceManager(
            savedInstanceState,
            lifecycle,
            photoEditorView,
            supportFragmentManager,
            object : FlashSupportChangeListener {
                override fun onFlashSupportChanged(isSupported: Boolean) {
                    if (isSupported) {
                        camera_flash_button.visibility = View.VISIBLE
                        label_flash.visibility = View.VISIBLE
                    } else {
                        camera_flash_button.visibility = View.INVISIBLE
                        label_flash.visibility = View.INVISIBLE
                    }
                }
            },
            BuildConfig.USE_CAMERAX)

        lifecycle.addObserver(backgroundSurfaceManager)

        emojiPickerFragment = EmojiPickerFragment()
        emojiPickerFragment.setEmojiListener(object : EmojiListener {
            override fun onEmojiClick(emojiUnicode: String) {
                photoEditor.addEmoji(emojiUnicode)
            }
        })

        // add click listeners
        addClickListeners()

        if (savedInstanceState == null) {
            // small tweak to make sure to not show the background image for the static image background mode
            backgroundSurfaceManager.preTurnTextureViewOn()

            // check camera selection, flash state from preferences
            cameraSelection =
                CameraSelection.valueOf(
                    getPreferences(Context.MODE_PRIVATE).getInt(getString(R.string.pref_camera_selection), 0))!!
            flashModeSelection =
                FlashIndicatorState.valueOf(
                    getPreferences(Context.MODE_PRIVATE).getInt(getString(R.string.pref_flash_mode_selection), 0))!!

            // also, update the UI
            updateFlashModeSelectionIcon()

            photoEditorView.postDelayed({
                launchCameraPreview()
            }, SURFACE_MANAGER_READY_LAUNCH_DELAY)
        } else {
            currentOriginalCapturedFile =
                savedInstanceState.getSerializable(STATE_KEY_CURRENT_ORIGINAL_CAPTURED_FILE) as File?

            photoEditorView.postDelayed({
                if (backgroundSurfaceManager.videoPlayerVisible()) {
                    showPlayVideo(currentOriginalCapturedFile)
                } else if (backgroundSurfaceManager.cameraVisible()) {
                    launchCameraPreview()
                } else {
                    Glide.with(this@ComposeLoopFrameActivity)
                        .load(currentOriginalCapturedFile)
                        .into(photoEditorView.source)
                    showStaticBackground()
                }
            }, SURFACE_MANAGER_READY_LAUNCH_DELAY)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI(window)
    }

    override fun onResume() {
        super.onResume()
        // Before setting full screen flags, we must wait a bit to let UI settle; otherwise, we may
        // be trying to set app to immersive mode before it's ready and the flags do not stick
        photoEditorView.postDelayed({
                hideSystemUI(window)
        }, IMMERSIVE_FLAG_TIMEOUT)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        backgroundSurfaceManager.saveStateToBundle(outState)
        outState.putSerializable(STATE_KEY_CURRENT_ORIGINAL_CAPTURED_FILE, currentOriginalCapturedFile)
        super.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (PermissionUtils.allRequiredPermissionsGranted(this)) {
            backgroundSurfaceManager.switchCameraPreviewOn()
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    override fun onBackPressed() {
        if (!backgroundSurfaceManager.cameraVisible()) {
            close_button.performClick()
        } else {
            super.onBackPressed()
        }
    }

    private fun addClickListeners() {
        camera_capture_button
            .setOnTouchListener(
                PressAndHoldGestureHelper(
                    PressAndHoldGestureHelper.CLICK_LENGTH,
                    object : PressAndHoldGestureListener {
                        override fun onClickGesture() {
                            if (!backgroundSurfaceManager.cameraRecording()) {
                                takeStillPicture()
                            }
                        }
                        override fun onHoldingGestureStart() {
                            startRecordingVideoAfterVibrationIndication()
                        }

                        override fun onHoldingGestureEnd() {
                            stopRecordingVideo(false)
                        }

                        override fun onHoldingGestureCanceled() {
                            stopRecordingVideo(true)
                        }

                        override fun onStartDetectionWait() {
                            // when the wait to see whether this is a "press and hold" gesture starts,
                            // start the animation to grow the capture button radius
                            camera_capture_button
                                .animate()
                                .scaleXBy(0.3f) // scale up by 30%
                                .scaleYBy(0.3f)
                                .duration = PressAndHoldGestureHelper.CLICK_LENGTH
                        }

                        override fun onTouchEventDetectionEnd() {
                            // when gesture detection ends, we're good to
                            // get the capture button shape as it originally was (idle state)
                            camera_capture_button.clearAnimation()
                            camera_capture_button
                                .animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .duration = PressAndHoldGestureHelper.CLICK_LENGTH / 4
                        }
                    })
            )

        gallery_upload_img.setOnClickListener {
            // TODO implement tapping on thumbnail
            Toast.makeText(this, "not implemented yet", Toast.LENGTH_SHORT).show()
        }

        camera_flip_group.setAllOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                cameraSelection = backgroundSurfaceManager.flipCamera()
                saveCameraSelectionPref()
            }
        })

        // attach listener a bit delayed as we need to have cameraBasicHandling created first
        photoEditorView.postDelayed({
            camera_flash_group.setAllOnClickListener(object : View.OnClickListener {
                override fun onClick(v: View) {
                    flashModeSelection = backgroundSurfaceManager.switchFlashState()
                    updateFlashModeSelectionIcon()
                    saveFlashModeSelectionPref()
                }
            })
        }, SURFACE_MANAGER_READY_LAUNCH_DELAY)

        close_button.setOnClickListener {
            // add discard dialog
            if (photoEditor.anyViewsAdded()) {
                // show dialog
                DiscardDialog.newInstance(getString(string.dialog_discard_message), object : DiscardOk {
                    override fun discardOkClicked() {
                        photoEditor.clearAllViews()
                        launchCameraPreview()
                        deleteCapturedMedia()
                    }
                }).show(supportFragmentManager, FRAGMENT_DIALOG)
            } else {
                deleteCapturedMedia()
                launchCameraPreview()
            }
        }

        sound_button_group.setOnClickListener {
            if (videoPlayerMuted) {
                backgroundSurfaceManager.videoPlayerUnmute()
                videoPlayerMuted = false
                sound_button.background = getDrawable(R.drawable.ic_volume_up_black_24dp)
                label_sound.text = getString(R.string.label_control_sound_on)
            } else {
                backgroundSurfaceManager.videoPlayerMute()
                videoPlayerMuted = true
                sound_button.background = getDrawable(R.drawable.ic_volume_mute_black_24dp)
                label_sound.text = getString(R.string.label_control_sound_off)
            }
        }

        text_add_button_group.setOnClickListener {
            addNewText()
        }

        stickers_button_group.setOnClickListener {
            emojiPickerFragment.show(supportFragmentManager, emojiPickerFragment.getTag())
        }

        save_button.setOnClickListener {
            saveLoopFrame()
        }
    }

    private fun deleteCapturedMedia() {
        currentOriginalCapturedFile?.let {
            val apkURI = FileProvider.getUriForFile(
                this,
                applicationContext.packageName + ".provider", it)
            contentResolver.delete(apkURI, null, null)
        }
        // reset
        currentOriginalCapturedFile = null
    }

    private fun testBrush() {
        photoEditor.setBrushDrawingMode(true)
        photoEditor.brushColor = ContextCompat.getColor(baseContext, color.red)
    }

    private fun testEraser() {
        photoEditor.setBrushDrawingMode(false)
        photoEditor.brushEraser()
    }

    private fun addNewText() {
        photoEditor.addText(
            "",
            colorCodeTextView = ContextCompat.getColor(baseContext, color.text_color_white), fontSizeSp = 24f)
    }

    private fun testEmoji() {
        val emojisList = PhotoEditor.getEmojis(this)
        // get some random emoji
        val randomEmojiPos = (0..emojisList.size).shuffled().first()
        photoEditor.addEmoji(emojisList.get(randomEmojiPos))
    }

    private fun testSticker() {
        photoEditor.addNewImageView(true, Uri.parse("https://i.giphy.com/Ok4HaWlYrewuY.gif"))
    }

    private fun launchCameraPreview() {
        if (!PermissionUtils.checkPermission(this, Manifest.permission.RECORD_AUDIO) ||
            !PermissionUtils.checkPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ||
            !PermissionUtils.checkPermission(this, Manifest.permission.CAMERA)) {
            val permissions = arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.CAMERA
            )
            PermissionUtils.requestPermissions(this, permissions)
            return
        }

        hideEditModeUIControls()

        // set the correct camera as selected by the user last time they used the app
        backgroundSurfaceManager.selectCamera(cameraSelection)
        // same goes for flash state
        backgroundSurfaceManager.setFlashState(flashModeSelection)
        backgroundSurfaceManager.switchCameraPreviewOn()
    }

    private fun showPlayVideo(videoFile: File? = null) {
        showEditModeUIControls(false)
        backgroundSurfaceManager.switchVideoPlayerOn(videoFile)
    }

    private fun showStaticBackground() {
        showEditModeUIControls(true)
        backgroundSurfaceManager.switchStaticImageBackgroundModeOn()
    }

    private fun takeStillPicture() {
        camera_capture_button.startProgressingAnimation(CAMERA_STILL_PICTURE_ANIM_MS)
        backgroundSurfaceManager.takePicture(object : ImageCaptureListener {
            override fun onImageSaved(file: File) {
                runOnUiThread {
                    Glide.with(this@ComposeLoopFrameActivity)
                        .load(file)
                        .transform(CenterCrop(), RoundedCorners(16))
                        .into(gallery_upload_img)
                    Glide.with(this@ComposeLoopFrameActivity)
                        .load(file)
                        .into(photoEditorView.source)
                    showStaticBackground()
                    currentOriginalCapturedFile = file
                }

                showToast("IMAGE SAVED")
            }
            override fun onError(message: String, cause: Throwable?) {
                // TODO implement error handling
                showToast("ERROR SAVING IMAGE")
            }
        })
    }

    private fun startRecordingVideoAfterVibrationIndication() {
        if (!backgroundSurfaceManager.cameraRecording()) {
            timesUpHandler.postDelayed({
                startRecordingVideo()
            }, VIBRATION_INDICATION_LENGTH_MS)
            hideVideoUIControls()
            showToast("VIDEO STARTED")
            vibrate()
        }
    }

    private fun startRecordingVideo() {
        if (!backgroundSurfaceManager.cameraRecording()) {
            // force stop recording video after maximum time limit reached
            timesUpHandler.postDelayed(timesUpRunnable, CAMERA_VIDEO_RECORD_MAX_LENGTH_MS)
            // strat progressing animation
            camera_capture_button.startProgressingAnimation(CAMERA_VIDEO_RECORD_MAX_LENGTH_MS)
            backgroundSurfaceManager.startRecordingVideo(object : VideoRecorderFinished {
                override fun onVideoSaved(file: File?) {
                    currentOriginalCapturedFile = file
                    runOnUiThread {
                        // now start playing the video we just recorded
                        showPlayVideo()
                    }
                }

                override fun onError(message: String?, cause: Throwable?) {
                    // TODO implement error handling
                    runOnUiThread {
                        showToast("Video could not be saved: " + message)
                    }
                }
            })
        }
    }

    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        // Vibrate for 100 milliseconds
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(
                VIBRATION_INDICATION_LENGTH_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            // deprecated in API 26
            vibrator.vibrate(VIBRATION_INDICATION_LENGTH_MS)
        }
    }

    private fun stopRecordingVideo(isCanceled: Boolean) {
        if (backgroundSurfaceManager.cameraRecording()) {
            camera_capture_button.stopProgressingAnimation()
            camera_capture_button.clearAnimation()
            camera_capture_button
                .animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .duration = PressAndHoldGestureHelper.CLICK_LENGTH / 4
            backgroundSurfaceManager.stopRecordingVideo()
            showVideoUIControls()
            if (isCanceled) {
                // remove any pending callback if video was cancelled
                timesUpHandler.removeCallbacksAndMessages(null)
                showToast("GESTURE CANCELLED, VIDEO SAVED")
            } else {
                showToast("VIDEO SAVED")
            }
        }
    }

    // this one saves one composed unit: ether an Image or a Video
    private fun saveLoopFrame() {
        // check wether we have an Image or a Video, and call its save functionality accordingly
        if (backgroundSurfaceManager.cameraVisible() || backgroundSurfaceManager.videoPlayerVisible()) {
            saveVideo(backgroundSurfaceManager.getCurrentFile().toString())
        } else {
            // check whether there are any GIF stickers - if there are, we need to produce a video instead
            if (photoEditor.anyStickersAdded()) {
                saveVideoWithStaticBackground()
            } else {
                saveImage()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun saveImage() {
        if (PermissionUtils.checkAndRequestPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            showLoading("Saving...")
            val file = getLoopFrameFile(this, false)
            try {
                file.createNewFile()

                val saveSettings = SaveSettings.Builder()
                    .setClearViewsEnabled(true)
                    .setTransparencyEnabled(true)
                    .build()

                photoEditor.saveAsFile(file.absolutePath, saveSettings, object : PhotoEditor.OnSaveListener {
                    override fun onSuccess(imagePath: String) {
                        hideLoading()
                        deleteCapturedMedia()
                        sendNewLoopReadyBroadcast(file)
                        showSnackbar(
                            getString(R.string.label_snackbar_loop_saved),
                            getString(R.string.label_snackbar_share),
                            object : OnClickListener {
                                override fun onClick(p0: View?) {
                                    shareAction(file)
                                }
                            }
                        )
                        hideEditModeUIControls()
                        backgroundSurfaceManager.switchCameraPreviewOn()
                    }

                    override fun onFailure(exception: Exception) {
                        hideLoading()
                        showSnackbar("Failed to save Image")
                    }
                })
            } catch (e: IOException) {
                e.printStackTrace()
                hideLoading()
                e.message?.takeIf { it.isNotEmpty() }?.let { showSnackbar(it) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun saveVideo(inputFile: String) {
        if (PermissionUtils.checkPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            showLoading("Saving...")
            try {
                val file = getLoopFrameFile(this, true)
                file.createNewFile()

                val saveSettings = SaveSettings.Builder()
                    .setClearViewsEnabled(true)
                    .setTransparencyEnabled(true)
                    .build()

                photoEditor.saveVideoAsFile(
                    inputFile,
                    file.absolutePath,
                    saveSettings,
                    object : PhotoEditor.OnSaveWithCancelListener {
                    override fun onCancel(noAddedViews: Boolean) {
                            runOnUiThread {
                                hideLoading()
                                showSnackbar("No views added - original video saved")
                            }
                        }

                        override fun onSuccess(imagePath: String) {
                            runOnUiThread {
                                hideLoading()
                                deleteCapturedMedia()
                                photoEditor.clearAllViews()
                                sendNewLoopReadyBroadcast(file)
                                showSnackbar(
                                    getString(R.string.label_snackbar_loop_saved),
                                    getString(R.string.label_snackbar_share),
                                    object : OnClickListener {
                                        override fun onClick(p0: View?) {
                                            shareAction(file)
                                        }
                                    }
                                )
                                hideEditModeUIControls()
                                backgroundSurfaceManager.switchCameraPreviewOn()
                            }
                        }

                        override fun onFailure(exception: Exception) {
                            runOnUiThread {
                                hideLoading()
                                showSnackbar("Failed to save Video")
                            }
                        }
                    })
            } catch (e: IOException) {
                e.printStackTrace()
                hideLoading()
                e.message?.takeIf { it.isNotEmpty() }?.let { showSnackbar(it) }
            }
        } else {
            showSnackbar("Please allow WRITE TO STORAGE permissions")
        }
    }

    @SuppressLint("MissingPermission")
    private fun saveVideoWithStaticBackground() {
        if (PermissionUtils.checkPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            showLoading("Saving...")
            try {
                val file = getLoopFrameFile(this, true, "tmp")
                file.createNewFile()

                val saveSettings = SaveSettings.Builder()
                    .setClearViewsEnabled(true)
                    .setTransparencyEnabled(true)
                    .build()

                photoEditor.saveVideoFromStaticBackgroundAsFile(
                    file.absolutePath,
                    saveSettings,
                    object : PhotoEditor.OnSaveWithCancelListener {
                        override fun onCancel(noAddedViews: Boolean) {
                            // TODO not implemented
                        }

                        override fun onSuccess(imagePath: String) {
                            // now save the video with emoji, but using the previously saved video as input
                            hideLoading()
                            saveVideo(imagePath)
                            // TODO: delete the temporal video produced originally
                        }

                        override fun onFailure(exception: Exception) {
                            hideLoading()
                            showSnackbar("Failed to save Video")
                        }
                    })
            } catch (e: IOException) {
                e.printStackTrace()
                hideLoading()
                e.message?.takeIf { it.isNotEmpty() }?.let { showSnackbar(it) }
            }
        } else {
            showSnackbar("Please allow WRITE TO STORAGE permissions")
        }
    }

    protected fun showLoading(message: String) {
        editModeHideAllUIControls()
        save_button.setSaving(true)
        blockTouchOnPhotoEditor()
    }

    protected fun hideLoading() {
        editModeRestoreAllUIControls(false)
        save_button.setSaving(false)
        releaseTouchOnPhotoEditor()
    }

    protected fun showSnackbar(message: String, actionLabel: String? = null, listener: OnClickListener? = null) {
        runOnUiThread {
            val view = findViewById<View>(android.R.id.content)
            if (view != null) {
                val snackbar = Snackbar.make(view, message, Snackbar.LENGTH_LONG)
                snackbar.config(this)
                actionLabel?.let {
                    snackbar.setAction(it, listener)
                }
                snackbar.show()
            } else {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showToast(message: String) {
        val toast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
        toast.setGravity(Gravity.TOP, 0, 0)
        toast.show()
    }

    private fun hideVideoUIControls() {
        camera_flash_button.visibility = View.INVISIBLE
        label_flash.visibility = View.INVISIBLE

        camera_flip_button.visibility = View.INVISIBLE
        label_flip.visibility = View.INVISIBLE

        gallery_upload_img.visibility = View.INVISIBLE
        gallery_upload.visibility = View.INVISIBLE
    }

    private fun showVideoUIControls() {
        camera_flash_button.visibility = View.VISIBLE
        label_flash.visibility = View.VISIBLE

        camera_flip_button.visibility = View.VISIBLE
        label_flip.visibility = View.VISIBLE

        gallery_upload_img.visibility = View.VISIBLE
        gallery_upload.visibility = View.VISIBLE
    }

    private fun showEditModeUIControls(noSound: Boolean) {
        // hide capturing mode controls
        hideVideoUIControls()
        camera_capture_button.visibility = View.INVISIBLE

        // show proper edit mode controls
        close_button.visibility = View.VISIBLE
        edit_mode_controls.visibility = View.VISIBLE
        if (photoEditor.anyViewsAdded()) {
            // only show save button if any views have been added
            save_button.visibility = View.VISIBLE
        }

        if (noSound) {
            sound_button_group.visibility = View.INVISIBLE
        } else {
            sound_button_group.visibility = View.VISIBLE
        }
    }

    private fun hideEditModeUIControls() {
        // show capturing mode controls
        showVideoUIControls()
        camera_capture_button.visibility = View.VISIBLE

        // hide proper edit mode controls
        close_button.visibility = View.INVISIBLE
        edit_mode_controls.visibility = View.INVISIBLE
        save_button.visibility = View.INVISIBLE
    }

    private fun editModeHideAllUIControls() {
        // momentarily hide proper edit mode controls
        close_button.visibility = View.INVISIBLE
        edit_mode_controls.visibility = View.INVISIBLE
        sound_button_group.visibility = View.INVISIBLE
    }

    private fun editModeRestoreAllUIControls(noSound: Boolean) {
        // momentarily hide proper edit mode controls
        close_button.visibility = View.VISIBLE
        edit_mode_controls.visibility = View.VISIBLE

        if (noSound) {
            sound_button_group.visibility = View.INVISIBLE
        } else {
            sound_button_group.visibility = View.VISIBLE
        }
    }

    private fun updateFlashModeSelectionIcon() {
        when (flashModeSelection) {
            FlashIndicatorState.AUTO ->
                camera_flash_button.background = getDrawable(R.drawable.ic_flash_auto_black_24dp)
            FlashIndicatorState.ON ->
                camera_flash_button.background = getDrawable(R.drawable.ic_flash_on_black_24dp)
            FlashIndicatorState.OFF ->
                camera_flash_button.background = getDrawable(R.drawable.ic_flash_off_black_24dp)
        }
    }

    private fun saveCameraSelectionPref() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putInt(getString(string.pref_camera_selection), cameraSelection.id)
            commit()
        }
    }

    private fun saveFlashModeSelectionPref() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putInt(getString(string.pref_flash_mode_selection), flashModeSelection.id)
            commit()
        }
    }

    private fun shareAction(mediaFile: File) {
        val apkURI = FileProvider.getUriForFile(
            this,
            applicationContext.packageName + ".provider", mediaFile)
        val shareIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            setDataAndType(apkURI, "image/jpeg")
            putExtra(Intent.EXTRA_STREAM, apkURI)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, resources.getText(R.string.label_share_to)))
    }

    private fun sendNewLoopReadyBroadcast(mediaFile: File) {
        // Implicit broadcasts will be ignored for devices running API
        // level >= 24, so if you only target 24+ you can remove this statement
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            if (mediaFile.extension.startsWith("jpg")) {
                sendBroadcast(Intent(Camera.ACTION_NEW_PICTURE, Uri.fromFile(mediaFile)))
            } else {
                sendBroadcast(Intent(Camera.ACTION_NEW_VIDEO, Uri.fromFile(mediaFile)))
            }
        }

        // If the folder selected is an external media directory, this is unnecessary
        // but otherwise other apps will not be able to access our images unless we
        // scan them using [MediaScannerConnection]
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(mediaFile.extension)
        MediaScannerConnection.scanFile(
            this, arrayOf(mediaFile.absolutePath), arrayOf(mimeType), null)
    }

    private fun blockTouchOnPhotoEditor() {
        translucent_view.visibility = View.VISIBLE
        translucent_view.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(p0: View?, p1: MotionEvent?): Boolean {
                // no op
                return true
            }
        })
    }

    private fun releaseTouchOnPhotoEditor() {
        translucent_view.visibility = View.GONE
        translucent_view.setOnTouchListener(null)
    }

    companion object {
        private const val SURFACE_MANAGER_READY_LAUNCH_DELAY = 500L
        private const val CAMERA_VIDEO_RECORD_MAX_LENGTH_MS = 10000L
        private const val CAMERA_STILL_PICTURE_ANIM_MS = 300L
        private const val STATE_KEY_CURRENT_ORIGINAL_CAPTURED_FILE = "key_current_original_captured_file"
        private const val VIBRATION_INDICATION_LENGTH_MS = 100L
    }
}
