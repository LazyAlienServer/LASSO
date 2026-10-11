# 验收：包完整性与开放判据

[目录](README.md) · [English](../en/acceptance.md) · [发行说明](../../RELEASE-NOTES.zh-CN.md)

## 1. 当前包可直接查的证据

包根执行 `python scripts/verify-package.py`，预期exit0。

| 文件 | 查什么 |
| --- | --- |
| `provenance/release-manifest.json` | 版本/source commit/tree、制品及文件hash |
| `provenance/SHA256SUMS` | 文件完整性；不等签名 |
| `provenance/verification-summary.json` | 实际命令/工具/时间、三模块tests、扫描范围/日期/复核 |
| `provenance/dependencies/` | 三锁/catalog/Wrapper、nestedJAR清单 |
| `database/migration-manifest.json` | JAR内嵌迁移hash |
| `source/` | 精确干净gitarchive；历史日期记录 |

校验器拒缺失/多文件、hash/版本/来源/迁移不一致、包外链接。源码和程序以本包记录commit为准。

## 2. 证据如何使用

- 全量数据库测试须fail/error/skip=0；以实际版本报告为准。
- opaqueJAR扫描可识别零依赖，故依赖扫描需解包/锁覆盖。
- 0发现不证明无漏洞；查看数据库日期、识别包数和人工复核。
- 历史TLS/restore/Grafana/5分钟短AT负载不自动算本版本重跑。
- 已验fixture证书/WindowsUID不能代公共CA/Linux保管/小时级目标容量。
- 文件排序/时间戳固定，不宣称独立双构建器字节级重现。

## 3. SSO账号站开放前

| 范围 | 通过条件 | 记录 |
| --- | --- | --- |
| 输入/启动 | 正式issuer/负责人/独立secret/key、production、不混local | 环境与配置修订 |
| 管理员 | 离线bootstrap→首登强改、码保管、reset可执行 | 操作员/UUID/audit引用 |
| DB | 空库/代表旧schema迁移、权限/索引时间空间、DB故障恢复 | 版本/schema/耗时 |
| TLS/网络 | 公共CA/续期、伪头拒、SecureCookie、不可直连app/DB/metrics | 外部探针 |
| 生命周期 | 冻结/注销/hold/cancel/执行、人工恢复、近期密码门禁 | 非秘密结果 |
| 监控 | 私有认证采集、真实firing/resolved、disk/backup/clock | 真实收件人确认 |
| 备份/恢复 | 异机加密可恢复、新key/epoch/旧凭据拒 | RPO/RTO实测 |
| 容量/安全 | 目标ramp/soak/history、当前镜像/JDK/代理扫描及DAST | 限额和处置 |
| 开放 | 最终候选/回退/有限流量/首运维周期 | 负责人签收 |

## 4. 每个消费方上线时追加

| 检查 | 通过条件 |
| --- | --- |
| OIDC | 两客户端、Code/PKCE/state/nonce、稳定UUID/consent |
| refresh/logout | same-session合并、reuse拒、当前/全部退出、重复迟到Back-channel |
| 本地状态 | 重启丢登录符合初期方案、已接受撤销恢复，并明确处理提交前及远端不确定结果 |
| 近期密码 | 发起本地session/待操作绑定15分钟、refresh不推进auth_time |
| 资格 | 一分钟内观察拒绝、旧epoch/cache/inflight屏障 |

账号站首发可无业务客户端；SSO通过不替消费方实现业务授权。

## 记录模板

版本/hash、日期、目标环境、负责人、命令、预期/实际、例外/后续。结果存脱敏摘要，不存密码/Token/私钥/恢复码。

## V01 软件包验收流程

**预期：** 精确软件包来源/完整性及受支持隔离流程有实际证据，未执行的目标检查明确保留。

**失败：** 保存失败证据并修正候选；历史或syntax-only结果不能代本版本缺失的实际执行。

1. 独立解压到无额外文件的新目录，核可信来源的ZIP hash；运行verify-package.py，检查版本/kind/source/artifacts/API及内嵌migration，exit0。
2. 读verification-summary实际时间/数量/skip/scan DB/date/scope。历史报告或仅三个聚焦case不能称本次全量；software-release也不等productionApproved。
3. 按已选N01–N06、内置Docker八步或J01在隔离目标安装，用U03/U07、A01–A10和I03–I05支持路径完成本版实际操作；破坏性流程用专用fixture。
4. 若声明upgrade/backup支持，以代表旧数据演练O03/B02及key/epoch/consumer屏障；记录command/status/read-back及未执行平台限制。
5. 文档/命令解析不算实际deployment，OpenAPI格式不算全部OIDC合规，SDK编译不算LASLOG登录。负责人核软件支持范围/缺陷/例外后保存不可变交付。

## V02 目标开放与消费方验收流程

**预期：** 精确候选满足目标约定输入、运维检查及已上线消费方范围，扩量前获负责人签收。

**失败：** 阻断的目标或消费方检查保持相关访问关闭，按记录修复/回退并重验。

1. 完成§3输入/网络/秘密/DB，真实publicCA/外部端口检查，明确流量及资源界限。
2. M01–M04实际采集/通知、B01/B02恢复及RPO/RTO、T01故障恢复，证明值班人可执行；账号站可无业务client。
3. 各实际consumer完成I01–I06及§4负例；身份/资格/业务门禁不因SSO通过自动完成。
4. 最终候选按本版hash安装、维护窗口内冒烟，定义停止扩量/回退条件，再有限流量观察。首轮定时backup/通知及增长复查是开放后责任，不提前伪勾。
5. 写软件版本/环境/开放范围/owner/实际容量/剩余限制与支持渠道，负责人确认后才扩量；仍有阻断失败保持关闭。
