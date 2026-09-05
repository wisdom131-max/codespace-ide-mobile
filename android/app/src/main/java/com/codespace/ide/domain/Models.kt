package com.codespace.ide.domain

sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

sealed interface AppError {
    val message: String
    data class Network(override val message: String) : AppError
    data class Http(val code: String, override val message: String, val status: Int) : AppError
    data class Auth(val expired: Boolean, override val message: String) : AppError
    data class Git(override val message: String) : AppError
    data class Ssh(override val message: String) : AppError
    data class Ai(val provider: String, override val message: String) : AppError
    data class Unknown(override val message: String) : AppError
}

enum class ProjectKind { GIT, SSH, CODESPACE, DOCKER, LOCAL }

data class Project(
    val id: String,
    val name: String,
    val kind: ProjectKind,
    val pathOrUrl: String,
    val defaultBranch: String? = null,
    val lastOpened: Long = 0L,
)

data class Workspace(val id: String, val name: String, val projects: List<Project>)

data class FileNode(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long = 0,
    val children: List<FileNode> = emptyList(),
)

data class EditorTab(
    val id: String,
    val path: String,
    val name: String,
    val content: String,
    val language: Language,
    val isDirty: Boolean = false,
    val cursorOffset: Int = 0,
    val savedContent: String = content,  // snapshot when file was last saved — for diff gutter
)

data class GitStatus(
    val branch: String,
    val ahead: Int,
    val behind: Int,
    val staged: List<String>,
    val modified: List<String>,
    val untracked: List<String>,
)

enum class AiAction { EXPLAIN, GENERATE, REFACTOR, FIX, DOCUMENT, TEST }

data class ChatMessage(val role: String, val content: String)
