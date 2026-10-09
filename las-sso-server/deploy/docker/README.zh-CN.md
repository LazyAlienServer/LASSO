# 单机 Docker Compose 部署

[English](README.md)

本方案实现已确认首发：SSO 账号站、一个 SSO 实例、PostgreSQL、同机 Nginx，不接 CDN。LASLOG/AlienCommon 和参考 BFF 在各自上线时独立登记验收。正式域名、主机容量、流量、备份/RPO/RTO、通知渠道由运维填写；本地 fixture 限额不代表生产选型。

## 1. 不可变镜像与外部输入

在已审查 Linux 主机使用 Docker Engine/Compose。本地检查为 Docker Desktop 的 Linux/amd64 容器、Compose v5.1.2，不证明目标 Linux 发行版、防火墙或其他 CPU 架构。

服务端 Dockerfile 只消费精确可执行 JAR，不编译源码、不复制工作区。干净仓库先通过 `scripts/package_release.py build <新版本>` 产生严格构建证据，再从仓库根执行（下列工具路径均相对根目录）：

```text
python scripts/build_docker_image.py --jar <精确server.jar> --expected-sha256 <可信64位摘要> --version <新版本> --tag <本地标签> --build-evidence <精确build-evidence.json>
python scripts/build_docker_infrastructure.py --postgres-tag <数据库标签> --proxy-tag <代理标签> --output-dir <已存在外部父目录/新证据目录>
```

独立解压包先在包根运行 `python scripts/verify-package.py`，从 manifest 取 server 路径/摘要/版本；将 `--build-evidence` 替换为 `--release-manifest provenance/release-manifest.json --output-dir <已存在外部父目录/镜像证据>`。两类镜像工具在已验证独立包内无需 Git。staging/receipt 放包外，保持包不可变。`--allow-dirty-fixture` 明确为未验证开发来源，不能把任意 JAR 标为当前干净提交。

PostgreSQL/Nginx 用 digest 固定上游基础镜像，在构建时应用 Alpine 安全更新。PostgreSQL 在原 entrypoint 路径用 Alpine `su-exec` 替代上游 Go `gosu`。软件源可变，须保存最终 image ID、包清单、receipt 和当前扫描；再次构建产生新字节。运行启动时不更新软件包。经受控介质保存/传输精确镜像，核归档摘要及加载后的 ID。`deployment.env` 只填 `sha256:<image-ID>` 或 `repository@sha256:<digest>`；tag 只是构建句柄。`pull_policy: never` 要求已加载指定镜像。

将 `deployment.env.example` 复制到源码/包外并补全。只存非秘密值。当前根路径拓扑要求 `LAS_SSO_ISSUER` 等于 `https://<LAS_SSO_HOST>[:外部端口]`，不设置 servlet 前缀。预发布/生产用独立 project、秘密、数据位置。测量审查资源限制，给数据库/JVM原生内存/代理/采集器及主机留余量；EXPOSE 元数据不发布宿主机端口。

## 2. 文件、权限和网络

创建受保护外部配置、秘密、PKCS12、TLS位置，复制 `server.properties.example` 为 `server.properties`。不能直接使用 host/systemd 的 loopback 配置：容器内9000/9091监听 `0.0.0.0`，由网络成员及无宿主机映射限制访问。

| 外部秘密文件名 | 使用者 |
| --- | --- |
| `db-admin-password` | PostgreSQL 初始化/管理员保管 |
| `db-runtime-password` | SSO 运行数据库账号 |
| `db-migrator-password` | 离线迁移/维护 |
| `db-backup-password` | 只读备份账号 |
| `signing-password` | 持久PKCS12，私钥同密码 |
| `recovery-pepper`、`transient-pepper`、`captcha-secret` | 三个独立持久高熵秘密 |
| `monitoring-hash` | `{bcrypt}` cost10–14哈希；独立明文密码仅采集器保管 |

用 CSPRNG/受控秘密机制分别生成；DB/key密码至少12 UTF-8字节，pepper/CAPTCHA至少32，长度不等熵。秘密不进入 env、buildarg、shell tracing、镜像、源码、receipt 或密码命令参数。init 拒短/复用数据库角色密码。已有卷不重跑初始化，修改密码文件不等于轮换已存在数据库角色。

普通 Compose secrets 是只读宿主机文件挂载，不是加密秘密库，所有者/权限由运维落实。Linux app配置/key/秘密仅UID10001可读（例如文件归UID10001、0400、父目录受保护）；TLS key仅UID101可读；DB秘密按初始化/维护所有者控制。不能用私钥全员可读解决权限错误。实测容器可读、其他主机用户不可读。Docker socket/group访问等于主机管理员权限；Windows挂载不证明Linux UID/ACL。

Nginx UID101、SSO UID10001只读/dropALL/no-new-privileges、有界 `/tmp`；PostgreSQL官方入口初始化卷仍需root，然后降至数据库用户。仅代理发布80/443或审查端口/接口。DB只有internal数据库网络；maintenance仅数据库网；proxy仅application网；management不代理。加入SSO网络的容器是可信基础设施，不应放任意工作负载。代理清其他Forwarded并覆盖canonical host/proto/外部port/client IP；后续CDN/多跳需独立信任设计验收。

提供真实主机 `tls/fullchain.pem`、`tls/privkey.pem`、公共CA和已验续期/reload任务。未知Host421，HTTP canonical HTTPS308，`/actuator`及子路径404；关闭access日志避免认证query/payload记录。代理容器health仅本机证书例外做进程/upstream检测，不验证CA、域名、到期或续期。

