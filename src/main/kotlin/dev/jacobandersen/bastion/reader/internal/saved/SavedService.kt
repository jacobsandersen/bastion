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
    /** How many folders were removed (more than one when a subtree was deleted). */
    val folders: Int,
)

/** The folders as a tree, built from the flat list: lookups by id and uid, children, depth and ancestry. */
internal class FolderTree(
    all: List<SavedFolder>,
) {
    val byId: Map<UUID?, SavedFolder> = all.associateBy { it.id }
    val byUid: Map<String, SavedFolder> = all.associateBy { it.uid }
    private val byParent: Map<UUID?, List<SavedFolder>> = all.groupBy { it.parentId }

    /** The children of [parentId] (null: the top level), in order. */
    fun children(parentId: UUID?): List<SavedFolder> = byParent[parentId].orEmpty()

    fun descendants(folder: SavedFolder): List<SavedFolder> = children(folder.id).flatMap { listOf(it) + descendants(it) }

    /** 1 for a top-level folder. */
    fun depth(folder: SavedFolder): Int = ancestorsAndSelf(folder).size

    /** Levels of the subtree under and including [folder]: 1 for a folder with no subfolders. */
    fun height(folder: SavedFolder): Int = 1 + (children(folder.id).maxOfOrNull(::height) ?: 0)

    fun isDescendant(
        folder: SavedFolder,
        of: SavedFolder,
    ): Boolean = ancestorsAndSelf(folder).any { it.id == of.id }

    fun ancestorsAndSelf(folder: SavedFolder): List<SavedFolder> {
        val chain = mutableListOf(folder)
        var parent = folder.parentId?.let(byId::get)
        while (parent != null && chain.none { it.id == parent!!.id }) {
            chain += parent
            parent = parent.parentId?.let(byId::get)
        }
        return chain
    }
}

