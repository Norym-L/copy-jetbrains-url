package com.example.copyjetbrainsurl

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.awt.datatransfer.StringSelection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 复制「当前文件 + 当前光标」对应的 CLion 跳转链接到系统剪贴板。
 *
 * 生成的 URL 形如：
 * `jetbrains://clion/navigate/reference?project=MyProject&path=src%2Fmain.cpp:11:4`
 *
 * 关键点：
 * - 相对路径全程在 **VFS 空间**内计算（`VirtualFile` / `VfsUtilCore`），绝不使用
 *   `java.io.File`、`Path`、`VirtualFile.toNioPath()` 之类的本地绝对路径，
 *   因此 WSL 项目（`\\wsl$\Ubuntu\home\me\proj`）、远程开发与普通 Windows 工程
 *   得到的结果完全一致，不会把 `C:\...` 或 `/home/...` 写进 URL。
 * - `/` 编码为 `%2F`，空格编码为 `%20`。
 * - **尾部的 line / column 是 0-based**（就是 `LogicalPosition` 的原始值，不做 +1），
 *   原因见 [buildUrl] 上的注释：CLion 的 `navigate/reference` 处理器把它们直接喂给
 *   `LogicalPosition(line, column)` → `logicalPositionToOffset()`。
 */
class CopyJetbrainsUrlAction : AnAction() {

