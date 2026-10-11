# 排障速查

[目录](README.md) · [English](../en/troubleshooting.md) · [Docker步骤](docker-deployment.md)

外部Nginx使用[N02/N05](host-nginx-deployment.md) helper，主机Nginx日志单独查；`dc logs proxy`仅内置替代。SSO `Restarting (1)`看最新启动异常，不在退出循环仅重试readiness curl。

## 先判断故障层

```text
无法打开 → proxy健康？ → sso健康？ → postgres健康？
能打开但无法登录 → Cookie / CSRF / 风险 / 凭据状态
只有业务站失败 → 客户端配置 / refresh / Back-channel / 资格缓存
只有指标异常 → 管理9091 / 采集认证 / snapshot
```

## 1. Docker第一组检查

**位置：包根。**

```sh
dc() { docker compose --env-file /etc/las-sso/deployment.env -f "$PWD/deploy/docker/compose.yml" "$@"; }
dc ps
dc logs --tail 100 postgres
dc logs --tail 100 sso
dc logs --tail 100 proxy
```

在私有终端查看；对外只提供时间、版本、requestId和稳定类别，不发送原始认证日志。

| 症状 | 首查 | 下一步 |
| --- | --- | --- |
| 输入校验失败 | env空值/host-issuer-port/immutableID/限额/shell覆盖 | 重跑[部署第4步](docker-deployment.md) |
| `image not found` | pull_policy:never，指定ID未加载 | docker image inspect /加载正确镜像 |
| postgres非healthy | init错误、PG UID读DB秘密、存储/权限 | 私有PG日志；现有卷不重跑init |
| sso未启动 | DB/key/config/admin门禁 | 查看下面稳定类别表 |
| proxy非healthy/重启 | TLS UID101、模板、上游sso | 私有proxy日志、证书/hostname |
| 502/504 | sso存活/DB/pool，proxy日志 | 先readiness再重启策略 |

### 直接查私有健康

```sh
dc exec -T sso curl --silent --output /dev/null --write-out '%{http_code}\n' http://127.0.0.1:9091/actuator/health/readiness
dc exec -T sso curl --silent --output /dev/null --write-out '%{http_code}\n' http://127.0.0.1:9091/actuator/health/liveness
```

| readiness / liveness | 含义 |
| --- | --- |
| 200 / 200 | 进程和DB就绪，不等所有目标验收通过 |
| 503 / 200 | DB/连接池/就绪故障，先修连接，避免重启风暴 |
| 无法连接 | 进程/监听/启动失败 |

## 2. 启动门禁类别

| 类别 / 信息 | 含义 | 处理 |
| --- | --- | --- |
| `PRODUCTION_PROFILE_REQUIRED` / `PRODUCTION_PROFILE_CONFLICT` | 非开发未production或混local/test | 使用production，核来源 |
| `PRODUCTION_ISSUER_INVALID` / `PRODUCTION_ISSUER_CONTEXT_MISMATCH` | 地址/路径不符 | 正式HTTPS、canonical host/port、根路径拓扑 |
| `PRODUCTION_SECRET_*` | 缺失/过短/复用/开发值 | 核受保护文件，别打印值 |
| `Unable to load persistent signing key` | path/UID/alias/password/RSA不符 | 核文件访问和同密码，别换临时key |
| `PRODUCTION_ADMIN_INITIALIZATION_REQUIRED` | 无唯一可用管理员/临时密码到期 | 离线bootstrap或当前管理员reset |
| `PRODUCTION_SCHEMA_INCOMPATIBLE` | Flyway状态/checksum/future不兼容 | 精确binary/schema；不repair checksum |
| DB permission denied | runtime尚需迁移/角色或授权不符 | migrator离线迁移，重跑权限SQL |
| `INTERACTIVE_CONSOLE_REQUIRED` | 密码维护无TTY | 真实终端、--no-TTY=false，不用-T |
| PRODUCTION_KEY_ID_INVALID | 非法公开kid/开发标识 | N02合法稳定ID，区别alias；env改后recreate |
| PRODUCTION_MONITORING_CREDENTIAL_INVALID | 明文/缺前缀/htpasswd整行/cost/引号/空格 | 监控教程`{bcrypt}` cost10–14，刷新挂载及collector原密码 |

## 3. 浏览器与账号

