package com.isotjs.todosian.data

import com.isotjs.todosian.utils.PersonScope
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri

import com.isotjs.todosian.BuildConfig

class PreferencesManager(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getFolderUri(): Uri? {
        val raw = prefs.getString(KEY_FOLDER_URI, null) ?: return null
        return runCatching { raw.toUri() }.getOrNull()
    }

    fun saveFolderUri(uri: Uri) {
        // The default file belongs to the previous folder.
        if (getFolderUri() != uri) clearDefaultAddTaskFileUri()
        if (prefs.getInt(KEY_LAST_SEEN_VERSION_CODE, 0) == 0) {
            prefs.edit {
                putString(KEY_FOLDER_URI, uri.toString())
                putInt(KEY_LAST_SEEN_VERSION_CODE, BuildConfig.VERSION_CODE)
            }
        } else {
            prefs.edit {
                putString(KEY_FOLDER_URI, uri.toString())
            }
        }
    }

    fun clearFolderUri() {
        prefs.edit {
            remove(KEY_FOLDER_URI)
            remove(KEY_DEFAULT_ADD_TASK_FILE_URI)
        }
    }

    /**
     * Further folders whose notes are shown next to the main folder's, e.g. a shared folder that
     * is synced separately. Kept in the order they were added.
     */
    fun getExtraFolderUris(): List<Uri> =
        prefs.getString(KEY_EXTRA_FOLDER_URIS, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.mapNotNull { raw -> runCatching { raw.toUri() }.getOrNull() }
            .orEmpty()

    fun saveExtraFolderUris(uris: List<Uri>) {
        prefs.edit {
            if (uris.isEmpty()) {
                remove(KEY_EXTRA_FOLDER_URIS)
            } else {
                putString(KEY_EXTRA_FOLDER_URIS, uris.distinct().joinToString(separator = "\n"))
            }
        }
    }

    /** The note that the "Add task" widget opens directly, instead of asking for a file. */
    fun getDefaultAddTaskFileUri(): Uri? =
        prefs.getString(KEY_DEFAULT_ADD_TASK_FILE_URI, null)?.let { raw -> runCatching { raw.toUri() }.getOrNull() }

    fun saveDefaultAddTaskFileUri(uri: Uri) {
        prefs.edit { putString(KEY_DEFAULT_ADD_TASK_FILE_URI, uri.toString()) }
    }

    fun clearDefaultAddTaskFileUri() {
        prefs.edit { remove(KEY_DEFAULT_ADD_TASK_FILE_URI) }
    }

    /**
     * The reminder profile chosen for [folderUri] on this device, by note name; null for none.
     * Not synced on purpose: everyone sharing a folder picks their own profile.
     */
    fun getReminderProfile(folderUri: Uri): String? =
        prefs.getString(KEY_REMINDER_PROFILE_PREFIX + folderUri.toString(), null)

    fun saveReminderProfile(folderUri: Uri, name: String?) {
        prefs.edit {
            if (name == null) remove(KEY_REMINDER_PROFILE_PREFIX + folderUri.toString())
            else putString(KEY_REMINDER_PROFILE_PREFIX + folderUri.toString(), name)
        }
    }

    /** Whose tasks remind on this device for [folderUri]; null reminds about every task. Not synced. */
    fun getPersonScope(folderUri: Uri): PersonScope? =
        PersonScope.decode(prefs.getString(KEY_PERSON_SCOPE_PREFIX + folderUri.toString(), null))

    fun savePersonScope(folderUri: Uri, scope: PersonScope?) {
        prefs.edit {
            if (scope == null) remove(KEY_PERSON_SCOPE_PREFIX + folderUri.toString())
            else putString(KEY_PERSON_SCOPE_PREFIX + folderUri.toString(), scope.encode())
        }
    }

    fun getLastSeenVersionCode(): Int {
        return prefs.getInt(KEY_LAST_SEEN_VERSION_CODE, 0)
    }

    fun saveLastSeenVersionCode(versionCode: Int) {
        prefs.edit { putInt(KEY_LAST_SEEN_VERSION_CODE, versionCode) }
    }

    private companion object {
        private const val PREFS_NAME = "todosian_prefs"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_REMINDER_PROFILE_PREFIX = "reminder_profile:"
        private const val KEY_PERSON_SCOPE_PREFIX = "person_scope:"
        private const val KEY_LAST_SEEN_VERSION_CODE = "last_seen_version_code"
        private const val KEY_DEFAULT_ADD_TASK_FILE_URI = "default_add_task_file_uri"
        private const val KEY_EXTRA_FOLDER_URIS = "extra_folder_uris"
    }
}
