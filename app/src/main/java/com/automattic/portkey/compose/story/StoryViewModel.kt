package com.automattic.portkey.compose.story

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.automattic.portkey.compose.story.StoryViewModel.StoryFrameListItemUiState.StoryFrameListItemUiStateFrame
import com.automattic.portkey.compose.story.StoryViewModel.StoryFrameListItemUiState.StoryFrameListItemUiStatePlusIcon

class StoryViewModel(val repository: StoryRepository, val storyIndex: Int) : ViewModel() {
    private var currentSelectedFrameIndex: Int = DEFAULT_SELECTION

    private val _uiState: MutableLiveData<StoryFrameListUiState> = MutableLiveData()
    val uiState: LiveData<StoryFrameListUiState> = _uiState

    private val _onSelectedFrameIndex: MutableLiveData<Pair<Int, Int>> by lazy {
        MutableLiveData<Pair<Int, Int>>().also {
            it.value = Pair(DEFAULT_SELECTION, currentSelectedFrameIndex)
        }
    }
    val onSelectedFrameIndex: LiveData<Pair<Int, Int>> = _onSelectedFrameIndex

    fun loadStory(storyIndex: Int) {
        repository.loadStory(storyIndex)
        updateUiState(createUiStateFromModelState(repository.getImmutableCurrentStoryFrames()))
        // default selected frame when loading a new Story
        _onSelectedFrameIndex.value = Pair(DEFAULT_SELECTION, DEFAULT_SELECTION)
    }

    fun addStoryFrameItemToCurrentStory(item: StoryFrameItem) {
        repository.addStoryFrameItemToCurrentStory(item)
        updateUiState(createUiStateFromModelState(repository.getImmutableCurrentStoryFrames()))
    }

    // when the user finishes a story, just add it to our repo for now and clear currentStory
    fun finishCurrentStory() {
        repository.finishCurrentStory()
        updateUiState(createUiStateFromModelState(repository.getImmutableCurrentStoryFrames()))
        currentSelectedFrameIndex = DEFAULT_SELECTION // default selected frame when loading a new Story
        _onSelectedFrameIndex.value = Pair(DEFAULT_SELECTION, currentSelectedFrameIndex)
    }

    fun discardCurrentStory() {
        repository.discardCurrentStory()
        currentSelectedFrameIndex = DEFAULT_SELECTION // default selected frame when loading a new Story
        _onSelectedFrameIndex.value = Pair(DEFAULT_SELECTION, currentSelectedFrameIndex)
        updateUiState(createUiStateFromModelState(repository.getImmutableCurrentStoryFrames()))
    }

    // used when user taps on a different frame and keep track record of its state
    fun setSelectedFrame(index: Int): StoryFrameItem {
        val newlySelectedFrame = repository.getImmutableCurrentStoryFrames()[index]
        val oldIndex = currentSelectedFrameIndex
        currentSelectedFrameIndex = index
        updateUiStateForSelection(oldIndex, index)
        return newlySelectedFrame
    }

    fun getSelectedFrameIndex(): Int {
        return currentSelectedFrameIndex
    }

    private fun updateUiState(uiState: StoryFrameListUiState) {
        _uiState.value = uiState
    }

    private fun updateUiStateForSelection(oldSelectedIndex: Int, newSelectedIndex: Int) {
        val immutableStory: StoryFrameListUiState? = _uiState.value
        immutableStory?.let {
            if (it.items[oldSelectedIndex + 1] is StoryFrameListItemUiStateFrame) {
                (it.items[oldSelectedIndex + 1] as StoryFrameListItemUiStateFrame).selected = false
            }
            if (it.items[newSelectedIndex + 1] is StoryFrameListItemUiStateFrame) {
                (it.items[newSelectedIndex + 1] as StoryFrameListItemUiStateFrame).selected = true
            }
            _onSelectedFrameIndex.value = Pair(oldSelectedIndex + 1, newSelectedIndex + 1)
        }
    }

    private fun createUiStateFromModelState(storyItems: List<StoryFrameItem>): StoryFrameListUiState {
        val uiStateItems = ArrayList<StoryFrameListItemUiState>()
        val newUiState = StoryFrameListUiState(uiStateItems)
        // add the plus icon to the UiState array
        uiStateItems.add(StoryFrameListItemUiStatePlusIcon)
        // TODO add the SingleLiveEvent click listener
        StoryFrameListItemUiStatePlusIcon.onItemTapped = {
            // setSelectedFrame(index)
            // TODO here trigger SingleLiveEvent
        }
        storyItems.forEachIndexed { index, model ->
            val isSelected = (getSelectedFrameIndex() == index)
            val oneFrameUiState = StoryFrameListItemUiStateFrame(selected = isSelected, filePath = model.filePath)
            oneFrameUiState.onItemTapped = {
                setSelectedFrame(index)
            }
            uiStateItems.add(oneFrameUiState)
        }
        return newUiState
    }

    data class StoryFrameListUiState(val items: List<StoryFrameListItemUiState>)

    sealed class StoryFrameListItemUiState() {
        var onItemTapped: (() -> Unit)? = null

        object StoryFrameListItemUiStatePlusIcon : StoryFrameListItemUiState()

        data class StoryFrameListItemUiStateFrame(
            var selected: Boolean = false,
            var filePath: String? = null
        ) : StoryFrameListItemUiState()
    }

    companion object {
        const val DEFAULT_SELECTION = 0
    }
}
