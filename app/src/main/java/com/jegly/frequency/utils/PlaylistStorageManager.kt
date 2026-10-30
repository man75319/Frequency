package com.jegly.frequency.utils

import android.content.Context
import android.util.Log
import com.jegly.frequency.R
import com.jegly.frequency.model.MusicModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.*

object PlaylistStorageManager {

    /** A user playlist as stored on disk: an explicit, stable id plus its name. */
    data class StoredPlaylist(val id: Long, val name: String)

    /** Ids 0, 1 and -1 belong to the built-in playlists, so user playlists start at 2. */
    const val FIRST_USER_PLAYLIST_ID = 2L

    private const val TAG = "PlaylistStorageManager"
    private const val FOLDER_HISTORY = "history"
    private const val PLAYLIST_DATA_FILE = "Playlist_Tracks_Data.json"
    private const val PLAYLIST_TITLES_FILE = "Playlist_Names.json"
    private const val FAVORITE_TRACKS_FILE = "FavoriteTracks.json"
    private const val IMPORTED_TRACKS_FILE = "ImportedTracks.json"

    suspend fun addToRecentTracks(context: Context, md: MusicModel) = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, FOLDER_HISTORY)
        if (!dir.exists()) dir.mkdirs()
        val safeName = md.songName.replace("[\\\\/:*?\"<>|]".toRegex(), "_") + ".json"
        val f = File(dir, safeName)
        try {
            writeJson(f, musicModelToJson(md).toString())
        } catch (e: Exception) {
            Log.e(TAG, "addToRecentTracks failed", e)
        }
    }

    suspend fun getRecentTracks(context: Context): List<MusicModel> = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, FOLDER_HISTORY)
        val files = dir.listFiles()
        val list = mutableListOf<MusicModel>()
        if (files == null) return@withContext list

        files.sortByDescending { it.lastModified() }
        var count = 0
        for (f in files) {
            if (count >= 50) break
            try {
                val json = readFile(f)
                if (json != null) {
                    list.add(musicModelFromJson(JSONObject(json)))
                    count++
                }
            } catch (e: Exception) {
                Log.e(TAG, "getRecentTracks error reading ${f.name}", e)
            }
        }
        list
    }

    suspend fun saveFavorite(context: Context, list: List<MusicModel>) {
        saveTrackList(context, FAVORITE_TRACKS_FILE, list)
    }

    suspend fun getFavorite(context: Context): List<MusicModel> = withContext(Dispatchers.IO) {
        loadTrackList(context, FAVORITE_TRACKS_FILE)
    }

    suspend fun saveImportedTracks(context: Context, list: List<MusicModel>) = withContext(Dispatchers.IO) {
        synchronized(IMPORTED_TRACKS_FILE) {
            val current = loadTrackList(context, IMPORTED_TRACKS_FILE).toMutableList()
            var modified = false
            for (m in list) {
                val exists = current.any { it.songPath == m.songPath }
                if (!exists) {
                    current.add(m)
                    modified = true
                }
            }
            if (modified) saveTrackListSync(context, IMPORTED_TRACKS_FILE, current)
        }
    }

    suspend fun getImportedTracks(context: Context): List<MusicModel> = withContext(Dispatchers.IO) {
        loadTrackList(context, IMPORTED_TRACKS_FILE)
    }

    /**
     * Persists user playlists as `[{"id":2,"name":"..."}, ...]`. The id is stored explicitly
     * rather than derived from the array position: positions shift when a playlist in the middle
     * is deleted, which used to desynchronise a playlist from its track list.
     */
    suspend fun savePlaylists(context: Context, playlists: List<StoredPlaylist>) = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        for (p in playlists) {
            try {
                arr.put(JSONObject().put("id", p.id).put("name", p.name))
            } catch (e: JSONException) {
                Log.e(TAG, "savePlaylists serialise error", e)
            }
        }
        val f = File(context.filesDir, PLAYLIST_TITLES_FILE)
        try {
            writeJson(f, arr.toString())
        } catch (e: IOException) {
            Log.e(TAG, "savePlaylists failed", e)
        }
    }

    /** Reads the user playlists. Returns an empty list when nothing has been saved yet. */
    suspend fun getPlaylists(context: Context): List<StoredPlaylist> = withContext(Dispatchers.IO) {
        val f = File(context.filesDir, PLAYLIST_TITLES_FILE)
        if (!f.exists()) return@withContext emptyList()
        val list = mutableListOf<StoredPlaylist>()
        try {
            val json = readFile(f) ?: return@withContext emptyList()
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                when (val entry = arr.get(i)) {
                    // Current format: {"id":…,"name":…}
                    is JSONObject -> {
                        val name = entry.optString("name", "")
                        if (name.isNotEmpty()) list.add(StoredPlaylist(entry.getLong("id"), name))
                    }
                    // Legacy format: a bare array of names, positionally indexed from 0.
                    is String -> if (entry.isNotEmpty()) {
                        list.add(StoredPlaylist(FIRST_USER_PLAYLIST_ID + i, entry))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getPlaylists failed", e)
        }
        list
    }

    suspend fun updatePlaylistTracks(context: Context, updatedList: List<MusicModel>?, playlistId: Long) = withContext(Dispatchers.IO) {
        synchronized(PLAYLIST_DATA_FILE) {
            val all = loadAllPlaylistTracks(context).toMutableMap()
            if (updatedList.isNullOrEmpty()) all.remove(playlistId)
            else all[playlistId] = updatedList.toList()
            saveAllPlaylistTracks(context, all)
        }
    }

    suspend fun getPlaylistTracks(context: Context, playlistId: Long): List<MusicModel> = withContext(Dispatchers.IO) {
        loadAllPlaylistTracks(context)[playlistId] ?: emptyList()
    }

    suspend fun dropPlaylistTracks(context: Context, playlistId: Long) = withContext(Dispatchers.IO) {
        synchronized(PLAYLIST_DATA_FILE) {
            val all = loadAllPlaylistTracks(context).toMutableMap()
            if (all.remove(playlistId) != null) saveAllPlaylistTracks(context, all)
        }
    }

    suspend fun dropAllPlaylistData(context: Context) = withContext(Dispatchers.IO) {
        File(context.filesDir, PLAYLIST_TITLES_FILE).delete()
        File(context.filesDir, PLAYLIST_DATA_FILE).delete()
    }

    private suspend fun saveTrackList(context: Context, fileName: String, list: List<MusicModel>) = withContext(Dispatchers.IO) {
        saveTrackListSync(context, fileName, list)
    }

    private fun saveTrackListSync(context: Context, fileName: String, list: List<MusicModel>) {
        val f = File(context.filesDir, fileName)
        if (list.isEmpty()) {
            if (f.exists() && f.delete()) Log.d(TAG, "$fileName deleted")
            return
        }
        val arr = JSONArray()
        for (m in list) {
            try {
                arr.put(musicModelToJson(m))
            } catch (e: JSONException) {
                Log.e(TAG, "saveTrackListSync serialise error", e)
            }
        }
        try {
            writeJson(f, arr.toString())
        } catch (e: IOException) {
            Log.e(TAG, "saveTrackListSync write error for $fileName", e)
        }
    }

    private fun loadTrackList(context: Context, fileName: String): List<MusicModel> {
        val list = mutableListOf<MusicModel>()
        val f = File(context.filesDir, fileName)
        if (!f.exists()) return list
        try {
            val json = readFile(f) ?: return list
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                list.add(musicModelFromJson(arr.getJSONObject(i)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadTrackList error for $fileName", e)
        }
        return list
    }

    private fun saveAllPlaylistTracks(context: Context, tracksById: Map<Long, List<MusicModel>>) {
        val f = File(context.filesDir, PLAYLIST_DATA_FILE)
        val outer = JSONObject()
        for ((id, inner) in tracksById) {
            val arr = JSONArray()
            for (m in inner) {
                try {
                    arr.put(musicModelToJson(m))
                } catch (e: JSONException) {
                    Log.e(TAG, "saveAllPlaylistTracks serialise error", e)
                }
            }
            outer.put(id.toString(), arr)
        }
        try {
            writeJson(f, outer.toString())
        } catch (e: IOException) {
            Log.e(TAG, "saveAllPlaylistTracks write error", e)
        }
    }

    /**
     * Track lists keyed by playlist id. Also reads the legacy format — a positional array of
     * arrays, where element 0 held the first user playlist — so existing data survives the switch.
     */
    private fun loadAllPlaylistTracks(context: Context): Map<Long, List<MusicModel>> {
        val result = mutableMapOf<Long, List<MusicModel>>()
        val f = File(context.filesDir, PLAYLIST_DATA_FILE)
        if (!f.exists()) return result
        try {
            val json = readFile(f) ?: return result
            if (json.trimStart().startsWith("[")) {
                val outer = JSONArray(json)
                for (i in 0 until outer.length()) {
                    result[FIRST_USER_PLAYLIST_ID + i] = readTrackArray(outer.getJSONArray(i))
                }
            } else {
                val outer = JSONObject(json)
                for (key in outer.keys()) {
                    val id = key.toLongOrNull() ?: continue
                    result[id] = readTrackArray(outer.getJSONArray(key))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadAllPlaylistTracks error", e)
        }
        return result
    }

    private fun readTrackArray(arr: JSONArray): List<MusicModel> {
        val list = mutableListOf<MusicModel>()
        for (j in 0 until arr.length()) {
            try {
                list.add(musicModelFromJson(arr.getJSONObject(j)))
            } catch (e: Exception) {
                Log.e(TAG, "readTrackArray parse error", e)
            }
        }
        return list
    }

    private fun musicModelToJson(m: MusicModel): JSONObject {
        val o = JSONObject()
        o.put("id", m.id)
        o.put("name", m.songName)
        o.put("artist", m.artist)
        o.put("album", m.album)
        o.put("path", m.songPath ?: "")
        o.put("art", m.albumArtUrl ?: "")
        o.put("duration", m.duration)
        o.put("dateAdded", m.dateAdded)
        return o
    }

    private fun musicModelFromJson(o: JSONObject): MusicModel {
        return MusicModel(
            o.getLong("id"),
            o.optString("name", ""),
            o.optString("artist", ""),
            o.optString("album", ""),
            o.optString("path", null),
            o.optString("art", null),
            o.optLong("duration", 0),
            o.optLong("dateAdded", 0)
        )
    }

    private fun writeJson(file: File, json: String) {
        BufferedWriter(FileWriter(file, false)).use { it.write(json) }
    }

    private fun readFile(file: File): String? {
        val sb = StringBuilder()
        BufferedReader(FileReader(file)).use { r ->
            var line: String?
            while (r.readLine().also { line = it } != null) sb.append(line)
        }
        return if (sb.isNotEmpty()) sb.toString() else null
    }
}
