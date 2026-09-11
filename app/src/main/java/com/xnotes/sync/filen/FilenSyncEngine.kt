package com.xnotes.sync.filen

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Two-way reconciliation between the local note tree and a Filen folder.
 *
 * Change detection compares each side to the recorded baseline (see [FilenSyncState]).
 * Conflicts (both sides changed) are resolved losslessly: the remote copy is saved
 * beside the local one under a "(filen conflict ...)" name and the local file becomes
 * the canonical remote version.
 *
 * Deletions DO propagate: a note that was synced before and is now gone on one side is
 * removed on the other, unless that other side changed since the baseline (an edit always
 * beats a delete, and the mismatch is reported instead). The recycle bin (`.trash`) is a
 * normal synced folder, so a soft delete, a restore, and a permanent delete are all just
 * ordinary moves/deletes this same logic replicates. A run that finds one whole side empty
 * while the baseline is not is treated as a transient read failure and propagates no deletes.
 */
class FilenSyncEngine(
    private val context: Context,
    private val treeUri: String,
    private val client: FilenClient,
    private val remoteRootUuid: String,
) {
    /** A note whose two sides diverged: the edit was kept and the user should know. */
    data class ConflictNote(val path: String, val message: String)

    data class Summary(
        var uploaded: Int = 0,
        var downloaded: Int = 0,
        var conflicts: Int = 0,
        var deleted: Int = 0,
        var skipped: Int = 0,
        val errors: MutableList<String> = mutableListOf(),
        val conflictNotes: MutableList<ConflictNote> = mutableListOf(),
    )

    private data class RemoteInfo(val file: FilenApi.RemoteFile, val meta: FilenClient.FileMetadata)

    fun sync(state: FilenSyncState): Summary {
        val summary = Summary()
        val local = FilenLocalStore.listNotes(context, treeUri).associateBy { it.relativePath }
        val remote = HashMap<String, RemoteInfo>()
        walkRemote(remoteRootUuid, "", remote, HashSet())

        // Guard: if one whole side comes back empty while the baseline is not, the enumeration
        // almost certainly failed transiently (root permission lost, folder unmounted, offline).
        // Propagating deletes then would wipe everything, so suppress them for this run.
        val hadBaseline = state.paths().any { !it.startsWith(".") || FilenLocalStore.isTrashPath(it) }
        val suppressDeletes = hadBaseline && (local.isEmpty() || remote.isEmpty())

        val allPaths = LinkedHashSet<String>().apply { addAll(local.keys); addAll(remote.keys); addAll(state.paths()) }
        for (path in allPaths) {
            try {
                reconcile(path, local[path], remote[path], state, summary, suppressDeletes)
            } catch (e: Exception) {
                summary.errors.add("$path: ${e.message}")
            }
        }
        return summary
    }

    private fun reconcile(
        path: String, loc: FilenLocalStore.LocalEntry?, rem: RemoteInfo?,
        state: FilenSyncState, summary: Summary, suppressDeletes: Boolean,
    ) {
        // Dot-prefixed paths that are not notes (the .xnote-config settings baseline, colour
        // sidecars) are left alone; the .trash recycle bin, however, is a normal synced folder.
        if (path.startsWith(".") && !FilenLocalStore.isTrashPath(path)) return
        val st = state.get(path)
        when {
            loc != null && rem != null -> {
                if (st == null) {
                    val bytes = FilenLocalStore.readBytes(context, loc.documentUri) ?: return
                    // Prefer the remote hash; when it's absent (older uploads / other clients), compare
                    // the actual bytes so byte-identical files don't spawn a spurious conflict copy.
                    val identical = if (rem.meta.hash != null) rem.meta.hash == FilenCrypto.sha512Hex(bytes)
                        else client.downloadFile(rem.file, rem.meta).contentEquals(bytes)
                    if (identical) {
                        state.put(path, FilenSyncState.Entry(rem.file.uuid, rem.meta.lastModified, loc.modified, loc.size))
                        summary.skipped++
                    } else {
                        doConflict(path, loc, rem, state, summary)
                    }
                } else {
                    val localChanged = loc.modified != st.localModified
                    val remoteChanged = rem.meta.lastModified != st.remoteLastModified
                    when {
                        localChanged && !remoteChanged -> doUpload(path, loc, rem.file.uuid, state, summary)
                        remoteChanged && !localChanged -> doDownload(path, rem, state, summary)
                        localChanged && remoteChanged -> doConflict(path, loc, rem, state, summary)
                        else -> Unit
                    }
                }
            }
            loc != null && rem == null -> when {
                st == null -> doUpload(path, loc, null, state, summary) // a brand-new local note
                loc.modified != st.localModified -> {
                    // Edited here, but deleted on another device: the edit wins (re-upload) and we flag it.
                    doUpload(path, loc, null, state, summary)
                    if (!FilenLocalStore.isTrashPath(path)) note(summary, path, "was deleted on another device, but you had edited it, so your copy was kept")
                }
                suppressDeletes -> summary.skipped++
                else -> {
                    // Unchanged here and gone from the other device: apply that deletion locally.
                    if (FilenLocalStore.deleteLocal(context, treeUri, path)) { state.remove(path); summary.deleted++ }
                    else summary.skipped++
                }
            }
            rem != null && loc == null -> when {
                st == null -> doDownload(path, rem, state, summary) // a note new to this device
                rem.meta.lastModified != st.remoteLastModified -> {
                    // Deleted here, but changed on another device: the edit wins (re-download) and we flag it.
                    doDownload(path, rem, state, summary)
                    if (!FilenLocalStore.isTrashPath(path)) note(summary, path, "you deleted it, but it was changed on another device, so it came back")
                }
                suppressDeletes -> summary.skipped++
                else -> {
                    // Unchanged remotely and deleted here: trash it on Filen so every device drops it.
                    runCatching { client.trashFile(rem.file.uuid) }
                    state.remove(path)
                    summary.deleted++
                }
            }
            else -> state.remove(path)
        }
    }

    private fun note(summary: Summary, path: String, message: String) {
        summary.conflicts++
        summary.conflictNotes.add(ConflictNote(path, message))
    }

    private fun doUpload(path: String, loc: FilenLocalStore.LocalEntry, oldRemoteUuid: String?, state: FilenSyncState, summary: Summary) {
        val bytes = FilenLocalStore.readBytes(context, loc.documentUri) ?: throw IllegalStateException("cannot read local file")
        val newUuid = client.uploadFile(remoteParent(path), path.substringAfterLast('/'), bytes, loc.modified)
        oldRemoteUuid?.let { runCatching { client.trashFile(it) } }
        state.put(path, FilenSyncState.Entry(newUuid, loc.modified, loc.modified, loc.size))
        summary.uploaded++
    }

    private fun doDownload(path: String, rem: RemoteInfo, state: FilenSyncState, summary: Summary) {
        val bytes = client.downloadFile(rem.file, rem.meta)
        val uri = FilenLocalStore.writeNote(context, treeUri, path, bytes) ?: throw IllegalStateException("cannot write local file")
        val localMod = FilenLocalStore.modifiedOf(context, uri)
        state.put(path, FilenSyncState.Entry(rem.file.uuid, rem.meta.lastModified, localMod, bytes.size.toLong()))
        summary.downloaded++
    }

    private fun doConflict(path: String, loc: FilenLocalStore.LocalEntry, rem: RemoteInfo, state: FilenSyncState, summary: Summary) {
        val remoteBytes = client.downloadFile(rem.file, rem.meta)
        val copyPath = conflictPath(path)
        FilenLocalStore.writeNote(context, treeUri, copyPath, remoteBytes)
        val bytes = FilenLocalStore.readBytes(context, loc.documentUri) ?: throw IllegalStateException("cannot read local file")
        val newUuid = client.uploadFile(remoteParent(path), path.substringAfterLast('/'), bytes, loc.modified)
        runCatching { client.trashFile(rem.file.uuid) }
        state.put(path, FilenSyncState.Entry(newUuid, loc.modified, loc.modified, loc.size))
        note(summary, path, "was also changed on another device; that version is saved as \"${copyPath.substringAfterLast('/')}\"")
    }

    private fun remoteParent(path: String): String {
        val folders = path.split("/").dropLast(1)
        return if (folders.isEmpty()) remoteRootUuid else client.ensureFolderPath(remoteRootUuid, folders)
    }

    private fun conflictPath(path: String): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date())
        val dot = path.lastIndexOf('.')
        return if (dot <= 0) "$path (filen conflict $ts)"
        else path.substring(0, dot) + " (filen conflict $ts)" + path.substring(dot)
    }

    private fun walkRemote(uuid: String, prefix: String, out: HashMap<String, RemoteInfo>, visited: HashSet<String>) {
        if (!visited.add(uuid)) return
        val content = client.listDecrypted(uuid)
        for ((name, file, meta) in content.files) {
            if (FilenLocalStore.isNote(name)) out[join(prefix, name)] = RemoteInfo(file, meta)
        }
        for ((name, folder) in content.folders) {
            // Descend into .trash (the synced recycle bin), skip other hidden folders (config, sidecars).
            if (name.startsWith(".") && name != FilenLocalStore.TRASH_DIR) continue
            walkRemote(folder.uuid, join(prefix, name), out, visited)
        }
    }

    private fun join(prefix: String, name: String) = if (prefix.isEmpty()) name else "$prefix/$name"
}
