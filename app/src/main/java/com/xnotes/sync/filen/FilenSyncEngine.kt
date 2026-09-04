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
 * the canonical remote version. Deletions are intentionally NOT propagated: a note
 * removed on one side is left alone on the other, never resurrected and never deleted.
 */
class FilenSyncEngine(
    private val context: Context,
    private val treeUri: String,
    private val client: FilenClient,
    private val remoteRootUuid: String,
) {
    data class Summary(var uploaded: Int = 0, var downloaded: Int = 0, var conflicts: Int = 0, var skipped: Int = 0, val errors: MutableList<String> = mutableListOf())

    private data class RemoteInfo(val file: FilenApi.RemoteFile, val meta: FilenClient.FileMetadata)

    fun sync(state: FilenSyncState): Summary {
        val summary = Summary()
        val local = FilenLocalStore.listNotes(context, treeUri).associateBy { it.relativePath }
        val remote = HashMap<String, RemoteInfo>()
        walkRemote(remoteRootUuid, "", remote, HashSet())

        val allPaths = LinkedHashSet<String>().apply { addAll(local.keys); addAll(remote.keys); addAll(state.paths()) }
        for (path in allPaths) {
            try {
                reconcile(path, local[path], remote[path], state, summary)
            } catch (e: Exception) {
                summary.errors.add("$path: ${e.message}")
            }
        }
        return summary
    }

    private fun reconcile(
        path: String, loc: FilenLocalStore.LocalEntry?, rem: RemoteInfo?,
        state: FilenSyncState, summary: Summary,
    ) {
        val st = state.get(path)
        when {
            loc != null && rem != null -> {
                if (st == null) {
                    val bytes = FilenLocalStore.readBytes(context, loc.documentUri) ?: return
                    if (rem.meta.hash != null && rem.meta.hash == FilenCrypto.sha512Hex(bytes)) {
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
            loc != null && rem == null -> {
                if (st == null || loc.modified != st.localModified) doUpload(path, loc, null, state, summary)
                else summary.skipped++
            }
            rem != null && loc == null -> {
                if (st == null || rem.meta.lastModified != st.remoteLastModified) doDownload(path, rem, state, summary)
                else summary.skipped++
            }
            else -> state.remove(path)
        }
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
        FilenLocalStore.writeNote(context, treeUri, conflictPath(path), remoteBytes)
        val bytes = FilenLocalStore.readBytes(context, loc.documentUri) ?: throw IllegalStateException("cannot read local file")
        val newUuid = client.uploadFile(remoteParent(path), path.substringAfterLast('/'), bytes, loc.modified)
        runCatching { client.trashFile(rem.file.uuid) }
        state.put(path, FilenSyncState.Entry(newUuid, loc.modified, loc.modified, loc.size))
        summary.conflicts++
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
            if (name.startsWith(".")) continue
            walkRemote(folder.uuid, join(prefix, name), out, visited)
        }
    }

    private fun join(prefix: String, name: String) = if (prefix.isEmpty()) name else "$prefix/$name"
}
