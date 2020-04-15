package com.automattic.portkey.compose.frame

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Camera
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcelable
import android.util.Log
import android.webkit.MimeTypeMap
import com.automattic.portkey.R
import com.automattic.portkey.compose.frame.FrameSaveManager.FrameSaveProgressListener
import com.automattic.photoeditor.PhotoEditor
import com.automattic.portkey.compose.frame.FrameSaveService.SaveResultReason.SaveError
import com.automattic.portkey.compose.frame.FrameSaveService.SaveResultReason.SaveSuccess
import com.automattic.portkey.compose.story.StoryFrameItem
import com.automattic.portkey.compose.story.StoryIndex
import com.automattic.portkey.compose.story.StoryRepository
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import org.greenrobot.eventbus.EventBus
import java.io.Serializable

class FrameSaveService : Service() {
    private val binder = FrameSaveServiceBinder()
    private lateinit var frameSaveNotifier: FrameSaveNotifier
    private val storySaveProcessors = ArrayList<StorySaveProcessor>()

    override fun onCreate() {
        super.onCreate()
        frameSaveNotifier = FrameSaveNotifier(applicationContext, this)
        Log.d("FrameSaveService", "onCreate()")
    }

    override fun onBind(p0: Intent?): IBinder? {
        Log.d("FrameSaveService", "onBind()")
        return binder
    }

    // we won't really use intents to start the Service but we need it to be a started Service so we can make it
    // a foreground Service as well. Hence, here we override onStartCommand() as well.
    // So basically we're using a bound Service to be able to pass the FrameSaveManager instance to it, which in turn
    // has an instance of PhotoEditor, which is needed to save each frame.
    // And, we're making it a started Service so we can also make it a foreground Service (bound services alone
    // can't be foreground services, and serializing a FrameSaveManager or a PhotoEditor instance seems way too
    // for something that will need to live on the screen anyway, at least for the time being).
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("FrameSaveService", "onStartCommand()")
        // Skip this request if no items to upload were given
        if (intent == null) { // || !intent.hasExtra(KEY_MEDIA_LIST) && !intent.hasExtra(KEY_LOCAL_POST_ID)) {
            // AppLog.e(T.MAIN, "UploadService > Killed and restarted with an empty intent")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    fun saveStoryFrames(
        storyIndex: Int,
        photoEditor: PhotoEditor,
        frameIndex: FrameIndex = StoryRepository.DEFAULT_NONE_SELECTED
    ) {
        CoroutineScope(Dispatchers.Default).launch {
            EventBus.getDefault().postSticky(StorySaveProcessStart(storyIndex))

            lateinit var storyFrames: List<StoryFrameItem>
            if (frameIndex > StoryRepository.DEFAULT_NONE_SELECTED) {
                storyFrames = listOf(StoryRepository.getStoryAtIndex(storyIndex).frames[frameIndex])
            } else {
                storyFrames = StoryRepository.getImmutableCurrentStoryFrames()
                // when saving a full story, first freeze the Story in the Repository
                StoryRepository.finishCurrentStory()
            }

            // now create a processor and run it.
            // also hold a reference to it in the storySaveProcessors list in case the Service is destroyed, so
            // we can cancel each coroutine.
            val processor = createProcessor(storyIndex, photoEditor)
            storySaveProcessors.add(processor)
            runProcessor(
                processor,
                storyIndex,
                storyFrames
            )
            // remove the processor from the list once it's done processing this Story's frames
            storySaveProcessors.remove(processor)

            // also if more than one processor is running, let's not stop the Service just now.
            if (storySaveProcessors.isEmpty()) {
                stopSelf()
            }
        }
    }

    private suspend fun runProcessor(
        processor: StorySaveProcessor,
        storyIndex: Int,
        storyFrames: List<StoryFrameItem>
    ) {
        processor.attachProgressListener()
        saveStoryFramesAndDispatchNewFileBroadcast(processor, storyIndex, storyFrames)
        processor.detachProgressListener()
    }

    private fun createProcessor(storyIndex: Int, photoEditor: PhotoEditor): StorySaveProcessor {
        return StorySaveProcessor(
            this,
            storyIndex,
            frameSaveNotifier,
            FrameSaveManager(photoEditor)
        )
    }

    private suspend fun saveStoryFramesAndDispatchNewFileBroadcast(
        storySaveProcessor: StorySaveProcessor,
        storyIndex: Int,
        frames: List<StoryFrameItem>
    ) {
        val frameFileList =
            storySaveProcessor.saveStory(
                this,
                frames
            )

        // once all frames have been saved, issue a broadcast so the system knows these frames are ready
        sendNewMediaReadyBroadcast(frameFileList)

        // if we got the same amount of output files it means all went good, otherwise there were errors
        prepareStorySaveResult(storySaveProcessor, storyIndex, frames.size == frameFileList.size)
    }

    private fun prepareStorySaveResult(
        storySaveProcessor: StorySaveProcessor,
        storyIndex: Int,
        noErrors: Boolean
    ) {
        storySaveProcessor.storySaveResult.storyIndex = storyIndex
        if (!noErrors) {
            // let's handle these errors
            handleErrors(storySaveProcessor.storySaveResult)
        }

        // update the Repository with latest save statuses
        StoryRepository.setCurrentStorySaveResultsOnFrames(storyIndex, storySaveProcessor.storySaveResult)

        // errors have been collected, post the SaveResult for the whole Story
        EventBus.getDefault().postSticky(storySaveProcessor.storySaveResult)
    }

