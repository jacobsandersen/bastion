package dev.jacobandersen.bastion.reader.internal.web

import dev.jacobandersen.bastion.reader.internal.ReaderApi
import dev.jacobandersen.bastion.reader.internal.saved.SavedService
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Saved folders (feature `saved`): the owner's private, frozen copies of entries, kept in folders outside Microsub's
 * channels. Reading, saving, moving and removing items need the Microsub `read` scope; creating, renaming and deleting
 * folders also need `channels`. Answers are never cached.
 */
@RestController
@RequestMapping("${ReaderApi.PATH}/saved")
class SavedController(
    private val saved: SavedService,
) {
    @GetMapping("/folders")
    fun folders(): ResponseEntity<Map<String, Any?>> {
        requireScope()
        return noStore(mapOf("items" to saved.listFolders().map { it.toBody() }))
    }

    /** Creates a folder. `name` is required; `parent` (a folder uid) nests it, at most 5 levels deep. Sibling names are unique. */
    @PostMapping("/folders", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createFolder(
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        requireFolderScopes()
        val result = saved.createFolder(text(body, "name"), text(body, "parent"))
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(result.toBody())
    }

    /**
     * Renames (`name`), nests or un-nests (`parent`: a folder uid, or null for the top level) and/or reorders
     * (`position`, zero-based among the siblings) the folder. A move into itself or its subtree, or deeper than 5 levels, is
     * 400; a sibling name that is taken is 409 `name_taken`.
     */
    @PatchMapping("/folders/{uid}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun updateFolder(
        @PathVariable uid: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        requireFolderScopes()
        val position =
            when (val value = body["position"]) {
                null -> null
                is Int -> value
                else -> throw ReaderError.invalidRequest("'position' must be a whole number")
            }
        if (body["name"] == null && position == null && !body.containsKey("parent")) {
            throw ReaderError.invalidRequest("Give 'name', 'parent' and/or 'position'")
        }
        return noStore(saved.updateFolder(uid, text(body, "name"), body.containsKey("parent"), text(body, "parent"), position).toBody())
    }

    private fun text(
        body: Map<String, Any?>,
        key: String,
    ): String? =
        when (val value = body[key]) {
            null -> null
            is String -> value
            else -> throw ReaderError.invalidRequest("'$key' must be text")
        }

    /**
     * Deletes the folder. A folder with subfolders needs `children`: `move_up` gives them to the folder's parent,
     * `delete` removes the whole subtree (409 `folder_has_children` without it). The items to be deleted need `move_to`
     * (another folder, not in the deleted subtree, takes them) or `items`, the item count the owner was shown (all items of
     * the subtree for `children=delete`; 409 `folder_changed` when it no longer is, deleting nothing). An empty folder needs
     * neither. Answers `{deleted, moved, folders}`.
     */
    @DeleteMapping("/folders/{uid}")
    fun deleteFolder(
        @PathVariable uid: String,
        @RequestParam("move_to", required = false) moveTo: String?,
        @RequestParam(required = false) items: Int?,
        @RequestParam(required = false) children: String?,
    ): ResponseEntity<Map<String, Any>> {
        requireFolderScopes()
        val result = saved.deleteFolder(uid, moveTo?.takeIf { it.isNotBlank() }, items, children?.takeIf { it.isNotBlank() })
        return noStore(mapOf("deleted" to result.deleted, "moved" to result.moved, "folders" to result.folders))
    }

    /** Saves an entry into a folder: 201 with the new item, 200 with the existing one when the folder already holds it. */
    @PostMapping("/items", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun saveItem(
        @RequestBody body: SavedItemCreateRequest,
    ): ResponseEntity<Map<String, Any?>> = save(body.folder, body.channel, body.entry)

    @PostMapping("/items", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun saveItemForm(
        @RequestParam(required = false) folder: String?,
        @RequestParam(required = false) channel: String?,
        @RequestParam(required = false) entry: String?,
    ): ResponseEntity<Map<String, Any?>> = save(folder, channel, entry)

    private fun save(
        folder: String?,
        channel: String?,
        entry: String?,
    ): ResponseEntity<Map<String, Any?>> {
        requireScope()
        val result = saved.save(folder, channel, entry)
        return ResponseEntity
            .status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .cacheControl(CacheControl.noStore())
            .body(result.item.toBody())
    }

    /**
     * A page of items, newest saved first: `{items, paging: {after}}`. `folder` limits it to one folder, and with `descendants=true`
     * to it and all its subfolders; without `folder` every folder is read. `q` keeps the items matching a web-style full-text search (words are ANDed, `"phrases"`,
     * `or` and `-exclusions` work) over their name, summary, content, author name and categories. `summary=ids` answers only `{id, entry, url, saved_at}` per item, for syncing.
     */
    @GetMapping("/items")
    fun items(
        @RequestParam(required = false) folder: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) after: String?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) summary: String?,
        @RequestParam(required = false) descendants: Boolean?,
    ): ResponseEntity<Map<String, Any>> {
        requireScope()
        val page = saved.list(folder?.takeIf { it.isNotBlank() }, q, after, limit, descendants == true)
        val items = if (summary == "ids") page.items.map { it.toSummaryBody() } else page.items.map { it.toBody() }
        return noStore(mapOf("items" to items, "paging" to listOfNotNull(page.after?.let { "after" to it }).toMap()))
    }

    @GetMapping("/items/{id}")
    fun item(
        @PathVariable id: String,
    ): ResponseEntity<Map<String, Any?>> {
        requireScope()
        return noStore(saved.get(id).toBody())
    }

    /** Moves the item to another folder; when that folder already holds the same post, its existing item is returned. */
    @PatchMapping("/items/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun moveItem(
        @PathVariable id: String,
        @RequestBody body: SavedItemMoveRequest,
    ): ResponseEntity<Map<String, Any?>> {
        requireScope()
        return noStore(saved.move(id, body.folder).toBody())
    }

    /** Removes the item for good. Idempotent: an item that is already gone answers 200 as well. */
    @DeleteMapping("/items/{id}")
    fun removeItem(
        @PathVariable id: String,
    ): ResponseEntity<Map<String, Any>> {
        requireScope()
        saved.remove(id)
        return noStore(emptyMap())
    }

    /** Moves or removes up to 100 items in one call; unknown ids are skipped. Answers `{op, changed}`. */
    @PostMapping("/items/batch", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun batch(
        @RequestBody body: SavedBatchRequest,
    ): ResponseEntity<Map<String, Any>> {
        requireScope()
        val changed = saved.batch(body.op, body.ids, body.folder)
        return noStore(mapOf("op" to requireNotNull(body.op), "changed" to changed))
    }

    private fun requireFolderScopes() {
        requireScope()
        requireScope(ReaderApi.SAVED_FOLDER_SCOPE)
    }

    private fun <T : Any> noStore(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
}
