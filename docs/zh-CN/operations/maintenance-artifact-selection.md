# 管理员维护使用精确制品

- 状态：2026-10-05制品/启动边界已验，2026-10-06真实交互空库bootstrap/firstchange/reset已验；生产/晋升/其他终端独立。
- [English](../../operations/maintenance-artifact-selection.md)。

bootstrap-admin.ps1 与 reset-admin-password.ps1 新增 -JarPath 和可选 -ExpectedSha256。原调用保留固定 0.1.0-SNAPSHOT 默认，不自动选最新 JAR。指定交付应提供精确路径和可信 manifest 摘要：

```powershell
.\scripts\bootstrap-admin.ps1 -Username InitialAdmin -Profile production -JarPath $ServerJar -ExpectedSha256 $ServerJarSha256
.\scripts\bootstrap-admin.ps1 -UserId $ExistingUserId -Profile production -JarPath $ServerJar -ExpectedSha256 $ServerJarSha256
.\scripts\reset-admin-password.ps1 -Profile production -JarPath $ServerJar -ExpectedSha256 $ServerJarSha256
```

相对路径以调用方当前目录为准，默认以脚本目录为准；literal filesystem 路径支持空格。公共 resolver 在 Java 启动前拒绝缺失/非 JAR/摘要不符，输出制品路径/SHA-256，入口脚本传递 Java 退出码。摘要仅证明与传入可信值一致，不等发布签名。密码仍由应用隐藏控制台读取，无密码参数或自动 bootstrap。

外部 9 个 PowerShell 用例实际执行入口：username/UUID bootstrap、reset、空格路径、摘要匹配/不符/格式、缺失文件、退出传播。Java 仅在启动边界替换，不算真实控制台/数据库证据。脚本 fixture 初误解 PowerShell -Command 退出行为，以明确 wrapper 校准；无管理员或生产库修改。

随后两实际脚本以最终.3 JAR/hash、仅一次性PostgreSQL验收库、无console启动；均nonzero/INTERACTIVE_CONSOLE_REQUIRED，users/credentialsversion/audit/session/Authorization/epoch快照不变。这补真实Java无console拒绝，非成功隐藏密码输入/bootstrap/reset演练。

后续[管理员维护验收](administrator-maintenance-acceptance.md)／[English](../../operations/administrator-maintenance-acceptance.md)已实际scripts/JARhash/WindowsPTY成功hiddenbootstrap/offlinereset及browserfirstchange/recoverydownload/旧状态拒。上无console是当阶段边界，非最新全局状态。
