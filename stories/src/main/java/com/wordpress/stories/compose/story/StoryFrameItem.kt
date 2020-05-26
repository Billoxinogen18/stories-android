package com.wordpress.stories.compose.story

import android.net.Uri
import com.automattic.photoeditor.views.added.AddedViewList
import com.wordpress.stories.compose.frame.StorySaveEvents.SaveResultReason
import com.wordpress.stories.compose.frame.StorySaveEvents.SaveResultReason.SaveSuccess
import com.wordpress.stories.compose.story.StoryFrameItemType.IMAGE
import java.io.File

data class StoryFrameItem(
    val source: BackgroundSource,
    val frameItemType: StoryFrameItemType = IMAGE,
    var addedViews: AddedViewList = AddedViewList(),
    var saveResultReason: SaveResultReason = SaveSuccess,
    var composedFrameFile: File? = null
) {
    sealed class BackgroundSource {
        data class UriBackgroundSource(var contentUri: Uri? = null) : BackgroundSource()
        data class FileBackgroundSource(var file: File? = null) : BackgroundSource()
    }
}