    private fun handleErrors(storyResult: StorySaveResult) {
        val storyTitle = StoryRepository.getStoryAtIndex(storyResult.storyIndex).title
        frameSaveNotifier.updateNotificationErrorForStoryFramesSave(storyTitle, storyResult)
    }

    private fun sendNewMediaReadyBroadcast(mediaFileList: List<File>) {
        // Implicit broadcasts will be ignored for devices running API
        // level >= 24, so if you only target 24+ you can remove this statement
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            @Suppress("DEPRECATION")
            for (mediaFile in mediaFileList) {
                if (mediaFile.extension == "jpg") {
                    sendBroadcast(Intent(Camera.ACTION_NEW_PICTURE, Uri.fromFile(mediaFile)))
                } else {
                    sendBroadcast(Intent(Camera.ACTION_NEW_VIDEO, Uri.fromFile(mediaFile)))
                }
            }
        }

        val arrayOfmimeTypes = arrayOfNulls<String>(mediaFileList.size)
        val arrayOfPaths = arrayOfNulls<String>(mediaFileList.size)
        for ((index, mediaFile) in mediaFileList.withIndex()) {
            arrayOfmimeTypes[index] = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(mediaFile.extension)
            arrayOfPaths[index] = mediaFile.absolutePath
        }

        // If the folder selected is an external media directory, this is unnecessary
        // but otherwise other apps will not be able to access our images unless we
        // scan them using [MediaScannerConnection]
        MediaScannerConnection.scanFile(
            applicationContext, arrayOfPaths, arrayOfmimeTypes, null)
    }

    override fun onDestroy() {
        Log.d("FrameSaveService", "onDestroy()")
        for (processor in storySaveProcessors) {
            processor.onCancel()
        }
        super.onDestroy()
    }

    inner class FrameSaveServiceBinder : Binder() {
        fun getService(): FrameSaveService = this@FrameSaveService
    }

    @Parcelize
    data class StorySaveResult(
        var storyIndex: StoryIndex = 0,
        val frameSaveResult: MutableList<FrameSaveResult> = mutableListOf()
    ) : Parcelable {
        fun isSuccess(): Boolean {
            return frameSaveResult.all { it.resultReason == SaveSuccess }
        }
    }
    @Parcelize
    data class FrameSaveResult(val frameIndex: FrameIndex, val resultReason: SaveResultReason) : Parcelable

    sealed class SaveResultReason : Parcelable {
        @Parcelize
        object SaveSuccess : SaveResultReason()

        @Parcelize
        data class SaveError(
            var reason: String? = null
        ) : SaveResultReason()
    }

    data class StorySaveProcessStart(
        var storyIndex: StoryIndex
    ) : Serializable

    class StorySaveProcessor(
        private val context: Context,
        private val storyIndex: Int,
        private val frameSaveNotifier: FrameSaveNotifier,
        private val frameSaveManager: FrameSaveManager
    ) : FrameSaveProgressListener {
        val storySaveResult = StorySaveResult()

        // FrameSaveProgressListener overrides
        override fun onFrameSaveStart(frameIndex: FrameIndex) {
            Log.d("PORTKEY", "START save frame idx: " + frameIndex)
            frameSaveNotifier.addStoryPageInfoToForegroundNotification(
                frameIndex.toString(),
                StoryRepository.getStoryAtIndex(storyIndex).title ?: context.getString(R.string.story_saving_untitled)
            )
        }

        override fun onFrameSaveProgress(frameIndex: FrameIndex, progress: Double) {
            Log.d("PORTKEY", "PROGRESS save frame idx: " + frameIndex + " %: " + progress)
            frameSaveNotifier.updateNotificationProgressForMedia(frameIndex.toString(), progress.toFloat())
        }

        override fun onFrameSaveCompleted(frameIndex: FrameIndex) {
            Log.d("PORTKEY", "END save frame idx: " + frameIndex)
            frameSaveNotifier.incrementUploadedMediaCountFromProgressNotification(frameIndex.toString(), true)
            // add success data to StorySaveResult
            storySaveResult.frameSaveResult.add(FrameSaveResult(frameIndex, SaveSuccess))
        }

        override fun onFrameSaveCanceled(frameIndex: FrameIndex) {
            // remove one from the count
            frameSaveNotifier.incrementUploadedMediaCountFromProgressNotification(frameIndex.toString())
            // add error data to StorySaveResult
            storySaveResult.frameSaveResult.add(FrameSaveResult(frameIndex, SaveError(REASON_CANCELLED)))
        }

        override fun onFrameSaveFailed(frameIndex: FrameIndex, reason: String?) {
            // remove one from the count
            frameSaveNotifier.incrementUploadedMediaCountFromProgressNotification(frameIndex.toString())
            // add error data to StorySaveResult
            storySaveResult.frameSaveResult.add(FrameSaveResult(frameIndex, SaveError(reason)))
        }

        fun attachProgressListener() {
            frameSaveManager.saveProgressListener = this
        }

        fun detachProgressListener() {
            frameSaveManager.saveProgressListener = null
        }

        suspend fun saveStory(
            context: Context,
            frames: List<StoryFrameItem>
        ): List<File> {
            return frameSaveManager.saveStory(context, frames)
        }

        fun onCancel() {
            frameSaveManager.onCancel()
        }
    }

    companion object {
        private const val REASON_CANCELLED = "cancelled"
        fun startServiceAndGetSaveStoryIntent(context: Context): Intent {
            Log.d("FrameSaveService", "startServiceAndGetSaveStoryIntent()")
            val intent = Intent(context, FrameSaveService::class.java)
            context.startService(intent)
            return intent
        }
    }
}
