package com.videoeditor.app.navigation

/** Central navigation route definitions. */
object Routes {
    const val HOME = "home"
    const val PROJECTS = "projects"
    const val SETTINGS = "settings"
    const val MEDIA_IMPORT = "media_import"
    const val EDITOR = "editor/{projectId}"
    const val EXPORT = "export/{projectId}"

    fun editor(projectId: String) = "editor/$projectId"
    fun export(projectId: String) = "export/$projectId"
}
