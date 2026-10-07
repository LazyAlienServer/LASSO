# 排障

[目录](README.md) · [English](../en/troubleshooting.md)

先记录版本/hash、UTC时间、route/类别/requestId及脱敏状态，必要时在隔离环境重现。不附Authorization、完整Cookie、code/Token、密码/恢复码、私钥或解密队列值。不通过弱化TLS/CSRF/Origin/签名/限流制造成功。

| 现象 | 检查/处理 |
| --- | --- |
| key/config启动失败 | JDK21、非local外部占位值、PKCS12路径/可读/alias/同密码/RSA/kid、DB目标；不降级为local临时key |
| Flyway校验/迁移失败 | DB/schema/history/权限、兼容和旧迁移hash；保存故障、隔离恢复，不repair checksum/drop表绕过 |
| Discovery/回调不匹配 | Issuer协议/主机/端口/路径、代理唯一HTTPS头、精确URI/全程同主机；保留路径、验证CA，不跳过TLS |
| CAPTCHA/429 | 风险/网络预算、隐含重试/并发、审计，按提示等待，确认转发地址信任 |
| 浏览器写403 | session/admin/近期密码、CSRF、同Origin、content-type、目标生命周期；Back-channel豁免不用于浏览器API |
| Cookie丢失/循环登录 | Secure __Host、Path=/、无Domain、forwarded HTTPS/baseURL、独立业务Cookie，不换主机 |
| 必须改密/资格拒绝 | 完成真实改密，核对生命周期/reason；业务登录不能覆盖冻结/注销/held |
| refresh invalid_grant/reuse | 同会话single-flight、RT绝对到期、客户端/会话撤销；明确拒绝清登录，不重复旧RT |
| refresh暂时503 | 网络/DB/provider，返回不可用，不当资格拒绝或新密码证据 |
| 刷新后全部退出400 | 精确V15版本、存活授权/初始hint关联、同key-pepper、已登记URI/client/audience；升级前丢hint不能重建 |
| Back-channel pending/dead | receiver验签/issuer/audience/sid、TLS/timeout、worker/lease/retry/本地执行回执；修复后重放，仅查元信息 |
| BFF重启丢登录 | 默认单JVM预期，顶层SSO重入；持久task queue不保存HttpSession/授权客户端 |
| metrics401/403/404 | monitoring、管理9091而非9000、GET、独立las-sso-metrics Basic、bcrypt格式/cost/私有传输，无浏览器凭据 |
| scrape有效snapshot过期 | DB/pool/sampler/available/last_success；DB down readiness503，scrape不证明DB缓存有效 |
| 恢复后version较低 | 新epoch/权威消费方generation屏障，拒旧在途；不字典选代/随响应切代 |
| 维护console/hash失败 | 真实交互终端、精确包JAR/hash、仅服务端配置与DB目标，不重定向密码/通配符选程序 |

liveness健康不代表DB readiness，monitoring改变health端口。数据库恢复、换签名key和消费方缓存/会话屏障是分别协调的步骤。仍故障时保留有界脱敏日志/审计/类别并升级给负责人，按[运维](operations.md)/[恢复](backup-and-recovery.md)继续。
