package com.xnotes.settings

import android.content.Context
import com.xnotes.platform.JsonStore

/** Loads/saves [Settings] via the atomic, failure-tolerant [JsonStore]. */
class SettingsRepository(context: Context) {
    private val store = JsonStore.settings(context.applicationContext)

    fun load(): Settings = Settings.fromJson(store.read())

    fun save(settings: Settings) = store.write(settings.toJson())
}

/**
 * The one live [Settings] for the process. A split view runs an editor per pane and both of them
 * read and write preferences, so they have to share a single copy: with a copy each, whichever pane
 * saved last would write its own stale view over the other pane's change.
 */
object LiveSettings {

    @Volatile private var value: Settings? = null

    /** The current settings, loaded through [repo] the first time anything asks for them. */
    fun get(repo: SettingsRepository): Settings = value ?: repo.load().also { value = it }

    fun set(settings: Settings) { value = settings }
}
