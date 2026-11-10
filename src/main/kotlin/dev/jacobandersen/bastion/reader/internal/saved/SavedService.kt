package dev.jacobandersen.bastion.reader.internal.saved

import com.github.slugify.Slugify
import dev.jacobandersen.bastion.microsub.entries.EntrySnapshotQueryService
import dev.jacobandersen.bastion.reader.internal.web.ReaderError
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class SavedPage(
    val items: List<SavedItem>,
    val after: String?,
)

data class SaveResult(
    val item: SavedItem,
    val created: Boolean,
)

/** What deleting a folder did to its items. */
data class FolderDeletion(
    val deleted: Int,
    val moved: Int,
)

/**
 * The owner's saved folders: private, frozen copies of entries that live in their own tables, apart from Microsub
 * channels and feeds. Nothing here reads or writes the microsub tables; a copy is taken once through
 * [EntrySnapshotQueryService], and no automatic process ever removes one. An item belongs to one folder and a folder
 * holds a given post (by canonical url) once. Every change to a folder's items bumps its `version`, which clients use to
 * sync. Methods are transactional so a change and its version bump commit together.
 */
@Service
class SavedService(
    private val folders: SavedFolderRepository,
    private val items: SavedItemRepository,
    private val snapshots: EntrySnapshotQueryService,
    private val slugify: Slugify,
) {
    @Transactional(readOnly = true)
    fun listFolders(): List<SavedFolderSummary> = folders.summaries()

    @Transactional
    fun createFolder(name: String?): SavedFolderSummary {
        val trimmed = requireName(name)
        val base = slugify.slugify(trimmed).take(MAX_UID).ifBlank { "folder" }
        var uid = base
        var suffix = 2
        while (folders.uidExists(uid)) uid = "$base-${suffix++}"
        val folder = folders.insert(uid, trimmed, folders.nextPosition(), now())
        return SavedFolderSummary(folder, 0, 0)
    }

    /** Renames and/or moves the folder to [position] (0 is first) among the others. Its uid never changes. */
    @Transactional
    fun updateFolder(
        uid: String,
        name: String?,
        position: Int?,
    ): SavedFolderSummary {
        val folder = folders.lockByUid(uid) ?: throw ReaderError.folderNotFound(uid)
        if (name != null) folders.rename(folder.id, requireName(name), now())
        if (position != null) {
            val order = folders.list().filter { it.id != folder.id }.toMutableList()
            order.add(position.coerceIn(0, order.size), folder)
            order.forEachIndexed { index, each -> if (each.position != index) folders.setPosition(each.id, index) }
        }
        return requireNotNull(folders.summary(folder.id))
    }

    /**
     * Deletes the folder. A folder with items needs [moveTo] (another folder takes them; ones it already holds are
     * dropped) or [expectedItems], the count the owner was shown, which must still be right: otherwise nothing is
     * deleted (409 `folder_changed`), so a folder never loses more than the owner was told.
     */
    @Transactional
    fun deleteFolder(
        uid: String,
        moveTo: String?,
        expectedItems: Int?,
    ): FolderDeletion {
        val folder = folders.lockByUid(uid) ?: throw ReaderError.folderNotFound(uid)
        val count = items.countInFolder(folder.id).toInt()
        if (moveTo != null) {
            if (moveTo == uid) throw ReaderError.invalidRequest("'move_to' must name another folder")
            val target = folders.lockByUid(moveTo) ?: throw ReaderError.folderNotFound(moveTo)
            val moved = items.moveAll(folder.id, target.id, now())
            if (moved > 0) folders.bumpVersion(listOf(target.id), now())
            folders.delete(folder.id)
            return FolderDeletion(deleted = count - moved, moved = moved)
        }
        if (expectedItems != null && expectedItems != count) throw ReaderError.folderChanged(count, expectedItems)
        if (count > 0 && expectedItems == null) {
            throw ReaderError.invalidRequest("Give 'move_to' or 'items' (the number of items to delete, now $count)")
        }
        folders.delete(folder.id)
        return FolderDeletion(deleted = count, moved = 0)
    }

    /**
     * Copies the entry into the folder. The copy is taken from the server's own record of the entry (a deleted entry
     * whose row is still kept works too) and is never refreshed: saving a post the folder already holds returns the
     * existing item unchanged.
     */
    @Transactional
    fun save(
        folderUid: String?,
        channelUid: String?,
        entryId: String?,
    ): SaveResult {
        if (folderUid.isNullOrBlank()) throw ReaderError.invalidRequest("'folder' is required")
        if (channelUid.isNullOrBlank()) throw ReaderError.invalidRequest("'channel' is required")
        if (entryId.isNullOrBlank()) throw ReaderError.invalidRequest("'entry' is required")
        val folder = folders.findByUid(folderUid) ?: throw ReaderError.folderNotFound(folderUid)
        val snapshot = snapshots.snapshot(channelUid, entryId) ?: throw ReaderError.entryNotFound(entryId)
        val originId = runCatching { UUID.fromString(entryId.trim()) }.getOrNull()
        val key = snapshot.url ?: "entry:${originId ?: entryId.trim()}"
        val now = now()
        val id =
            items.insertIfAbsent(
                NewSavedItem(
                    folderId = folder.id,
                    dedupeKey = key,
                    originChannelUid = channelUid,
                    originEntryId = originId,
                    originFeedUrl = snapshot.feedUrl,
                    url = snapshot.url,
                    authorUrl = snapshot.authorUrl,
                    published = snapshot.published,
                    categories = snapshot.categories,
                    kinds = snapshot.kinds,
                    data = snapshot.jf2 - "_id",
                    searchText = snapshot.searchText,
                ),
                now,
            )
        if (id == null) return SaveResult(requireNotNull(items.findInFolder(folder.id, key)), created = false)
        folders.bumpVersion(listOf(folder.id), now)
        return SaveResult(requireNotNull(items.find(id)), created = true)
    }

    /**
     * Items newest saved first. [folderUid] null reads every folder; [query] filters by full-text search. [limit]
     * defaults to 20 and is clamped to 1..100, like Microsub timelines.
     */
    @Transactional(readOnly = true)
    fun list(
        folderUid: String?,
        query: String?,
        after: String?,
        limit: Int?,
    ): SavedPage {
        val folderId = folderUid?.let { (folders.findByUid(it) ?: throw ReaderError.folderNotFound(it)).id }
        val size = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val rows = items.page(folderId, query?.trim()?.takeIf { it.isNotEmpty() }, SavedCursor.decode(after), size)
        val page = rows.take(size)
        val next = if (rows.size > size) page.last().let { SavedCursor(it.savedAt, it.id).encode() } else null
        return SavedPage(page, next)
    }

    @Transactional(readOnly = true)
    fun get(id: String): SavedItem = parse(id)?.let(items::find) ?: throw ReaderError.itemNotFound(id)

    /** Moves the item to another folder; when that folder already holds the same post, the existing item is returned. */
    @Transactional
    fun move(
        id: String,
        folderUid: String?,
    ): SavedItem {
        if (folderUid.isNullOrBlank()) throw ReaderError.invalidRequest("'folder' is required")
        val item = parse(id)?.let(items::find) ?: throw ReaderError.itemNotFound(id)
        val target = folders.findByUid(folderUid) ?: throw ReaderError.folderNotFound(folderUid)
        val touched = mutableSetOf<UUID>()
        val result = moveOne(item, target, touched)
        folders.bumpVersion(touched, now())
        return result
    }

    /** Removes the item. Removing one that is already gone is not an error. */
    @Transactional
    fun remove(id: String) {
        removeAll(listOfNotNull(parse(id)))
    }

    /** Moves or removes up to [MAX_BATCH] items at once. Unknown ids are skipped; returns how many items changed. */
    @Transactional
    fun batch(
        op: String?,
        ids: List<String>?,
        folderUid: String?,
    ): Int {
        if (ids.isNullOrEmpty()) throw ReaderError.invalidRequest("'ids' must list at least one item")
        if (ids.size > MAX_BATCH) throw ReaderError.invalidRequest("At most $MAX_BATCH items can be changed at once")
        val parsed = ids.mapNotNull(::parse).distinct()
        return when (op) {
            "remove" -> {
                removeAll(parsed)
            }

            "move" -> {
                if (folderUid.isNullOrBlank()) throw ReaderError.invalidRequest("'folder' is required to move items")
                val target = folders.findByUid(folderUid) ?: throw ReaderError.folderNotFound(folderUid)
                val touched = mutableSetOf<UUID>()
                val changed = items.findAll(parsed).count { item -> (item.folderId != target.id).also { moveOne(item, target, touched) } }
                folders.bumpVersion(touched, now())
                changed
            }

            else -> {
                throw ReaderError.invalidRequest("'op' must be 'move' or 'remove'")
            }
        }
    }

    private fun moveOne(
        item: SavedItem,
        target: SavedFolder,
        touched: MutableSet<UUID>,
    ): SavedItem {
        if (item.folderId == target.id) return item
        touched += item.folderId
        touched += target.id
        val existing = items.findInFolder(target.id, item.dedupeKey)
        if (existing != null) {
            items.delete(listOf(item.id))
            return existing
        }
        items.moveTo(item.id, target.id, now())
        return requireNotNull(items.find(item.id))
    }

    private fun removeAll(ids: List<UUID>): Int {
        val from = items.delete(ids)
        folders.bumpVersion(from, now())
        return from.size
    }

    private fun parse(id: String): UUID? = runCatching { UUID.fromString(id.trim()) }.getOrNull()

    /** Microsecond precision, so a paging cursor built from a stored time compares exactly. */
    private fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    private fun requireName(name: String?): String =
        name?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NAME) ?: throw ReaderError.invalidRequest("'name' is required")

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
        const val MAX_BATCH = 100
        private const val MAX_NAME = 100
        private const val MAX_UID = 64
    }
}