创建资源前校验（源码/解压包中填实际Compose路径）：

```text
python scripts/verify_docker_deployment.py --env-file <外部/deployment.env> --compose <本目录/compose.yml>
```

工具校非秘密输入、不可变镜像、issuer/代理一致及渲染隔离/权限；DNS、宿主机防火墙、CA、文件保管、容量、备份仍目标检查。继承shell环境须与选定文件一致。

## 3. 空库先迁移、bootstrap，再 Web

以下在本部署目录执行，每条保留 env-file/-f。由目标防火墙/绑定接口保持入口关闭。密码操作须真实交互终端，不能管道输入密码或记录transcript。

```sh
docker compose --env-file /protected/deployment.env -f compose.yml up -d postgres
docker compose --env-file /protected/deployment.env -f compose.yml --profile maintenance run --rm -T maintenance database-migrate
docker compose --env-file /protected/deployment.env -f compose.yml exec -T postgres psql -X -U postgres -d las_sso -v ON_ERROR_STOP=1 < postgres-runtime-permissions.sql
docker compose --env-file /protected/deployment.env -f compose.yml --profile maintenance run --rm --no-TTY=false maintenance admin-bootstrap --username=InitialAdmin
docker compose --env-file /protected/deployment.env -f compose.yml up -d sso proxy
```

`database-migrate` 用内嵌Flyway initializer，验证完成，无HTTP/任务，不需管理员/console。生产Web拒无管理员空库。每次成功离线迁移后执行 `postgres-runtime-permissions.sql`：runtime可读Flyway历史，不可改写；migrator拥有schema/object，runtime有业务DML无schema CREATE，backup只读。Web保持Flyway校验，runtime不能执行尚未应用迁移。

现有用户bootstrap改用 `--user-id=<UUID>`，不同传username。临时密码隐藏读取/24小时到期，首登强改。核唯一管理员，经已审查TLS登录改密，再私密生成保管恢复码。SSO账号站首发允许客户端为空，不自动登记local/reference，不只凭health绿开放。

PowerShell不支持shell `<`：经标准输入发送非秘密SQL，或复制至DB容器后 `psql -f`；命令不含密码值。Compose v5在Windows自动PTY下可能需显式 `--no-TTY=false`，密码仍仅console。

## 4. 维护与升级

先关闭/排空入口、停止Web写入者：

```sh
docker compose --env-file /protected/deployment.env -f compose.yml stop proxy sso
docker compose --env-file /protected/deployment.env -f compose.yml --profile maintenance run --rm --no-TTY=false maintenance admin-reset-password
```

维护用相同精确server镜像/key/config、独立migrator，无management、关闭scheduling/sessioncleanup、`logging: none`。无TTY reset/bootstrap失败，密码参数/任意配置覆盖失败。console仍可能敏感，须私密；reset显示唯一管理员需YES确认，设新ACTIVE密码，与bootstrap临时凭据不同。验新登录/旧会话拒/恢复码重新保管再开放。

升级保已核异机备份/旧镜像配置，停writer，选新镜像、离线迁移、重设runtime权限、启动验证。V16普通索引需目标数据时间/空间演练。Flyway只前向，只有已验schema兼容才能回binary；生产门禁拒未知未来迁移。不能把 `down --volumes` 删除持久卷当升级/回退。

旧快照恢复至隔离库、停入口/任务；按expected-epoch交互确认、新key/kid及全局重置，清消费方session/cache/旧证据并拒迟到旧代响应再开放。命令 `rotate-security-epoch-after-restore --expected-epoch=<记录UUID> --reason=<非秘密引用>` 需交互确认；复制数据库/回退JAR不等恢复。

## 5. 监控、备份与目标门禁

仅将审查采集器接私有monitoring网络，以 `las-sso-metrics` 与独立受保护密码文件采 `sso:9091/actuator/prometheus`。既有Prometheus/Alertmanager/Grafana模板需实际网络和真实通知。Compose不供应采集器、公开Grafana或备份调度。实测service/DB/snapshot/outbox/reuse及主机磁盘/资源/时钟/证书、备份新鲜度/失败的真实触发恢复通知。health/cache不等通知送达。

backup角色可在私有DB网络认证 `pg_dump --format=custom`，密码仅受保护文件/进程环境，不进argv。记摘要、日期、PG/schema/image/验收元信息，不记数据。加密异机传输、核保留/可恢复及失败通知；Docker卷/同机dump不是异机备份。明确RPO/RTO并恢复实际backup，保audit/tombstone/consent/username事实及key/password分离保管，不虚构频率/保留期限。

开放SSO账号站前记录：

| 门禁 | 必需证据 |
| --- | --- |
| P06 | 真实主机/DNS/公共CA/续期；未知Host/伪头/SecureCookie/Origin，外部无法直连upstream/DB/metrics |
| P07 | Linux UID/mode/mount保管，重启key/secret稳定、错误key拒、轮换与责任人 |
| P08 | 目标迁移/权限/runtime无DDL/Flyway只读、backup只读、任务/DB故障恢复/schema兼容回退 |
| P09 | 私有认证采集/UI、真实收件人确认、故障恢复通知/凭据轮换 |
| P10 | 加密定时异机备份、实际隔离恢复/新key+epoch/旧凭据拒、实测RPO/RTO及操作员恢复 |

目标容量/安全/最终候选/有限开放仍必需。具体消费方上线时适用P11/P12，SSO首发不要求LASLOG/AlienCommon提前完成。保存候选特定证据，旧包不能用新字节覆盖。
