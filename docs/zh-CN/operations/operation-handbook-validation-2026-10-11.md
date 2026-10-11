# 操作手册验证 — 2026-10-11

[English](../../operations/operation-handbook-validation-2026-10-11.md) · [操作总目录](../../../release/_source/docs/zh-CN/operation-index.md) · [阅读入口](handbook-reading-guide.md) · [API/发行状态](../implementation/api-release-readiness-2026-10-11.md)

## 来源与范围

验证覆盖`codex/fix-sso-quality-review`上当前未提交的手册/API/发行工具工作，HEAD为`297cdfc0dcf06a9db421064d5328b7bb71f07859`。单凭HEAD不能定位这些修改。临时`0.1.0-rc.1`模板渲染是工具fixture，不表示已选候选、构建应用或生成发行包。已有开发归档保持原字节。

中英包内源码各有**17页手册、57个编号流程**：U01–U11、A01–A10、F01–F10、I01–I06、O01–O04、B01–B02、M01–M04、H01–H02、R01–R04、J01、T01、V01–V02。每个编号流程至少三项有序动作，有明确预期与失败处理；页面共用前提及关联流程供应认证/部署上下文。

## 实现核对与修正

- 对照账号、恢复、会话及管理员脚本/模板核按钮和请求顺序。管理对话框先验证自己的密码再写入；恢复码近期认证后要再点生成，认证成功不自动换批。
- 对照controller/service核Cookie/CSRF/同源、实际状态码、适用幂等key、一次性输出及当前sid/全用户退出。历史名当前一律拒绝，包含本人旧名；冲突UI提示不建立复用能力。
- 区分人工恢复凭据和bootstrap临时密码。hold保原注销期限，release不重启30天。消费方证据来自已验证密码认证，不是本地登录成功、refresh或dialog。
- 核参考V1/V2列及维护script/context真实参数。queue查询只读事务、10秒statement/500ms锁、100行样本及独立未完成总数；换key在全部producer/worker停止后复查排空。
- 备份/还原/升级/TLS/systemd代码失败即停。备份限制权限、`noclobber`防覆盖、每份dump独立list。还原等待PG健康，先还原再迁移；migrator使用`--no-owner --no-acl --exit-on-error --single-transaction`。
- refresh远端未知结果如实保留。旧A撤销200不证明未知B/family清理；历史RT补偿是另行选择的可选增强。

## 渲染、覆盖与解析

使用真实`copy_templates()`在新临时目录渲染，通过：

| 检查 | 结果 |
| --- | --- |
| 双语文件/流程编号 | 各17页/57流程，集合一致 |
| API流程目录 | 每种语言均覆盖OpenAPI全部59 operationId |
| 本地Markdown链接 | 415条有效，目标均在渲染目录内 |
| 发行占位符 | VERSION/DELIVERY_EN/DELIVERY_ZH全部替换 |
| Bash/PowerShell/JavaScript | 50块`bash -n`、16块Windows PowerShell5.1 parser、2块Node模块语法通过 |
| 失败停止行为 | 双语dump、restore、升级migration、Nginx test、systemd verify共10项stub检查 |

失败停止检查用返回19的stub替换外部操作，验证后续命令未运行，没有执行部署。临时验证器递归错误已修正，最终检查通过且fixture容器已清理。

## 隔离PostgreSQL演练

使用两个可丢弃、无网络的PostgreSQL **18.4** fixture容器，不发布DB端口，采用实际`postgres-init.sh`角色/default grants脚本。临时凭据及dump字节不显示、不提交。

1. 非秘密小表/sequence fixture通过backup角色custom dump/list及migrator单事务还原、对象所有者及backup角色读检查。
2. migrator依序执行实际**V1–V16 SQL脚本**并加入非秘密哨兵表。**28张public表**custom dump/list/restore到另一空应用库后数量一致，哨兵数据保留。
3. backup角色执行备份手册全部**四个双语应用只读SQL块**；独立reference库执行参考V1/V2后运行双语queue检查块。
4. `history-status.sql`用backup角色在还原前后均通过，还原对象默认grants可用。
5. 再次还原到非空目标被拒绝，原哨兵数量不变，验证空目标/失败即停指导。

这些直接SQL检查**未运行**Flyway metadata/checksum校验、本版本JAR、交互epoch/key维护、真实账号数据、旧Token拒绝、浏览器、加密/异机保管或RPO/RTO测量。它们需B02/V01/V02运行时/目标证据。fixture容器/卷、临时dump/日志及生成凭据已清理。

## 辅助检查与边界

`python -B -m unittest discover -s scripts/tests -v`的**9项**发行工具测试通过。仓库检查通过：**579个UTF-8/text候选、1701条本地链接、1条声明兄弟链接、三依赖锁、Wrapper checksum及V1–V16**。`git diff --check`通过，换行转换warning不等空白错误。

Gitleaks **8.30.1**以`dir --redact=100`扫描**595个精确跟踪/未忽略源码candidate**，包括本轮手册及双语记录：**exit0、0发现**。路径/hash清单、脱敏报告及扫描日志私有保留。ignored历史包/build不在范围内，不代最终artifact/history/dependency/image扫描。记录本地日期为2026-10-11，首次完整源码扫描完成于UTC2026-10-10T20:29:50Z；加入本脱敏结果后再次扫描，保存包含本记录的最终快照。

先前843项dirty工作区全量JVM build保日期事实；本轮手册未重跑或冒充干净RC构建。没有新真实RC/stable归档或目标部署。Linux systemd/UID保管、publicCA续期、真实collector/Grafana/通知、代表升级/恢复及实际consumer接入须环境证据。既有已停止本地实例及历史交付数据保留。
