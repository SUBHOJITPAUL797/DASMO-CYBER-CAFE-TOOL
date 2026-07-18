package com.example.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class GoogleDriveFolder(
    val id: String,
    val name: String
)

data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val createdTime: String?,
    val webViewLink: String?,
    val folderId: String,
    var folderName: String? = null
)

object GoogleDriveClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun listFolders(accessToken: String, parentId: String = "root"): List<GoogleDriveFolder> = withContext(Dispatchers.IO) {
        val folders = mutableListOf<GoogleDriveFolder>()
        val effectiveParentId = if (parentId.isEmpty()) "root" else parentId
        val q = "'$effectiveParentId' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val url = "https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode(q, "UTF-8")}&fields=files(id,name)&pageSize=1000&supportsAllDrives=true&includeItemsFromAllDrives=true"
        
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val filesArray = json.optJSONArray("files")
                if (filesArray != null) {
                    for (i in 0 until filesArray.length()) {
                        val item = filesArray.getJSONObject(i)
                        folders.add(
                            GoogleDriveFolder(
                                id = item.optString("id", ""),
                                name = item.optString("name", "")
                            )
                        )
                    }
                }
            } else {
                val errBody = response.body?.string() ?: ""
                throw Exception("Google Drive API Error (${response.code}): $errBody")
            }
        }
        folders
    }

    suspend fun createFolder(accessToken: String, name: String, parentId: String = "root"): String? = withContext(Dispatchers.IO) {
        android.util.Log.d("GoogleDriveClient", "createFolder: name=$name parentId=$parentId")
        val url = "https://www.googleapis.com/drive/v3/files?supportsAllDrives=true"
        val json = JSONObject().apply {
            put("name", name)
            put("mimeType", "application/vnd.google-apps.folder")
            if (parentId.isNotEmpty()) {
                put("parents", org.json.JSONArray().apply { put(parentId) })
            }
        }
        val requestBody = json.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())
        
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val resJson = JSONObject(body)
                if (resJson.has("id")) resJson.getString("id") else null
            } else {
                val errBody = response.body?.string() ?: ""
                throw Exception("Google Drive folder creation failed (${response.code}): $errBody")
            }
        }
    }

    suspend fun getOrCreateFolder(accessToken: String, name: String, parentId: String = "root"): String? = withContext(Dispatchers.IO) {
        android.util.Log.d("GoogleDriveClient", "getOrCreateFolder: name=$name parentId=$parentId")
        
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            return@withContext if (parentId.isEmpty()) "root" else parentId
        }
        
        val effectiveParentId = if (parentId.isEmpty()) "root" else parentId
        
        // 1. Direct Search Query for exact folder name & parent (Bulletproof, Shared-drive safe, Case-insensitive match)
        try {
            val escapedName = trimmedName.replace("'", "\\'")
            val q = "'$effectiveParentId' in parents and name = '$escapedName' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            val url = "https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode(q, "UTF-8")}&fields=files(id,name)&pageSize=20&supportsAllDrives=true&includeItemsFromAllDrives=true"
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
                
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val filesArray = json.optJSONArray("files")
                    if (filesArray != null && filesArray.length() > 0) {
                        for (i in 0 until filesArray.length()) {
                            val item = filesArray.getJSONObject(i)
                            val folderName = item.optString("name", "")
                            if (folderName.trim().equals(trimmedName, ignoreCase = true)) {
                                val foundId = item.getString("id")
                                android.util.Log.d("GoogleDriveClient", "getOrCreateFolder: Found existing folder directly via name match: $trimmedName (ID: $foundId)")
                                return@withContext foundId
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("GoogleDriveClient", "Direct query failed, falling back to listFolders", e)
        }
        
        // 2. Fallback: List folders and search locally (in case of query quirks)
        try {
            val existing = listFolders(accessToken, effectiveParentId)
            val found = existing.find { it.name.trim().equals(trimmedName, ignoreCase = true) }
            if (found != null) {
                android.util.Log.d("GoogleDriveClient", "getOrCreateFolder: Found existing folder via local list match: $trimmedName (ID: ${found.id})")
                return@withContext found.id
            }
        } catch (e: Exception) {
            android.util.Log.e("GoogleDriveClient", "Fallback listFolders search failed", e)
        }
        
        // 3. Not found, create it
        android.util.Log.d("GoogleDriveClient", "getOrCreateFolder: Folder '$trimmedName' not found. Creating a new folder.")
        return@withContext createFolder(accessToken, trimmedName, effectiveParentId)
    }

    suspend fun findFileByName(accessToken: String, parentId: String, fileName: String): String? = withContext(Dispatchers.IO) {
        try {
            val q = "'$parentId' in parents and name = '${fileName.replace("'", "\\'")}' and trashed = false"
            val url = "https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode(q, "UTF-8")}&fields=files(id)&supportsAllDrives=true&includeItemsFromAllDrives=true"
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val filesArray = json.optJSONArray("files")
                    if (filesArray != null && filesArray.length() > 0) {
                        return@withContext filesArray.getJSONObject(0).getString("id")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    suspend fun uploadFile(
        accessToken: String,
        file: File,
        mimeType: String,
        fileName: String,
        parentId: String,
        oldFileName: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val existingFileId = findFileByName(accessToken, parentId, oldFileName ?: fileName)
        
        val url = if (existingFileId != null) {
            "https://www.googleapis.com/upload/drive/v3/files/$existingFileId?uploadType=multipart&supportsAllDrives=true"
        } else {
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&supportsAllDrives=true"
        }
        
        val metadata = JSONObject().apply {
            put("name", fileName)
            if (existingFileId == null && parentId.isNotEmpty()) {
                put("parents", org.json.JSONArray().apply { put(parentId) })
            }
        }
        
        val mediaTypeJson = "application/json; charset=UTF-8".toMediaType()
        val mediaTypeFile = mimeType.toMediaType()

        val multipartBody = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toString().toRequestBody(mediaTypeJson))
            .addPart(file.asRequestBody(mediaTypeFile))
            .build()

        val request = Request.Builder()
            .url(url)
            .method(if (existingFileId != null) "PATCH" else "POST", multipartBody)
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                true
            } else {
                val errBody = response.body?.string() ?: ""
                throw Exception("Google Drive upload failed (${response.code}): $errBody")
            }
        }
    }

    suspend fun listFiles(accessToken: String, folderId: String): List<DriveFile> = withContext(Dispatchers.IO) {
        val filesList = mutableListOf<DriveFile>()
        val q = "'$folderId' in parents and mimeType != 'application/vnd.google-apps.folder' and trashed = false"
        // Request fields we care about, including createdTime and webViewLink for detailed presentation
        val fields = "files(id,name,mimeType,size,createdTime,webViewLink)"
        val url = "https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode(q, "UTF-8")}&fields=${java.net.URLEncoder.encode(fields, "UTF-8")}&pageSize=1000&supportsAllDrives=true&includeItemsFromAllDrives=true"
        
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val filesArray = json.optJSONArray("files")
                if (filesArray != null) {
                    for (i in 0 until filesArray.length()) {
                        val item = filesArray.getJSONObject(i)
                        filesList.add(
                            DriveFile(
                                id = item.optString("id", ""),
                                name = item.optString("name", "Unnamed File"),
                                mimeType = item.optString("mimeType", ""),
                                size = item.optLong("size", 0L),
                                createdTime = item.optString("createdTime", null),
                                webViewLink = item.optString("webViewLink", null),
                                folderId = folderId
                            )
                        )
                    }
                }
            } else {
                val errBody = response.body?.string() ?: ""
                android.util.Log.e("GoogleDriveClient", "listFiles Error: $errBody")
            }
        }
        filesList
    }
}
