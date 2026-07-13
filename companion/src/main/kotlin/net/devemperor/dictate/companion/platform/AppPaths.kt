package net.devemperor.dictate.companion.platform

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where the companion keeps its database.
 *
 * Windows: `%LOCALAPPDATA%\DictateCompanion\` — *Local*, not *Roaming*: a history of dictated texts
 * has no business being copied onto a corporate roaming profile share.
 * Linux/macOS: `$XDG_DATA_HOME` (default `~/.local/share`) — so the dev VM behaves like a real
 * install rather than dropping a file into the working directory.
 */
object AppPaths {

    private const val APP_DIR_WINDOWS = "DictateCompanion"
    private const val APP_DIR_XDG = "dictate-companion"

    fun databaseFile(): Path = dataDirectory().resolve("companion.db")

    fun dataDirectory(): Path {
        val windowsLocal = System.getenv("LOCALAPPDATA")
        if (!windowsLocal.isNullOrBlank()) return Paths.get(windowsLocal, APP_DIR_WINDOWS)

        val xdg = System.getenv("XDG_DATA_HOME")
        if (!xdg.isNullOrBlank()) return Paths.get(xdg, APP_DIR_XDG)

        return Paths.get(System.getProperty("user.home"), ".local", "share", APP_DIR_XDG)
    }
}