    /**
     * 本 Action 在 [update] 中读取 [CommonDataKeys.EDITOR]，这是 EDT-only 的数据，
     * 所以必须声明在当前线程更新；否则新建平台的 `ActionUpdateThread.BGT` 默认值
     * 会抛 `IncorrectOperationException`（"Access is allowed from EDT only"）。
     */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)
        // 只有在「有项目 + 有真实文件 + 有编辑器」时才显示/可用，避免在项目树、
        // 欢迎页等上下文里出现一个点了就报错的菜单项。
        e.presentation.isEnabledAndVisible =
            project != null && file != null && file.isValid && !file.isDirectory && editor != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        try {
            doActionPerformed(e)
        } catch (t: Throwable) {
            // 兜底：任何未预料的异常都转成通知，绝不把异常抛进 EDT 导致 IDE 内部报错。
            notify(project, "生成跳转链接失败：${t.message ?: t.javaClass.name}", NotificationType.ERROR)
        }
    }

    private fun doActionPerformed(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            notify(null, "没有可用的项目，无法生成跳转链接。", NotificationType.ERROR)
            return
        }

        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (file == null || !file.isValid) {
            notify(project, "当前没有打开的文件，无法生成跳转链接。", NotificationType.ERROR)
            return
        }
        if (file.isDirectory) {
            notify(project, "当前选中的是目录，请先打开一个文件。", NotificationType.ERROR)
            return
        }

        val editor: Editor = e.getData(CommonDataKeys.EDITOR)
            ?: run {
                notify(project, "当前没有活动的编辑器，请在代码编辑器中触发该操作。", NotificationType.ERROR)
                return
            }
        if (editor.caretModel.caretCount == 0) {
            notify(project, "当前编辑器没有光标，无法定位行列号。", NotificationType.ERROR)
            return
        }

        val projectName = project.name
        if (projectName.isBlank()) {
            notify(project, "项目名称为空，无法生成跳转链接。", NotificationType.ERROR)
            return
        }

        val baseDir = findProjectBaseDir(project, file)
        val relativePath = resolveRelativePath(file, baseDir)
        if (relativePath.isNullOrEmpty()) {
            notify(
                project,
                "无法计算文件相对于项目根目录的路径（文件不在项目根目录下）：${file.path}",
                NotificationType.ERROR
            )
            return
        }

        val logicalPosition = editor.caretModel.currentCaret.logicalPosition
        val url = buildUrl(projectName, relativePath, logicalPosition)

        try {
            CopyPasteManager.getInstance().setContents(StringSelection(url))
        } catch (t: Throwable) {
            notify(project, "写入剪贴板失败：${t.message ?: t.javaClass.name}", NotificationType.ERROR)
            return
        }

        notify(project, "✅ 跳转链接已复制：$url", NotificationType.INFORMATION)
    }

    /**
     * 拼接 `jetbrains://<product>/navigate/reference?project=…&path=…:line:column`。
     *
     * **行号、列号是 0-based**（直接写 `LogicalPosition.line` / `.column`，不做 +1）。
     * 这一点在 CLion 2026.2 上是反直觉的，鲸鲸用 javap 把整条消费链路挖完了：
     *
     * 1. `jetbrains://clion/navigate/reference` 由 `ProtocolNavigationCommandProcessor`
     *    这个 EP 接收，本地实现是 `MonolithProtocolNavigationProcessor`
     *    （注册在 `lib/intellij.platform.ide.impl.jar` 的 `META-INF/PlatformExtensions.xml`，
     *    `order="last"`）；
     * 2. 它调用 `MonolithProtocolNavigationProcessorKt.executeLocalNavigation("reference", …)`
     *    → `NavigatorWithinProject.navigateByPath(path)`；
     * 3. `NavigatorWithinProject$Companion.parseNavigationPath` 用正则
     *    `(?<path>[^:]+)(:(?<line>\d+))?(:(?<column>\d+))?` 拆出三个 group，
     *    然后 `LocationInFile(parseInt(line) ?: 0, parseInt(column) ?: 0)` —— **没有任何 ±1**；
     * 4. `MonolithProtocolNavigationProcessorKt.locationToOffset` 是
     *    `editor.logicalPositionToOffset(LogicalPosition(line, column))`，
     *    最后 `caretModel.moveToOffset(offset)` —— 而 `LogicalPosition` 就是 0-based。
     *
     * 所以按「人读的 1-based」写 `line + 1` / `column + 1`，落点会整体**下移一行、右移一列**。
     * 另外注意正则里 `path` 部分是 `[^:]+`：路径里（编码后）不能出现冒号，
     * 这也是这里只 encode 路径、再单独拼 `:line:column` 的原因。
     */
    private fun buildUrl(projectName: String, relativePath: String, position: LogicalPosition): String =
        buildString {
            append("jetbrains://").append(PRODUCT_CODE)
            append("/navigate/reference?project=").append(encode(projectName))
            append("&path=").append(encode(relativePath))
            append(':').append(position.line).append(':').append(position.column)
        }

    /**
     * 项目根目录。
     *
     * 这里刻意**不用** `ProjectUtil.guessProjectDir(project)`：CLion 2026.2 里的
     * `com.intellij.openapi.project.ProjectUtil` 是 Kotlin 文件门面
     * （`@file:JvmName("ProjectUtil")`，metadata `k=2`），Kotlin 侧按类名 import 会直接
     * 报 `unresolved reference: ProjectUtil`（实测 kotlinc 2.4.21 对 CLion 2026.2.4 的
     * 平台 jar），而且它是 2026.x 才普遍存在的 API。下面两个都是十几年前就稳定的 Java
     * API，行为对 CLion 项目完全等价，还顺便支持 2023.1+ 的老 IDE。
     *
     * 优先取「真正包含当前文件」的 content root（多 root 工程也能算出相对路径），
     * 否则退回第一个 content root —— 普通单根工程里它就是项目根目录。
     */
    private fun findProjectBaseDir(project: Project, file: VirtualFile): VirtualFile? {
        val roots = ProjectRootManager.getInstance(project).contentRoots
        return roots.firstOrNull { VfsUtilCore.isAncestor(it, file, false) }
            ?: roots.firstOrNull()
    }

    /**
     * 计算 [file] 相对 [baseDir] 的路径，分隔符统一为 `/`。
     *
     * 主路径走 [VfsUtilCore.getRelativePath]（两边都是 VFS 路径，WSL 的 `\\wsl$` 前缀
     * 会一起被消掉）；只有它返回 null（极端情况，例如两个不同的文件系统实现）时，
     * 才退回到「系统无关路径前缀比较」，且依然只在 VFS 路径上做字符串运算。
     */
    private fun resolveRelativePath(file: VirtualFile, baseDir: VirtualFile?): String? {
        if (baseDir == null) return null

        VfsUtilCore.getRelativePath(file, baseDir, '/')?.let { return it }

        val basePath = FileUtil.toSystemIndependentName(baseDir.path).trimEnd('/')
        val filePath = FileUtil.toSystemIndependentName(file.path)
        if (basePath.isEmpty()) return null

        val prefix = "$basePath/"
        val matches = if (SystemInfo.isWindows) {
            filePath.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)
        } else {
            filePath.startsWith(prefix)
        }
        return if (matches) filePath.substring(prefix.length) else null
    }

    /**
     * 组件级 URL 编码：`/` → `%2F`、空格 → `%20`。
     *
     * [URLEncoder.encode] 按 `application/x-www-form-urlencoded` 规则工作，本来就会把 `/`
     * 编码成 `%2F`、空格编码成 `+`；这里把 `+` 换回 `%20`。字面量 `+` 会被编码成 `%2B`，
     * 所以这个 replace 不会破坏原始内容。
     */
    private fun encode(raw: String): String =
        URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20")

    private fun notify(project: Project?, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(TITLE, content, type)
            .notify(project)
    }

    companion object {
        /** 目标 IDE 的产品码；CLion 的 jetbrains:// 协议前缀就是 `clion`。 */
        private const val PRODUCT_CODE = "clion"

        /** 必须与 plugin.xml 中 `<notificationGroup id="...">` 完全一致。 */
        private const val NOTIFICATION_GROUP_ID = "CopyJetbrainsUrl.Notification"

        private const val TITLE = "Copy JetBrains URL"
    }
}
