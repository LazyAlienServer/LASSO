# SSO 工程规范基线 — 2026-10-07

- [English](../../implementation/engineering-baseline-2026-10-07.md)／[协作规范](../../../CONTRIBUTING.md)／[仓库入口](../../../README.md)。
- 范围：代码库组织、构建校验规范和本地Git历史；身份、权限和部署政策仍以现有契约为准。

## 本地提交历史

机械整理前先保存原`.20261006.7`已验证功能和记录：

| 提交 | 用途 |
| --- | --- |
| `1c287e9` | 安全/客户端/运行基线，V13–V15迁移、测试和部署样例 |
| `f1f1093` | 契约、双语ADR、历史验收/交付manifest与上线计划 |
| `09700e4` | 工程规则、仓库校验、固定CI输入和共享Gradle约定 |
| `b21304d` | 独立Java机械格式化，不混实质行为变更 |

这些是`codex/fix-sso-quality-review`分支的本地提交，不表示已推送、托管发布或生产部署。历史manifest保原base/worktree fingerprint/hash；当时未提交的描述对应当时执行，不再代表当前仓库状态。格式化后源码字节已改变，不可用旧`.7`ID发布新组合。

## 组织与规则

- 根README明确活跃三模块；服务端/Starter README改为当前用法和历史证据链接，参考BFF新增独立入口。
- CONTRIBUTING定义模块/包职责、参数化持久化、Flyway只追加、专项/全量验证、显式暂存和双语文档维护。
- EditorConfig定义UTF-8/缩进，Git attributes定义源码LF/批处理CRLF，Markdown双空格硬换行仍允许；Unix Wrapper在Git中具有可执行权限。
- ignore规则覆盖构建/IDE/Python输出、本地环境和密钥文件、既有docs.zip与工作站init脚本。文件本机保留，不因忽略就认定秘密/生产配置安全。
- 固定Spotless8.10.3、Google Java Format1.28.0 AOSP开发工具；三模块共享Java21/release21、UTF-8、JUnit Platform、格式及稳定归档顺序/时间戳。格式化不改应用依赖/三锁和版本SQL。
- `-PrequireDatabaseTests=true`作为显式Test输入，XML有跳过即失败；日常专项可允许Docker相关跳过，但不能计数据库验收。
- `scripts/verify.py`仅Python3.10标准库，核仓库卫生/UTF8/JSON/链接/三锁/Wrapperchecksum/迁移序号及三模块JUnit总数。一个声明LASLOG兄弟文档链接只作背景，不作构建依赖。
- Actions固定SHA/只读权限，Linux完整数据库必需构建，Windows仓库和PowerShell语法；只上传数量JSON，不上传可能含秘密的日志/XML原内容。

## 验证

仓库根目录：

```powershell
python scripts/verify.py repository
python scripts/verify.py tests --require-no-skips
git diff --check
```

从`las-sso-server/`：

```powershell
.\gradlew.bat build :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication -PrequireDatabaseTests=true --rerun-tasks --no-daemon --console=plain
```

最终本机Java21/Docker强制6m38s/28tasks执行，**505server193starter37reference BFF=735**，0失败/错误/跳过，三格式检查及实际PG通过。既有Testcontainers/Hikari停止警告仍fixture范围，不影响0skip或证明生产容量。

200个改动Java解析后的类型声明、字面量、控制流与格式化前一致（注释/import排序和移除不纳比较）。实际Gradle开关对外部临时Disabled用例返回预期nonzero；报告校验另拒skip/failure/error/empty。四维护PowerShell脚本语法通过。候选源码秘密模式扫描100%redact零finding，只是规则覆盖，不是所有秘密保证。

本任务没有运行GitHub-hosted CI，只交配置，不报远端绿。工程构建未对格式化快照重跑全部旧browser/TLS/restore/load演练；原`.7`JARhash核验不变。新本机SNAPSHOT输出不等不可变生产发行。

## 下一步

按[上线计划](production-launch-plan.md)优先做生产启动门禁盘点和测试，同时收目标环境输入。正式发行仍需新辨识source/artifact和对应candidate目标/消费方/安全证据；本地提交完成源码追踪，不等P16完整生产交付。
