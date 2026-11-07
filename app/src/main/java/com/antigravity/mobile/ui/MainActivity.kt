package com.antigravity.mobile.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.antigravity.mobile.data.repository.AccountRepository
import com.antigravity.mobile.data.repository.ProjectRepository
import com.antigravity.mobile.engine.AntigravityService
import com.antigravity.mobile.ui.screens.ChatScreen
import com.antigravity.mobile.ui.theme.AntigravityMobileTheme
import com.antigravity.mobile.utils.StoragePermissionHelper
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var accountRepository: AccountRepository
    private lateinit var projectRepository: ProjectRepository

    private var antigravityService: AntigravityService? = null
    private var isServiceBound by mutableStateOf(false)
    private var hasStoragePermission by mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AntigravityService.LocalBinder
            antigravityService = binder.getService()
            isServiceBound = true
            maybeStartEngineSession()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            antigravityService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        accountRepository = AccountRepository(this)
        projectRepository = ProjectRepository()

        hasStoragePermission = StoragePermissionHelper.hasStoragePermission(this)

        lifecycleScope.launch {
            if (hasStoragePermission) {
                projectRepository.refreshProjects()
                if (projectRepository.projects.value.isEmpty()) {
                    projectRepository.createProject("Workspace-1")
                }
            }
        }

        // Start Foreground Service
        val serviceIntent = Intent(this, AntigravityService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            AntigravityMobileTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val activeAccount by accountRepository.activeAccount.collectAsState()
                    val activeProject by projectRepository.activeProject.collectAsState()

                    // Automatically refresh session when user switches account
                    LaunchedEffect(activeAccount?.id, activeProject?.id) {
                        maybeStartEngineSession()
                    }

                    ChatScreen(
                        accountRepository = accountRepository,
                        projectRepository = projectRepository,
                        bridge = antigravityService?.antigravityBridge,
                        hasStoragePermission = hasStoragePermission,
                        onRequestStoragePermission = {
                            StoragePermissionHelper.requestStoragePermission(this@MainActivity)
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val currentPermission = StoragePermissionHelper.hasStoragePermission(this)
        if (currentPermission != hasStoragePermission) {
            hasStoragePermission = currentPermission
            if (hasStoragePermission) {
                lifecycleScope.launch {
                    projectRepository.refreshProjects()
                    if (projectRepository.projects.value.isEmpty()) {
                        projectRepository.createProject("Workspace-1")
                    }
                    maybeStartEngineSession()
                }
            }
        }
    }

    private fun maybeStartEngineSession() {
        val account = accountRepository.activeAccount.value ?: return
        val project = projectRepository.activeProject.value ?: return
        if (account.apiKey.isBlank()) return

        antigravityService?.startSession(
            account = account,
            projectDir = File(project.absolutePath),
            onReady = {
                // Engine session connected and ready
            }
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == StoragePermissionHelper.STORAGE_PERMISSION_CODE) {
            hasStoragePermission = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (hasStoragePermission) {
                lifecycleScope.launch {
                    projectRepository.refreshProjects()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}
