# 更新日志

## [1.0.1] - 2026-11-12

首个可用版本，修掉了一个真实问题：生成的链接点开以后，光标比预期**下移一行、右移一列**。

根因不在路径或编码，而是 `navigate/reference` 里的行列号 base：

- CLion 处理 `jetbrains://clion/navigate/reference` 的链路是
  `ProtocolNavigationCommandProcessor` → `MonolithProtocolNavigationProcessor`
  → `NavigatorWithinProject.parseNavigationPath`（正则
  `(?<path>[^:]+)(:(?<line>\d+))?(:(?<column>\d+))?`）
  → `LocationInFile(line, column)`（**不做任何 ±1**）
  → `editor.logicalPositionToOffset(LogicalPosition(line, column))` → `caretModel.moveToOffset(...)`。
- `LogicalPosition` 是 **0-based**，所以 URL 里要写 `LogicalPosition` 的原值；按人读的
  1-based 写 `line + 1` 就会整体下移一行。

1.0.1 改为 0-based（`position.line` / `position.column`），并用 `javap -c` 核对过打包产物里
没有任何 `+1`。

### 相关说明

- 平台自带的 `CopyTBXReferenceAction`（生成 `jetbrains://` 链接的官方实现）写的是
  `line + 1`，和本地消费方不一致，容易误导。
- 链接格式：`jetbrains://clion/navigate/reference?project={projectName}&path={path}:{line}:{column}`，
  路径相对项目根目录（VFS 计算，WSL / 远程项目同样适用），`/` → `%2F`，空格 → `%20`。
- 文件名里有冒号会让 path 段的正则 `[^:]+` 失配，属已知边界。

[1.0.1]: https://github.com/Norym-L/copy-jetbrains-url/releases/tag/v1.0.1