| 症状 | 检查 | 正确处理 |
| --- | --- | --- |
| 循环登录/Cookie丢失 | Secure __Host、Path=/、无Domain、HTTPS、同一hostname | 核代理头/站点Cookie，不换主机 |
| 写操作403 | session/admin/近期密码、CSRF、Origin、Content-Type | 获取新CSRF，按页面重新认证 |
| CAPTCHA/429 | 风险预算、隐含重试、真实IP信任 | 按Retry-After等候，不关闭限流 |
| 必须改密 | bootstrap临时密码/凭据状态 | 完成U03；人工恢复凭据走U08两步恢复，不作为临时密码登录 |
| 资格拒绝 | 冻结/注销pending-held/凭据状态 | 看reason，不用业务登录覆盖 |
| 一次性输出不见 | 已关闭/离页清理 | 不能重开找回；使用受支持重发/轮换 |

## 4. OIDC、刷新与退出

| 症状 | 首查 | 处理 |
| --- | --- | --- |
| Discovery/callback不符 | issuer协议/host/port/path、精确URI、CA | 保留完整路径；不跳TLS校验 |
| refresh invalid_grant/reuse | same-session single-flight、family绝对到期/撤销 | 明确拒绝清登录，不重用旧RT |
| refresh暂时503 | DB/provider/network | 返回独立不可用，非封禁/密码证据 |
| 刷新后全局退出400 | V15+、存活初始hint、同pepper、client/URI | 不重建升级前丢hint |
| Back-channel pending/dead | TLS/receiver验签、lease/retry、本地执行回执 | 修接收者；只查元信息，不伪标成功 |
| BFF重启丢登录 | 默认单JVM | 预期行为；持久queue不保存HttpSession |
| 恢复后version变低 | 新epoch/本地generation/旧inflight | 在同epoch内比version，拒旧代回复 |

资格与密码边界详见[接入](external-integration.md)；参考queue死信流程见[参考维护](reference-revocation.md)。

## 5. 监控与恢复

| 症状 | 检查 | 处理 |
| --- | --- | --- |
| metrics401 | 专用Basic用户名/密码、bcrypt格式/cost | 采集器凭据，不用浏览器/OIDC密码 |
| metrics403/404 | monitoring/GET/管理9091，非public9000 | 核私有地址；公网actuator404是预期 |
| scrape有效、snapshot旧 | DB/pool/sampler/last_success | 旧gauge无效，见[监控](monitoring.md) |
| 表增长 | eligible/忙/oversized/audit | 见[历史清理](history-retention.md) |
| 恢复command拒epoch | 目标/当前epoch/重复执行 | 不改计数器绕过，见[恢复](backup-and-recovery.md) |

## 给支持者的最小记录

版本与hash、UTC时间、route、稳定错误类别、requestId、readiness/liveness状态、操作结果。

不得附密码/恢复码/code/Token/完整Cookie/私钥/解密队列值；不关闭TLS/CSRF/Origin/验签来制造成功。

## T01 按层定位并核修复结果

**预期：** 原故障修复，原路径、负例、readiness及通知通过，并记录配置修订。

**失败：** 问题仍在时保失败类别并将最小证据交负责人，受保护访问保持关闭。

1. 先记UTC、版本/image/source、症状（HTTP/path、启动、账号、业务站或metric），避免先restart丢现场；使用§1dc确认目标。
2. 外部不能访问先proxy状态/证书/host/端口；proxy502再看SSOreadiness；readiness503/liveness200查DB/pool，不无限重启应用。
3. 只有写403按认证/role/近期密码/CSRF/Origin分别核；只有业务站失败对接方核client/URI/Token会话所有权，不能改SSO账号状态“试一下”。
4. 私有bounded logs及read-back/audit核结果；一次性输出缺失先核提交，按U07/A04/A08受支持重发，不取旧明文。
5. 选最小修复、记外配置差异，按O03/O04/M01等对应操作应用；只在隔离测试复现DB/网络fault，不临时关安全检查。
6. 原失败路径再次验证成功、负例仍拒、readiness/通知正常；记录恢复时间/剩余风险/下次复查。不只看container up。

**失败仍在：** 保受保护访问拒绝，把最小记录交模块负责人；SSO outage不等账号封禁，也不授权自动踢既有游戏。
