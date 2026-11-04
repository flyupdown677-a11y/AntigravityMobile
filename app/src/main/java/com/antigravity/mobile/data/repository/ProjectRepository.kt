package com.antigravity.mobile.data.repository

import com.antigravity.mobile.data.model.Project
import com.antigravity.mobile.utils.StoragePermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class ProjectRepository {

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _activeProject = MutableStateFlow<Project?>(null)
    val activeProject: StateFlow<Project?> = _activeProject.asStateFlow()

    suspend fun refreshProjects() = withContext(Dispatchers.IO) {
        val root = StoragePermissionHelper.getDefaultProjectsDirectory()
        val dirs = root.listFiles { file -> file.isDirectory } ?: emptyArray()

        val list = dirs.map { dir ->
            Project(
                id = dir.name,
                name = dir.name,
                absolutePath = dir.absolutePath,
                lastModified = dir.lastModified()
            )
        }.sortedByDescending { it.lastModified }

        _projects.value = list

        if (_activeProject.value == null || !_projects.value.any { it.id == _activeProject.value?.id }) {
            _activeProject.value = list.firstOrNull()
        }
    }

    suspend fun createProject(projectName: String): Project = withContext(Dispatchers.IO) {
        val root = StoragePermissionHelper.getDefaultProjectsDirectory()
        val sanitized = projectName.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val dir = File(root, sanitized)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val project = Project(
            id = dir.name,
            name = dir.name,
            absolutePath = dir.absolutePath,
            lastModified = System.currentTimeMillis()
        )
        refreshProjects()
        _activeProject.value = project
        project
    }

    fun selectActiveProject(project: Project) {
        _activeProject.value = project
    }
}
