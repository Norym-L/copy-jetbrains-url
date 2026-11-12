# Copy JetBrains URL — CLion 插件

在 CLion 里一键复制「当前文件 + 光标位置」的跳转链接，格式固定为：

```
jetbrains://clion/navigate/reference?project=MyProject&path=src%2Fmain.cpp:11:4
```

把链接贴给同事（或 AI 助手）后，对方在 CLion 里打开就能直达同一个文件、同一行、同一列。

## 下载安装

不想自己构建的话，直接到 [Releases](https://github.com/Norym-L/copy-jetbrains-url/releases/latest)
下载 `copy-jetbrains-url-1.0.1.zip`，然后：

`Settings → Plugins → ⚙ → Install Plugin from Disk...` → 选中 zip → 重启 CLion。

要求 **CLion 2026.2+**（`since-build=262`）。装好后在编辑器里右键，菜单第一项就是
`Copy JetBrains URL`；默认不带快捷键，可在 `Settings → Keymap` 里自行绑定。

## 行为

| 步骤 | 实现 |
| --- | --- |
| 项目名 | `project.name` |
| 相对路径 | `ProjectRootManager.contentRoots` → `VfsUtilCore.getRelativePath(file, baseDir, '/')` |
| 编码 | `URLEncoder.encode(..., UTF_8)` 后把 `+` 换成 `%20`；`/` 本来就编成 `%2F` |
| 行列号 | `caretModel.currentCaret.logicalPosition` 的 `line` / `column` 原值（**0-based**，见下节） |
| 剪贴板 | `CopyPasteManager.getInstance().setContents(StringSelection(url))` |
| 提示 | 右下角 BALLOON 通知：`✅ 跳转链接已复制：{url}` |
| 触发 | 编辑器右键菜单首项（`EditorPopupMenu`，`anchor="first"`）；默认**不绑定快捷键** |

关于 WSL：整条路径计算都在 **VFS 空间**完成（`VirtualFile` / `VfsUtilCore`），从不碰
`java.io.File`、`Path`、`VirtualFile.toNioPath()`，所以 WSL 工程的 `\\wsl$\Ubuntu\home\me\proj`
前缀会和项目根一起被消掉，最终只会得到 `pans/include/pans/mutex.h` 这样的相对路径，
不会把 `C:\...` 或 `/home/...` 写进 URL。

## 为什么不使用 `ProjectUtil.guessProjectDir`

需求里给的 API 提示 `ProjectUtil.guessProjectDir(project)` 在 **CLion 2026.2 上写不出来**，
实测结论（本机 CLion 2026.2.4 / build CL-262.11879.74）：

```text
e: CopyJetbrainsUrlAction.kt:12:37 Unresolved reference 'ProjectUtil'.
```

原因不是拼写：`javap -v` 显示 `com.intellij.openapi.project.ProjectUtil` 在
`lib/intellij.platform.ide.core.jar` 里是一个 **Kotlin 文件门面**

```text
kotlin.Metadata(mv=[2,4,0], k=2, xi=48)
kotlin.jvm.JvmName(name="ProjectUtil")     // 来自 @file:JvmName("ProjectUtil")
```

`k=2` 表示「文件门面」而不是普通类，配合 `@file:JvmName` 改名后，Kotlin 侧**不能按类名
import**（这正是 `unresolved reference` 的来源，和 JDK 版本、kotlinc 版本都无关——kotlinc
2.3.20 与 2.4.21 表现完全一致，而同一 jar 里的 Java 类如 `VfsUtilCore` 一直正常）。

如果确实想用那个函数，Kotlin 侧要改成包级函数名：

```kotlin
import com.intellij.openapi.project.guessProjectDir   // 不是 ProjectUtil
...
val baseDir = project.guessProjectDir()
```

但它是 2026.x 才普遍存在的 API，写进插件就绑死了新 IDE。工程里最终用了两个十几年没变过的
Java API，行为对 CLion 项目完全等价、还兼容老 IDE：

```kotlin
val roots = ProjectRootManager.getInstance(project).contentRoots
val baseDir = roots.firstOrNull { VfsUtilCore.isAncestor(it, file, false) } ?: roots.firstOrNull()
```

## 为什么行列号是 0-based

这里踩过一个真实的坑：最初的版本按「人读的行号」写成 `line + 1` / `column + 1`，结果点开
链接后光标比预期**下移一行、右移一列**。用 `javap` 把 CLion 2026.2.4 里整条消费链路挖完
之后，结论是：`navigate/reference` 里的行列号就是 `LogicalPosition` 的原始值，**0-based**。

证据链（全部来自 `C:\Users\Norym\AppData\Local\Programs\CLion\lib` 下的 jar）：

1. `jetbrains://<product>/navigate/reference` 由 EP `protocolNavigationCommandProcessor`
   接收，本地实现在 `intellij.platform.ide.impl.jar` 的 `META-INF/PlatformExtensions.xml`：

   ```xml
   <protocolNavigationCommandProcessor
       implementation="com.intellij.navigation.MonolithProtocolNavigationProcessor"
       order="last"/>
   ```

2. `MonolithProtocolNavigationProcessorKt.executeLocalNavigation(command, parameters, …)`
   只处理 `command == "reference"`，拿到 Project 后走
   `NavigatorWithinProject.navigateByPath(path)`。
3. `NavigatorWithinProject$Companion.parseNavigationPath` 用的正则是：

   ```java
   (?<path>[^:]+)(:(?<line>\d+))?(:(?<column>\d+))?
   ```

   拆出来的数字**原样**塞进 `LocationInFile(line ?: 0, column ?: 0)`，没有任何 ±1。
4. `MonolithProtocolNavigationProcessorKt.locationToOffset` 只有一行：

   ```java
   editor.logicalPositionToOffset(new LogicalPosition(location.line, location.column))
   ```

   紧接着 `caretModel.moveToOffset(offset)` + `scrollingModel.scrollToCaret(CENTER)`。
   而 `LogicalPosition` 本身就是 0-based（第 1 行是 0，第 1 列是 0）。

所以 `:0:0` = 文件第一个字符，`:11:4` = 第 12 行第 5 列。平台自带的
`com.intellij.ide.actions.CopyTBXReferenceAction`（生成 `jetbrains://` 链接的官方实现）
写的是 `line + 1`，和本地处理器**不一致**；这个插件跟随实际消费方，用 0-based。

> 另一个细节：正则里 `path` 段是 `[^:]+`，decode 之后不能含冒号。所以这里只把路径做
> URL 编码、再单独拼 `:line:column`，而不是把行列号也塞进 path 一起编码。

如果真的遇到某个消费方按 1-based 解释，改 `buildUrl` 里的两行即可（`position.line + 1`）。

## 构建

```bash
# Windows
gradlew.bat buildPlugin

# macOS / Linux
./gradlew buildPlugin
```

产物：`build/distributions/copy-jetbrains-url-1.0.1.zip`

默认直接用**本机已安装的 CLion**做编译目标（`gradle.properties` 里的 `clionPath`），
不会下载整份 IDE 发行版。换机器时覆盖它：

```bash
gradlew.bat buildPlugin -PclionPath="C:/Program Files/JetBrains/CLion 2026.2"
# 或
set CLION_PATH=C:\Program Files\JetBrains\CLion 2026.2
```

### 工具链

- `org.jetbrains.kotlin.jvm` **2.4.21**
- `org.jetbrains.intellij.platform` **2.20.1**
- Gradle **9.6.1**（已带 `gradlew`）
- JDK 21 工具链（`jvmToolchain(21)`），CLion 自带 JBR 25 运行时可直接加载
- `sinceBuild = "262"`：插件用 2026.2 的 API 编译。要在 2023.1 等更老的 IDE 上运行，
  必须把目标平台换成那个版本重新编译（`build.gradle.kts` 里 `local(clionPath)` 改成
  `clion("2023.1.5")`），平台 API 只向前兼容、不向后兼容。

### 调试运行

```bash
gradlew.bat runIde
```

会启动一个装好本插件的沙箱 CLion。注意 `runIde` 需要在 IDE 内打开一个项目后再测试，
并且要在**编辑器里**触发（右键菜单项只在「有项目 + 有真实文件 + 有编辑器」时可见）。

## 安装到本机 CLion

1. `gradlew.bat buildPlugin`
2. CLion → `Settings/Preferences` → `Plugins` → 齿轮 ⚙ → `Install Plugin from Disk...`
3. 选 `build/distributions/copy-jetbrains-url-1.0.1.zip`，重启 IDE

## 绑定快捷键（可选）

插件默认不带快捷键（`EditorPopupMenu` 的注册与快捷键完全解耦）。两种补法：

- `Settings` → `Keymap` → 搜索 `Copy JetBrains URL` → 右键 `Add Keyboard Shortcut`
- 或改 `src/main/resources/META-INF/plugin.xml`，取消 `<keyboard-shortcut>` 的注释：
  ```xml
  <keyboard-shortcut keymap="$default" first-keystroke="ctrl alt shift C"/>
  <keyboard-shortcut keymap="Mac OS X" first-keystroke="meta alt shift C"/>
  ```
  注意 `Ctrl+Alt+Shift+C` 默认被内置的 `Copy Reference` 占用，二者会冲突（IDE 会提示）。

没绑定快捷键时，可以用 `Ctrl+Shift+A`（Find Action）搜索 `Copy JetBrains URL` 验证插件是否装载成功。

## 目录结构

```
copy-jetbrains-url/
├─ build.gradle.kts
├─ gradle.properties
├─ settings.gradle.kts
├─ gradlew / gradlew.bat / gradle/wrapper/
├─ src/main/kotlin/com/example/copyjetbrainsurl/CopyJetbrainsUrlAction.kt
└─ src/main/resources/META-INF/plugin.xml
```

## 验证记录（本机实测）

- `gradlew.bat buildPlugin` → `BUILD SUCCESSFUL`，产出
  `build/distributions/copy-jetbrains-url-1.0.1.zip`（约 8 KB，内含
  `copy-jetbrains-url/lib/copy-jetbrains-url-1.0.1.jar`）。
- `gradlew.bat verifyPluginStructure` → 通过（plugin.xml 结构校验）。
- 解包后核对打包版 `META-INF/plugin.xml`：`<idea-version since-build="262"/>`、
  `<version>1.0.1</version>`、`notificationGroup CopyJetbrainsUrl.Notification`、
  `<add-to-group group-id="EditorPopupMenu" anchor="first"/>`，且**没有** `keyboard-shortcut`。
- 用户实测反馈（1.0.0）：按 1-based 生成链接后，跳转落点比预期**下移一行**（0-based /
  1-based 语义搞反），1.0.1 已改为 0-based。
- 1.0.1 打包后用 `javap -c` 核对 `buildUrl`：只 `getfield LogicalPosition.line` /
  `.column`，字节码里没有任何 `+1`。

## 已知边界

- 链接尾部的行列号是 **0-based**（编辑器里第 1 行第 1 列 → `:0:0`），不是人读的 1-based；
  原因见「为什么行列号是 0-based」一节。
- 文件不在任何 content root 下（例如引用了项目外的头文件）会明确弹 ERROR 通知，
  而不是生成一个指向错误位置的链接。
- 多 content root 工程里，路径是相对于**包含该文件的那个 root**；如果目标机器上项目
  根目录结构不同，CLion 可能定位到别处。
- 项目名按 URL 组件编码，含空格/中文的工程名也能正确传递。
- `%2F` 是给 URL 传输用的；CLion 收到 `jetbrains://` 链接后会先 decode 回 `/` 再定位文件。
