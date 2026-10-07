# 运维

[目录](README.md) · [English](../en/operations.md)

## 日常检查

观察含数据库的 readiness、私有采集/snapshot 新鲜度、登录/管理员失败、refresh/reuse、退出积压/年龄/死信、连接池/存储和证书有效期。通过 `/admin/audit` 检查审计/安全事件，按时间/requestId/action 关联，避免 Token/Cookie/密码数据。CA 续期、备份有效性、真实通知接收与升级响应由运维负责。见[监控](monitoring.md)。

保留发行清单：版本、核验 SHA-256、源码 commit、不含值的配置修订、JDK/PostgreSQL/代理版本及迁移历史。重启不重新生成秘密。SSO 暂时不可用不是账号封禁，消费方应区分拒绝与临时故障。

## 维护命令

从包根执行，提供同一受保护数据库/密钥环境及仅服务端外部配置。停止 Web 写入者、关闭公网入口，先验证包，再读取精确值：

```powershell
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:/etc/las-sso/server.properties'
$m = Get-Content -LiteralPath './provenance/release-manifest.json' -Raw | ConvertFrom-Json
$j = Join-Path $PWD $m.artifacts.server.path
$h = $m.artifacts.server.sha256
./scripts/reset-admin-password.ps1 -Profile production -JarPath $j -ExpectedSha256 $h
```

Reset 隐藏读取密码、针对当前管理员，并非任意用户重置 API。Bootstrap 的 `-Username`/`-UserId` 见[部署](deployment.md)。恢复另需 ExpectedEpoch/原因并明确关闭 scheduling，见[恢复](backup-and-recovery.md)。包内维护在 Java 启动前拒绝缺少/错误摘要，不使用通配符或“最新”程序。私密保管恢复输出并审计操作者/原因/结果。

## 升级与回退

1. 核验独立候选版本及其源码/测试/扫描覆盖；在隔离副本演练空库与旧版本升级。备份数据库并独立记录密钥/pepper/配置保管。
2. 排空入口、停止写入者/worker，记录 epoch 和 Flyway，安装新不可变目录。外部配置独立版本管理且不暴露值。启动单个 SSO，验证迁移/readiness/TLS/浏览器/消费方刷新退出，再启消费方、逐步开放。
3. 记录部署时间、摘要与验收。失败时继续关闭入口。旧二进制不能未经兼容性验证直接操作新 schema。恢复旧快照必须执行完整 epoch/新签名密钥/消费方屏障，单纯回退文件不够。

关注 V15 hint/history 增长与 authorization 删除、audit/outbox/security 保留和数据库膨胀。没有交付独立 history TTL/压缩器或完整容量策略，不按猜测删除数据。并发/outbox 应受控处理，不能随意改 processed 标志。

## 参考持久撤销

默认 BFF 重启丢失本地登录。可选 `durable-revocation` 使用独立数据库、32-byte base64 AES 密钥，key-id 符合 `[A-Za-z0-9._-]{1,64}`；schema/配置见[接入](external-integration.md)。队列与密钥分别保护备份。仅已提交任务重启恢复，提交前中断仍可能遗留远程授权。死信需调查后授权处理，无运维 HTTP 重放 API。pending 任务仍依赖当前 key 时不能直接替换；参考实现加载一个 key，不是多密钥环。它不持久化登录，也不等于完整多实例撤销。

## 故障处置

凭据/密钥泄露时按范围关闭入口，通过支持的管理操作撤销会话/授权/客户端，在验证过的连续性方案下替换秘密，保留脱敏审计。签名密钥泄露/恢复协调全部消费方和旧 kid 缓存屏障。DB down 时 readiness503、liveness200，应恢复连接而非形成重启风暴。疑似攻击查看固定低基数指标和风险/审计结果，不通过关闭 CSRF/限流消除症状。人工恢复联系现有 QQ 群；仅确定联系渠道不代表已核实申请人或完成整套人工恢复流程。
