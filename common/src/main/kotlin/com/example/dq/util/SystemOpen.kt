package com.example.dq.util

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * 调系统软件打开文件/目录(导出任务的「打开」「打开文件目录」)。
 * 纯 ProcessBuilder 实现,不用 java.awt.Desktop(headless 部署下 Desktop 直接抛 HeadlessException)。
 * 打开文档的软件顺序:MS Office → WPS → 系统默认关联程序。
 */
object SystemOpen {

    private val log = LoggerFactory.getLogger(javaClass)
    private val os = System.getProperty("os.name").lowercase()

    /** 打开 Word 文档:MS Office → WPS → 系统默认 */
    fun openDocument(path: Path) {
        when {
            os.contains("mac") -> {
                val word = Path.of("/Applications/Microsoft Word.app")
                val wps = listOf("/Applications/WPS Office.app", "/Applications/wpsoffice.app")
                    .map { Path.of(it) }.firstOrNull { it.exists() }
                when {
                    word.exists() -> start("open", "-a", "Microsoft Word", path.toString())
                    wps != null -> start("open", "-a", wps.fileName.toString().removeSuffix(".app"), path.toString())
                    else -> start("open", path.toString())
                }
            }
            os.contains("win") -> {
                val word = findWindowsOffice()
                val wps = if (word == null) findWindowsWps() else null
                when {
                    word != null -> start(word.toString(), path.toString())
                    wps != null -> start(wps.toString(), path.toString())
                    else -> start("cmd", "/c", "start", "", path.toString())
                }
            }
            else -> start("xdg-open", path.toString())
        }
        log.info("已调系统程序打开文档: {}", path)
    }

    /** 打开文件所在目录并选中文件(macOS Finder / Windows 资源管理器;Linux 只开目录) */
    fun reveal(path: Path) {
        when {
            os.contains("mac") -> start("open", "-R", path.toString())
            os.contains("win") -> start("explorer", "/select,${path.toAbsolutePath()}")
            else -> start("xdg-open", path.toAbsolutePath().parent.toString())
        }
        log.info("已打开文件目录: {}", path)
    }

    /** MS Word 常见安装路径(Office 2016+ 即点即用版) */
    private fun findWindowsOffice(): Path? {
        val roots = listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"))
        for (root in roots) {
            for (ver in listOf("Office16", "Office15")) {
                val exe = Path.of(root, "Microsoft Office", "root", ver, "WINWORD.EXE")
                if (exe.exists()) {
                    return exe
                }
            }
        }
        return null
    }

    /** WPS Windows 版:%LOCALAPPDATA%\Kingsoft\WPS Office\<版本>\office6\wps.exe(版本目录不固定,扫一层) */
    private fun findWindowsWps(): Path? {
        val localAppData = System.getenv("LOCALAPPDATA") ?: return null
        val base = Path.of(localAppData, "Kingsoft", "WPS Office")
        if (!base.isDirectory()) {
            return null
        }
        return try {
            Files.list(base).use { dirs ->
                dirs.filter { it.isDirectory() }
                    .map { it.resolve("office6").resolve("wps.exe") }
                    .filter { it.exists() }
                    .findFirst().orElse(null)
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun start(vararg cmd: String) {
        try {
            ProcessBuilder(*cmd).start()
        } catch (e: IOException) {
            throw IllegalStateException("调用系统程序失败: ${cmd.joinToString(" ")}: ${e.message}", e)
        }
    }
}
