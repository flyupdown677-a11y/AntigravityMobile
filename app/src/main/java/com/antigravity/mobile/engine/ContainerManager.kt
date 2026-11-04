package com.antigravity.mobile.engine

import android.content.Context
import android.util.Log
import com.antigravity.mobile.data.model.Account
import com.antigravity.mobile.utils.StoragePermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Manages the embedded lightweight Debian Linux container powered by PRoot.
 * Operates purely headlessly without presenting a terminal interface to the user.
 * 
 * Provides:
 * - User-space glibc emulation via PRoot (no root or KVM required)
 * - Bind-mounting of Android shared storage (/sdcard/AntigravityProjects -> /workspace)
 * - Background execution of the Antigravity localharness/agy engine
 */
class ContainerManager(private val context: Context) {

    private val tag = "ContainerManager"

    val rootfsDir: File = File(context.filesDir, "debian_rootfs")
    val binDir: File = File(context.filesDir, "bin")
    val prootBinary: File = File(binDir, "proot")
    val daemonScript: File = File(binDir, "antigravity_daemon.py")

    private var runningProcess: Process? = null

    /**
     * Initializes the minimal Debian rootfs and PRoot binaries if not already setup.
     */
    suspend fun setupContainerIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!binDir.exists()) binDir.mkdirs()
            if (!rootfsDir.exists()) rootfsDir.mkdirs()

            // 1. Prepare PRoot launcher
            if (!prootBinary.exists() || prootBinary.length() == 0L) {
                deployAssetOrFallback("proot", prootBinary)
                prootBinary.setExecutable(true, false)
            }

            // 2. Ensure minimal rootfs skeleton exists
            ensureMinimalRootfsSkeleton()

            // 3. Deploy Antigravity daemon script inside rootfs
            deployAssetOrFallback("antigravity_daemon.py", daemonScript)
            daemonScript.setExecutable(true, false)

            Log.i(tag, "Debian virtual environment initialized successfully.")
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize container environment", e)
            false
        }
    }

    /**
     * Launches the Antigravity engine inside the headless Debian environment.
     * The process is scoped to the active account's API key and project workspace.
     */
    suspend fun startAntigravityEngine(
        account: Account,
        activeProjectDir: File,
        port: Int = 18080
    ): Process = withContext(Dispatchers.IO) {
        stopAntigravityEngine() // Stop previous instance if running

        val projectsRoot = StoragePermissionHelper.getDefaultProjectsDirectory()
        val projectRelativePath = activeProjectDir.relativeTo(projectsRoot).path

        val env = mutableMapOf(
            "GEMINI_API_KEY" to account.apiKey,
            "MODEL_NAME" to account.modelName,
            "AGY_PORT" to port.toString(),
            "WORKSPACE_SUBDIR" to projectRelativePath,
            "HOME" to "/root",
            "PATH" to "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
            "LANG" to "C.UTF-8",
            "TMPDIR" to "/tmp"
        )

        // Construct PRoot command to virtualize Debian
        // -r: root directory of Debian rootfs
        // -0: fake root (uid=0)
        // -b: bind mounts
        //   Android /dev, /proc, /sys -> container /dev, /proc, /sys
        //   Android Projects directory -> container /workspace
        val command = mutableListOf<String>().apply {
            add(prootBinary.absolutePath)
            add("-r")
            add(rootfsDir.absolutePath)
            add("-0")
            add("-b")
            add("/dev:/dev")
            add("-b")
            add("/proc:/proc")
            add("-b")
            add("/sys:/sys")
            add("-b")
            add("${projectsRoot.absolutePath}:/workspace")
            add("-w")
            add("/workspace/$projectRelativePath")
            add("/usr/bin/python3")
            add("/bin/antigravity_daemon.py")
            add("--port")
            add(port.toString())
        }

        val processBuilder = ProcessBuilder(command)
        processBuilder.directory(binDir)
        processBuilder.environment().putAll(env)
        processBuilder.redirectErrorStream(true)

        val process = processBuilder.start()
        runningProcess = process
        Log.i(tag, "Antigravity daemon launched inside PRoot container (Port: $port).")
        process
    }

    fun stopAntigravityEngine() {
        runningProcess?.let {
            try {
                it.destroy()
                it.waitFor()
            } catch (e: Exception) {
                Log.w(tag, "Error terminating container process", e)
            } finally {
                runningProcess = null
            }
        }
    }

    fun isEngineRunning(): Boolean {
        return runningProcess?.isAlive ?: false
    }

    private fun ensureMinimalRootfsSkeleton() {
        val dirs = listOf(
            "bin", "etc", "lib", "usr/bin", "usr/lib", "usr/local/bin",
            "root", "workspace", "tmp", "var", "dev", "proc", "sys"
        )
        for (d in dirs) {
            val dir = File(rootfsDir, d)
            if (!dir.exists()) dir.mkdirs()
        }
    }

    private fun deployAssetOrFallback(assetName: String, targetFile: File) {
        try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            // When running without asset bundle in development, create placeholder script
            if (!targetFile.exists()) {
                targetFile.writeText("#!/bin/sh\n# $assetName auto-generated launcher\n")
            }
        }
    }
}
