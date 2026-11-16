package com.gndec.timetable.domain

import android.content.Context
import com.gndec.timetable.net.LostFoundClient
import com.gndec.timetable.net.LostFoundItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** UI-facing state of the Lost & Found screen. */
sealed interface LostFoundState {
    data object Loading : LostFoundState

    data class Ready(
        val items: List<LostFoundItem>,
        val refreshing: Boolean,
        val error: String? = null
    ) : LostFoundState

    data class Error(val message: String) : LostFoundState
}

/**
 * Campus Lost & Found noticeboard backed by the project's Supabase
 * (see [LostFoundClient] for the security and bandwidth policy).
 *
 * Posting flow: compress both variants (see [ImageCompressor]) -> upload
 * the thumbnail -> upload the full image -> insert the row through the
 * create RPC with the poster's contact info and delete PIN. The row comes
 * last because anonymous rows can never be updated; if the insert fails the
 * two stored objects are simply orphaned (invisible, harmless) rather than
 * a row pointing at images that don't exist.
 *
 * Deleting: the delete RPC verifies the PIN the poster chose when creating
 * the post — "item collected" / "founder returned it" — removes the row and
 * then cleans up the two image objects.
 */
class LostFoundManager(
    private val context: Context,
    private val client: LostFoundClient = LostFoundClient()
) {
    private val refreshMutex = Mutex()
    private val postMutex = Mutex()

    private val _state = MutableStateFlow<LostFoundState>(LostFoundState.Loading)
    val state: StateFlow<LostFoundState> = _state.asStateFlow()

    /** True while a new post is being compressed + uploaded + inserted. */
    private val _posting = MutableStateFlow(false)
    val posting: StateFlow<Boolean> = _posting.asStateFlow()

    /** Last failed post, for the composer to pre-fill and let the student retry. */
    private val _lastPostError = MutableStateFlow<String?>(null)
    val lastPostError: StateFlow<String?> = _lastPostError.asStateFlow()

    private var loaded = false
    private var items: List<LostFoundItem> = emptyList()

    suspend fun ensureLoaded(forceRefresh: Boolean = false) {
        if (!loaded) {
            loaded = true
            refresh(force = forceRefresh)
        } else if (forceRefresh) {
            refresh(force = true)
        }
    }

    suspend fun refresh(force: Boolean = true) {
        refreshMutex.withLock {
            if (items.isNotEmpty()) publish(refreshing = true)
            try {
                val fetched = client.list()
                items = fetched
                publish(refreshing = false)
            } catch (e: Exception) {
                if (items.isEmpty()) {
                    _state.value = LostFoundState.Error(e.message ?: "Couldn’t load Lost & Found")
                } else {
                    publish(refreshing = false, error = e.message ?: "Refresh failed")
                }
            }
        }
    }

    /**
     * Publishes one post. [imageUri] is the content URI of the picked photo
     * (optional — text-only posts are allowed), [contact] is how other
     * students reach the poster and [pin] is the 4–8 digit PIN that later
     * unlocks deletion. Returns the created item or throws with a
     * student-readable message.
     */
    suspend fun post(
        kind: String,
        title: String,
        description: String,
        contact: String,
        pin: String,
        imageUri: String?
    ): LostFoundItem = postMutex.withLock {
        require(kind == LostFoundItem.KIND_LOST || kind == LostFoundItem.KIND_FOUND) {
            "kind must be lost or found"
        }
        require(title.isNotBlank()) { "A title is required" }
        require(contact.isNotBlank()) { "Add contact info so the other student can reach you" }
        require(LostFoundClient.isValidDeletePin(pin)) { "The delete PIN must be 4–8 digits" }
        _posting.value = true
        _lastPostError.value = null
        try {
            val image = imageUri?.let { withContext(Dispatchers.Default) { ImageCompressor.compress(context, it) } }

            val imagePath: String? = image?.let {
                val id = UUID.randomUUID().toString()
                val path = "$kind/$id.jpg"
                withContext(Dispatchers.IO) {
                    client.uploadImage(LostFoundClient.thumbPath(path), it.thumb)
                    client.uploadImage(path, it.full)
                }
                path
            }

            val created = client.insert(
                kind = kind,
                title = title.trim().take(120),
                description = description.trim().take(1000),
                contact = LostFoundClient.sanitizeContact(contact),
                pin = pin,
                imagePath = imagePath
            )

            // Optimistic list update: the new post is instantly visible
            // without a re-fetch of the whole feed.
            items = listOf(created) + items
            publish(refreshing = false)
            created
        } catch (e: Exception) {
            _lastPostError.value = e.message ?: "Posting failed"
            throw e
        } finally {
            _posting.value = false
        }
    }

    /**
     * Deletes one post with the poster's PIN. Returns true when the post is
     * gone (and the list no longer shows it); false when the PIN doesn't
     * match. Network-level failures still throw, like [refresh] does.
     */
    suspend fun delete(item: LostFoundItem, pin: String): Boolean = postMutex.withLock {
        if (!LostFoundClient.isValidDeletePin(pin)) return@withLock false
        val deleted = client.deletePost(item.id, pin, item.image_path)
        if (deleted) {
            items = items.filterNot { it.id == item.id }
            publish(refreshing = false)
        }
        deleted
    }

    private fun publish(refreshing: Boolean, error: String? = null) {
        if (items.isEmpty() && error == null) {
            _state.value = LostFoundState.Ready(items = emptyList(), refreshing = refreshing)
            return
        }
        _state.value = LostFoundState.Ready(items = items, refreshing = refreshing, error = error)
    }

    companion object {
        /** "2 h ago" / "yesterday" / "3 d ago" for a post's created_at. */
        fun relativeTime(createdAtIso: String, nowMillis: Long = System.currentTimeMillis()): String =
            LostFoundRelativeTime.relativeTime(createdAtIso, nowMillis)
    }
}
