package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val PREF_GITHUB_OWNER = "update_github_owner"
    private const val PREF_GITHUB_REPO = "update_github_repo"
    private const val DEFAULT_OWNER = "SUBHOJITPAUL797"
    private const val DEFAULT_REPO = "DASMO-CYBER-CAFE-TOOL"

    fun getGithubOwner(context: Context): String {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        return prefs.getString(PREF_GITHUB_OWNER, DEFAULT_OWNER) ?: DEFAULT_OWNER
    }

    fun getGithubRepo(context: Context): String {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        return prefs.getString(PREF_GITHUB_REPO, DEFAULT_REPO) ?: DEFAULT_REPO
    }

    fun saveGithubConfig(context: Context, owner: String, repo: String) {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString(PREF_GITHUB_OWNER, owner.trim())
            .putString(PREF_GITHUB_REPO, repo.trim())
            .apply()
    }

    data class UpdateInfo(
        val hasUpdate: Boolean,
        val latestVersion: String,
        val currentVersion: String,
        val releaseTitle: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val browserUrl: String,
        val isCritical: Boolean = false,
        val apkSizeMb: Double = 0.0
    )

    suspend fun checkForUpdates(context: Context): UpdateInfo = withContext(Dispatchers.IO) {
        val owner = getGithubOwner(context)
        val repo = getGithubRepo(context)
        val url = "https://api.github.com/repos/$owner/$repo/releases/latest"

        val currentVersion = try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "DASMO-Update-Checker")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code != 404) {
                        Log.e(TAG, "Failed to fetch update info: ${response.code}")
                    }
                    return@withContext UpdateInfo(false, currentVersion, currentVersion, "", "", "", "")
                }

                val bodyString = response.body?.string() ?: ""
                if (bodyString.isEmpty()) {
                    return@withContext UpdateInfo(false, currentVersion, currentVersion, "", "", "", "")
                }

                val json = JSONObject(bodyString)
                val tagName = json.optString("tag_name", "").trim()
                val releaseTitle = json.optString("name", "New Release $tagName")
                val browserUrl = json.optString("html_url", "")
                val body = json.optString("body", "• Improvements and bug fixes.")

                var downloadUrl = ""
                var apkSizeBytes = 0L
                val assets = json.optJSONArray("assets")
                if (assets != null && assets.length() > 0) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val assetName = asset.optString("name", "")
                        if (assetName.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = asset.optString("browser_download_url", "")
                            apkSizeBytes = asset.optLong("size", 0L)
                            break
                        }
                    }
                }

                if (downloadUrl.isEmpty()) {
                    downloadUrl = browserUrl
                }

                val cleanLatest = sanitizeVersion(tagName)
                val cleanCurrent = sanitizeVersion(currentVersion)
                val hasUpdate = isLatestVersionNewer(cleanCurrent, cleanLatest)
                val isCritical = body.contains("[CRITICAL]", ignoreCase = true) ||
                        body.contains("[MANDATORY]", ignoreCase = true) ||
                        releaseTitle.contains("CRITICAL", ignoreCase = true)

                val apkSizeMb = if (apkSizeBytes > 0) apkSizeBytes / (1024.0 * 1024.0) else 0.0

                return@withContext UpdateInfo(
                    hasUpdate = hasUpdate,
                    latestVersion = tagName,
                    currentVersion = currentVersion,
                    releaseTitle = releaseTitle,
                    releaseNotes = body,
                    downloadUrl = downloadUrl,
                    browserUrl = browserUrl,
                    isCritical = isCritical,
                    apkSizeMb = apkSizeMb
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
            return@withContext UpdateInfo(false, currentVersion, currentVersion, "", "", "", "")
        }
    }

    private fun sanitizeVersion(version: String): String {
        return version.lowercase().removePrefix("v").trim()
    }

    private fun isLatestVersionNewer(current: String, latest: String): Boolean {
        if (current == latest) return false

        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }

        val maxLength = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until maxLength) {
            val currentVal = currentParts.getOrNull(i) ?: 0
            val latestVal = latestParts.getOrNull(i) ?: 0
            if (latestVal > currentVal) return true
            if (latestVal < currentVal) return false
        }

        return false
    }

    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (progress: Float, downloadedMb: Double, totalMb: Double) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updateDir, "dasmo_scanner_update.apk")
            if (apkFile.exists()) apkFile.delete()

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "DASMO-APK-Downloader")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Download failed: HTTP ${response.code}"))
                }

                val body = response.body ?: return@withContext Result.failure(Exception("Empty response body"))
                val contentLength = body.contentLength()
                val totalMb = if (contentLength > 0) contentLength / (1024.0 * 1024.0) else 0.0

                body.byteStream().use { inputStream ->
                    FileOutputStream(apkFile).use { outputStream ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        var totalBytesRead = 0L
                        var lastReportTime = System.currentTimeMillis()

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastReportTime > 100 || totalBytesRead == contentLength) {
                                lastReportTime = now
                                val progress = if (contentLength > 0) totalBytesRead.toFloat() / contentLength else 0f
                                val downloadedMb = totalBytesRead / (1024.0 * 1024.0)
                                withContext(Dispatchers.Main) {
                                    onProgress(progress, downloadedMb, totalMb)
                                }
                            }
                        }
                        outputStream.flush()
                    }
                }

                return@withContext Result.success(apkFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading APK", e)
            return@withContext Result.failure(e)
        }
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            if (!apkFile.exists()) {
                Log.e(TAG, "APK file does not exist: ${apkFile.absolutePath}")
                return
            }

            val authority = "${context.packageName}.provider"
            val uri = FileProvider.getUriForFile(context, authority, apkFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Check if unknown app sources permission needed on Android 8.0+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                    return
                }
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch installer", e)
        }
    }

    fun openUrl(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open URL: $url", e)
        }
    }
}