/** What the saved folders hold. [payloadBytes] is the row size of the items; [onDiskBytes] also counts indexes and free space. */
data class SavedStorage(
    val items: Long,
    val folders: List<SavedFolderSummary>,
    val payloadBytes: Long,
    val onDiskBytes: Long,
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
    private val stats: SavedStatsRepository,
) {
    @Transactional(readOnly = true)
    fun listFolders(): List<SavedFolderSummary> = inTreeOrder(folders.summaries())

    /** Counts and sizes of everything saved, computed on each call: the tables hold a handful of rows per folder to scan. */
    @Transactional(readOnly = true)
    fun storage(): SavedStorage {
        val summaries = inTreeOrder(folders.summaries())
        return SavedStorage(summaries.sumOf { it.count }, summaries, summaries.sumOf { it.bytes }, stats.onDiskBytes())
    }

    /** Creates a folder, nested in [parentUid] when given (at most [MAX_DEPTH] levels deep). Sibling names are unique. */
    @Transactional
    fun createFolder(
        name: String?,
        parentUid: String?,
    ): SavedFolderSummary {
        val trimmed = requireName(name)
        val tree = FolderTree(folders.list())
        val parent = parentUid?.let { tree.byUid[it] ?: throw ReaderError.folderNotFound(it) }
        if ((parent?.let(tree::depth) ?: 0) + 1 >
            MAX_DEPTH
        ) {
            throw ReaderError.invalidRequest("Folders can be nested at most $MAX_DEPTH deep")
        }
        requireNameFree(tree, parent?.id, trimmed, except = null)
        val base = slugify.slugify(trimmed).take(MAX_UID).ifBlank { "folder" }
        var uid = base
        var suffix = 2
        while (folders.uidExists(uid)) uid = "$base-${suffix++}"
        val folder = folders.insert(uid, trimmed, parent?.id, tree.children(parent?.id).size, now())
        return requireNotNull(folders.summary(folder.id))
    }

    /**
     * Renames the folder, and/or moves it under [parentUid] ([moveParent] true; null parent = top level), and/or puts it
     * at [position] (0 is first) among its siblings. A moved folder with no [position] goes last. Its uid never changes.
     * A move that would put a folder inside itself or its own subtree, or nest anything deeper than [MAX_DEPTH], is refused.
     */
    @Transactional
    fun updateFolder(
        uid: String,
        name: String?,
        moveParent: Boolean,
        parentUid: String?,
        position: Int?,
    ): SavedFolderSummary {
        val folder = folders.lockByUid(uid) ?: throw ReaderError.folderNotFound(uid)
        val tree = FolderTree(folders.list())
        val newName = if (name != null) requireName(name) else folder.name
        val parent =
            if (moveParent) {
                parentUid?.let {
                    tree.byUid[it] ?: throw ReaderError.folderNotFound(
                        it,
                    )
                }
            } else {
                tree.byId[folder.parentId]
            }
        val parentChanged = moveParent && parent?.id != folder.parentId
        if (parentChanged) {
            if (parent != null && (parent.id == folder.id || tree.isDescendant(parent, folder))) {
                throw ReaderError.invalidRequest("A folder cannot be moved into itself or one of its subfolders")
            }
            if ((parent?.let(tree::depth) ?: 0) + tree.height(folder) > MAX_DEPTH) {
                throw ReaderError.invalidRequest("Folders can be nested at most $MAX_DEPTH deep")
            }
        }
        if (parentChanged || newName != folder.name) requireNameFree(tree, parent?.id, newName, except = folder.id)
        val now = now()
        if (newName != folder.name) folders.rename(folder.id, newName, now)
        if (parentChanged) {
            folders.setParent(folder.id, parent?.id, tree.children(parent?.id).size, now)
            // The subtrees of both old and new ancestors changed membership.
            bump(listOfNotNull(folder.parentId, parent?.id), tree, now)
        }
        if (position != null) {
            val order = folders.list().filter { it.parentId == parent?.id && it.id != folder.id }.toMutableList()
            order.add(position.coerceIn(0, order.size), folder)
            order.forEachIndexed { index, each -> folders.setPosition(each.id, index) }
        }
        return requireNotNull(folders.summary(folder.id))
    }

    /**
     * Deletes the folder. A folder with subfolders needs [children]: `move_up` reparents them to the deleted folder's
     * parent, `delete` removes the whole subtree with its items (409 `folder_has_children` without it). The items to be
     * deleted need [moveTo] (another folder, outside the deleted subtree, takes them; ones it already holds are dropped) or
     * [expectedItems], the count the owner was shown (for `delete`, all items of the subtree), which must still be right:
     * otherwise nothing is deleted (409 `folder_changed`), so a folder never loses more than the owner was told.
     */
    @Transactional
    fun deleteFolder(
        uid: String,
        moveTo: String?,
        expectedItems: Int?,
        children: String?,
    ): FolderDeletion {
        val folder = folders.lockByUid(uid) ?: throw ReaderError.folderNotFound(uid)
        val tree = FolderTree(folders.list())
        val kids = tree.children(folder.id)
        if (children != null && children != CHILDREN_MOVE_UP && children != CHILDREN_DELETE) {
            throw ReaderError.invalidRequest("'children' must be '$CHILDREN_MOVE_UP' or '$CHILDREN_DELETE'")
        }
        if (kids.isNotEmpty() && children == null) throw ReaderError.folderHasChildren(kids.size)
        val doomed = if (children == CHILDREN_DELETE) listOf(folder) + tree.descendants(folder) else listOf(folder)
        val doomedIds = doomed.map { it.id }.toSet()
        val count = items.countInFolders(doomedIds).toInt()
        val now = now()
        var moved = 0
        var target: SavedFolder? = null
        if (moveTo != null) {
            val found = tree.byUid[moveTo] ?: throw ReaderError.folderNotFound(moveTo)
            if (found.id in doomedIds) throw ReaderError.invalidRequest("'move_to' must name a folder that is not deleted")
            target = folders.lockByUid(moveTo)
        } else {
            if (expectedItems != null && expectedItems != count) throw ReaderError.folderChanged(count, expectedItems)
            if (count > 0 && expectedItems == null) {
                throw ReaderError.invalidRequest("Give 'move_to' or 'items' (the number of items to delete, now $count)")
            }
        }
        if (children == CHILDREN_MOVE_UP && kids.isNotEmpty()) {
            val siblings = tree.children(folder.parentId).filter { it.id != folder.id }
            kids
                .firstOrNull { kid ->
                    siblings.any { it.name.equals(kid.name, ignoreCase = true) }
                }?.let { throw ReaderError.nameTaken(it.name) }
            kids.forEachIndexed { index, kid -> folders.setParent(kid.id, folder.parentId, siblings.size + index, now) }
        }
        if (target != null) doomed.forEach { moved += items.moveAll(it.id, target.id, now) }
        folders.delete(folder.id)
        bump(listOfNotNull(folder.parentId, target?.id), tree, now)
        return FolderDeletion(deleted = count - moved, moved = moved, folders = doomed.size)
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
        bump(listOf(folder.id), now = now)
        return SaveResult(requireNotNull(items.find(id)), created = true)
    }

    /**
     * Items newest saved first. [folderUid] null reads every folder; [descendants] adds the folder's subfolders; [query] filters by full-text search. [limit]
     * defaults to 20 and is clamped to 1..100, like Microsub timelines.
     */
    @Transactional(readOnly = true)
    fun list(
        folderUid: String?,
        query: String?,
        after: String?,
        limit: Int?,
        descendants: Boolean = false,
    ): SavedPage {
        val folderIds =
            folderUid?.let {
                val tree = FolderTree(folders.list())
                val folder = tree.byUid[it] ?: throw ReaderError.folderNotFound(it)
                if (descendants) (listOf(folder) + tree.descendants(folder)).map { each -> each.id } else listOf(folder.id)
            }
        val size = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val rows = items.page(folderIds, query?.trim()?.takeIf { it.isNotEmpty() }, SavedCursor.decode(after), size)
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
        bump(touched, now = now())
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
                bump(touched, now = now())
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
        bump(from, now = now())
        return from.size
    }

    /** Parents before their children, siblings by position: the order a client shows the tree in. */
    private fun inTreeOrder(all: List<SavedFolderSummary>): List<SavedFolderSummary> {
        val byParent = all.groupBy { it.folder.parentId }

        fun walk(parent: UUID?): List<SavedFolderSummary> = byParent[parent].orEmpty().flatMap { listOf(it) + walk(it.folder.id) }
        return walk(null)
    }

    /** Bumps the folders' versions and their ancestors', whose subtree (`descendants=true`) listing changed too. */
    private fun bump(
        ids: Collection<UUID>,
        tree: FolderTree = FolderTree(folders.list()),
        now: Instant,
    ) {
        val all = ids.toSet().flatMapTo(mutableSetOf()) { id -> listOfNotNull(tree.byId[id]).flatMap { tree.ancestorsAndSelf(it) } }
        folders.bumpVersion(all.map { it.id }, now)
    }

    private fun requireNameFree(
        tree: FolderTree,
        parentId: UUID?,
        name: String,
        except: UUID?,
    ) {
        if (tree.children(parentId).any { it.id != except && it.name.equals(name, ignoreCase = true) }) throw ReaderError.nameTaken(name)
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
        const val MAX_DEPTH = 5
        const val CHILDREN_MOVE_UP = "move_up"
        const val CHILDREN_DELETE = "delete"
    }
}
