package com.antigravity.mobile.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Handles Storage Permissions and Workspace Direct File Access.
 * Fully compatible with Android 10 (API 29) through Android 15 (API 35).
 */
object StoragePermissionHelper {

    const val STORAGE_PERMISSION_CODE = 1001

    /**
     * The public workspace directory accessible by the user via file manager apps.
     */
    fun getDefaultProjectsDirectory(): File {
        val base = Environment.getExternalStorageDirectory()
        val projectsDir = File(base, "AntigravityProjects")
        if (!projectsDir.exists()) {
            projectsDir.mkdirs()
        }
        return projectsDir
    }

    /**
     * Checks if full external storage permission is granted.
     */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (API 30+) All Files Access
            Environment.isExternalStorageManager()
        } else {
            // Android 10 (API 29)
            val read = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            val write = ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            read && write
        }
    }

    /**
     * Requests necessary storage permissions based on Android API level.
     */
    fun requestStoragePermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                activity.startActivity(fallbackIntent)
            }
        } else {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                STORAGE_PERMISSION_CODE
            )
        }
    }

    /**
     * Opens the given project directory in Android's default file manager.
     */
    fun openProjectInFileManager(context: Context, projectDir: File) {
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AAntigravityProjects%2F${projectDir.name}")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "resource/folder")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        // If specific folder intent fails, fall back to general document view or file viewer
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                setDataAndType(Uri.fromFile(projectDir), "*/*")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                context.startActivity(fallback)
            } catch (e2: Exception) {
                // If no file manager installed, inform user
            }
        }
    }
}
