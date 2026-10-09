# Docker 部署：从解压包到首次登录

[目录](README.md) · [English](../en/docker-deployment.md) · [排障](troubleshooting.md)

**适用：** Linux 主机、单实例、同机 Nginx、独立 PostgreSQL、初期无 CDN。

**执行位置：** 解压后的发行包根目录。本文用 Bash；`/etc/las-sso` 和 `/var/tmp` 是可调整的路径示例。

## 路线图

| 步骤 | 做什么 | 成功判据 |
| --- | --- | --- |
| 1 | 验证包和工具 | 包验证 exit 0 |
| 2 | 构建三类镜像 | 获得精确 image ID 和构建记录 |
| 3 | 准备配置、秘密、key、TLS | 文件齐全且目标 UID 可读 |
| 4 | 填写并校验部署输入 | 校验 exit 0 |
| 5 | 启数据库、离线迁移 | V1–V16 完成、命令 exit 0 |
| 6 | bootstrap 唯一管理员 | 隐藏输入完成、命令 exit 0 |
| 7 | 启 Web、首登改密 | 三服务健康、管理员可登录 |
| 8 | 目标验收并开放 | 监控、备份、TLS 和外部边界通过 |

## 1. 验证包与工具

准备 Docker Engine、Compose 插件、Python 3.10+。主机生成签名密钥还需 JDK 21 的 `keytool`。

```sh
python3 scripts/verify-package.py
docker version
docker compose version
```

**预期：** 首条显示 `Verified {{VERSION}}`。当前镜像本地验收架构为 Linux/amd64。

| 如果失败 | 处理 |
| --- | --- |
| 文件缺失、多余或 checksum 不符 | 重新取得完整包 |
| Docker daemon 无法连接 | 核服务状态与操作员权限 |
| 其他 CPU 架构 | 先验证对应镜像和运行流程 |

## 2. 构建镜像

包不内置容器镜像。构建在受控联网机器进行；生产主机也可加载已构建、校验过的相同镜像。

### 2.1 服务端镜像

```sh
WORK="/var/tmp/las-sso-{{VERSION}}"
mkdir -p "$WORK"
SERVER_SHA256="$(python3 -c 'import json; print(json.load(open("provenance/release-manifest.json"))["artifacts"]["server"]["sha256"])')"

python3 scripts/build_docker_image.py \
  --jar application/las-sso-server-{{VERSION}}.jar \
  --expected-sha256 "$SERVER_SHA256" \
  --version {{VERSION}} \
  --tag las-sso:{{VERSION}} \
  --release-manifest provenance/release-manifest.json \
  --output-dir "$WORK/server"
```

**预期：** `$WORK/server/image-build.json` 记录 source commit、JAR 摘要和 image ID；`unverifiedFixture=false`。

### 2.2 数据库与代理镜像

```sh
python3 scripts/build_docker_infrastructure.py \
  --postgres-tag las-sso-postgres:{{VERSION}} \
  --proxy-tag las-sso-proxy:{{VERSION}} \
  --output-dir "$WORK/infrastructure"

docker image inspect las-sso:{{VERSION}} --format '{{.Id}}'
docker image inspect las-sso-postgres:{{VERSION}} --format '{{.Id}}'
docker image inspect las-sso-proxy:{{VERSION}} --format '{{.Id}}'
```

**预期：** 得到三个 `sha256:...`，以及 `infrastructure-images.json` 和包清单。

- PostgreSQL/Nginx 从 digest 固定基础镜像构建，并在构建时更新 Alpine 系统包。
- PostgreSQL 用 `su-exec` 替换旧 `gosu`；重新构建可能产生不同字节。
- 扫描最终精确镜像，保留记录；运行启动时不更新系统包。
- 若跨机传输，使用 `docker image save/load`，核归档摘要和加载后的 ID。
- Compose 为 `pull_policy: never`；部署前必须已加载指定镜像。

## 3. 准备外部文件

保持发行目录不可变，将实际配置放在外部受保护目录。

```text
/etc/las-sso/
├── deployment.env              非秘密输入
├── config/server.properties    容器应用配置
├── secrets/                    9 个秘密文件
├── signing.p12                 持久签名密钥
└── tls/
    ├── fullchain.pem           正式域名证书链
    └── privkey.pem             TLS 私钥
```

### 3.1 配置模板

```sh
install -d -m 0700 /etc/las-sso /etc/las-sso/config /etc/las-sso/secrets /etc/las-sso/tls
install -m 0600 deploy/docker/server.properties.example /etc/las-sso/config/server.properties
install -m 0600 deploy/docker/deployment.env.example /etc/las-sso/deployment.env
```

以上目录操作需相应主机权限；按实际路径执行。

### 3.2 秘密文件

| 文件名 | 内容 / 使用者 |
| --- | --- |
| `db-admin-password` | PG 管理密码 |
| `db-runtime-password` | SSO 运行账号密码 |
| `db-migrator-password` | 离线迁移 / 维护密码 |
| `db-backup-password` | 只读备份账号密码 |
| `signing-password` | PKCS12 密码，私钥同密码 |
| `recovery-pepper` | 恢复摘要秘密 |
| `transient-pepper` | Token / 瞬态摘要秘密 |
| `captcha-secret` | 验证码 HMAC 秘密 |
| `monitoring-hash` | 独立采集密码的 `{bcrypt}` 哈希，cost 10–14 |

**生成要求：** 各用途独立随机；DB/key 密码至少 12 UTF-8 字节，三类 pepper/HMAC 至少 32 字节。

将值直接写入受保护文件，不放入 `deployment.env`、命令参数、屏幕输出或 Git。采集明文密码只交采集器；应用只收到哈希。重启保持原值。

### 3.3 签名密钥

在私有交互终端运行，按提示隐藏输入密码；将同一密码存入 `secrets/signing-password`。

```sh
keytool -genkeypair \
  -alias las-sso-signing \
  -keyalg RSA -keysize 3072 -sigalg SHA256withRSA \
  -storetype PKCS12 \
  -keystore /etc/las-sso/signing.p12 \
  -dname "CN=LAS SSO Signing"
```

按保管策略设置证书有效期。`LAS_SSO_SIGNING_KEY_ALIAS` 与 alias 一致；key/kid 稳定、备份独立加密保管。

### 3.4 TLS 与 Linux 文件权限

| 文件 | 必须可读的容器身份 |
| --- | --- |
| `server.properties`、`signing.p12`、应用秘密 | SSO UID `10001` |
| 四个 DB 密码文件 | PostgreSQL 的数据库 UID |
| runtime / migrator 密码文件 | 同时由 SSO `10001` 与 PostgreSQL UID 读取 |
| `tls/privkey.pem` | Nginx UID `101` |

查询实际 PG UID，避免猜测：

```sh
docker run --rm --network none --entrypoint id las-sso-postgres:{{VERSION}} -u postgres
```

使用受限所有权、组或 POSIX ACL 满足双读者文件权限；普通 Compose secrets 是 host-file mount，不自动实现 vault 加密或 UID 重映射。

支持POSIX ACL的Linux可按下面示例授权。**前提：** 九个秘密文件、key和TLS文件已准备好，安装了 `setfacl`，命令由文件所有者/root在私有终端执行。

```sh
PG_UID="$(docker run --rm --network none --entrypoint id las-sso-postgres:{{VERSION}} -u postgres)"
chmod 0600 /etc/las-sso/config/server.properties /etc/las-sso/signing.p12 /etc/las-sso/secrets/* /etc/las-sso/tls/*
setfacl -m u:10001:r /etc/las-sso/config/server.properties /etc/las-sso/signing.p12
for name in db-runtime-password db-migrator-password signing-password recovery-pepper transient-pepper captcha-secret monitoring-hash; do
  setfacl -m u:10001:r "/etc/las-sso/secrets/$name"
done
for name in db-admin-password db-runtime-password db-migrator-password db-backup-password; do
  setfacl -m "u:$PG_UID:r" "/etc/las-sso/secrets/$name"
done
setfacl -m u:101:rx /etc/las-sso/tls
setfacl -m u:101:r /etc/las-sso/tls/fullchain.pem /etc/las-sso/tls/privkey.pem
```

**预期：** `getfacl`看到指定用户权限、other无访问；创建新文件或轮换后重新核ACL。若有userns/不同UID映射，按实际映射调整，不照搬数字。

**预期：** 需要的 UID 可读，其他普通主机用户不可读。不要用私钥 `chmod 644` 解决权限问题。

提供正式域名的公共 CA 证书链和私钥，并配置续期/reload。容器健康检查不能代替证书到期和信任检查。

## 4. 填写 deployment.env

按 `/etc/las-sso/deployment.env` 模板填写；所有限额需目标测量。

| 变量组 | 填什么 |
| --- | --- |
| `LAS_SSO_PROJECT` | 预发布/生产各自唯一 Compose 项目名 |
| `LAS_SSO_IMAGE` | 服务端完整 `sha256:...` 或已加载的 repository digest |
| `LAS_SSO_DB_IMAGE` | PostgreSQL完整image ID/digest |
| `LAS_SSO_PROXY_IMAGE` | Nginx完整image ID/digest |
| `LAS_SSO_HOST` | 正式 DNS 主机名，不含协议或端口 |
| `LAS_SSO_ISSUER` | `https://<正式主机>`，非 443 时含外部端口；无尾斜线 |
| `LAS_SSO_SIGNING_KEY_ALIAS` / `LAS_SSO_SIGNING_KEY_ID` | PKCS12 alias 与稳定、唯一 kid |
| `LAS_SSO_BIND_ADDRESS` / `LAS_SSO_HTTP_PORT` / `LAS_SSO_HTTPS_PORT` | 审查后的主机接口和外部端口 |
| `LAS_SSO_CONFIG_DIR` / `LAS_SSO_SECRET_DIR` | 本例 `/etc/las-sso/config` / `/etc/las-sso/secrets` |
| `LAS_SSO_KEY_PATH` / `LAS_SSO_TLS_DIR` | 本例 `/etc/las-sso/signing.p12` / `/etc/las-sso/tls` |
| `LAS_SSO_MEMORY_LIMIT` / `LAS_SSO_CPUS` | SSO非零有限限额；包含JVM原生内存余量 |
| `LAS_SSO_DB_MEMORY_LIMIT` / `LAS_SSO_DB_CPUS` | PostgreSQL目标限额 |
| `LAS_SSO_PROXY_MEMORY_LIMIT` / `LAS_SSO_PROXY_CPUS` | Nginx目标限额；总体给主机留余量 |

本拓扑不设置 servlet 前缀。不得把 host/systemd 的 `127.0.0.1` 应用配置复制到容器内；已有容器模板使 9000/9091 在容器内监听，由网络和无 host 映射隔离。

```sh
python3 scripts/verify_docker_deployment.py \
  --env-file /etc/las-sso/deployment.env \
  --compose deploy/docker/compose.yml
```

**预期：** `Docker input/Compose boundaries verified`，exit `0`。

**失败先查：** 空值、标签代替 image ID、Issuer/host/port 不一致、shell 环境覆盖、零限额。不要发布 `docker compose config` 的原始输出。

## 5. 启动数据库并迁移

仍在包根，定义后续命令共用的 Bash 函数：

```sh
dc() { docker compose --env-file /etc/las-sso/deployment.env -f "$PWD/deploy/docker/compose.yml" "$@"; }
dc up -d postgres
dc ps
```

**预期：** `postgres` 为 `healthy`。若尚在启动，等待后再次查询。

```sh
dc --profile maintenance run --rm -T maintenance database-migrate
dc exec -T postgres psql -X -U postgres -d las_sso -v ON_ERROR_STOP=1 \
  < deploy/docker/postgres-runtime-permissions.sql
```

**预期：** 迁移命令显示完成、exit `0`，没有 HTTP listener；权限 SQL 成功。

| 行为 | 作用 |
| --- | --- |
| 内嵌 Flyway V1–V16 | 单一迁移来源，无第二份手工迁移 |
| migrator 拥有对象 | runtime 不执行 DDL |
| postmigration 权限 SQL | runtime 可读但不能改写 Flyway history；每次迁移后重跑 |
| 重复 database-migrate | 已完成时校验后正常退出 |

**首次初始化失败：** 先保留私有故障记录；已有卷不会重跑 init。不要未经确认删除已有数据卷。

## 6. 初始化唯一管理员

必须在真实交互终端执行；本步骤不使用 `-T`。

```sh
dc --profile maintenance run --rm --no-TTY=false maintenance admin-bootstrap --username=InitialAdmin
```

1. 核用户名后输入临时密码。
2. 再次隐藏输入确认。
3. 记录成功结果的用户 UUID / audit ID；不记密码。

**预期：** 命令 exit `0`，恰好一个管理员；临时密码 24 小时内有效、首次登录强制修改。

| 情况 | 操作 |
| --- | --- |
| 提升已有用户 | 用 `--user-id=<UUID>` 替代 username，不能同时传 |
| `INTERACTIVE_CONSOLE_REQUIRED` | 换真实终端，显式开启 TTY |
| 已有管理员 | 核当前状态；使用[维护重置](operations.md) |

## 7. 启动服务与首次登录

```sh
dc up -d sso proxy
dc ps
dc exec -T sso curl --fail --silent http://127.0.0.1:9091/actuator/health/readiness
```

**预期：** `postgres`、`sso`、`proxy` 均健康；readiness 返回 `UP`。

浏览器通过真实 HTTPS 入口：

1. `/login` 用临时管理员密码登录。
2. 完成强制改密，再用新密码重新登录。
3. 生成恢复码，保存到主机/数据库之外的私密位置。
4. 检查 `/admin/users`、`/admin/audit`。

**网络结果：** 未知 Host `421`，HTTP canonical redirect `308`，公开 `/actuator` 为 `404`；数据库、9000、9091 无 host 端口映射。

## 8. 开放前最后检查

| 检查 | 成功判据 |
| --- | --- |
| 域名与 TLS | 无浏览器证书例外；证书续期/reload可验证 |
| Cookie / 代理 | Secure/HttpOnly/Lax、host-only；伪 Forwarded 不影响地址 |
| 数据库与重启 | 同 key / secret，SSO 会话按策略保持；DB故障readiness拒绝 |
| 监控 | 私有采集认证、真实 firing/resolved 通知 |
| 备份 | 加密、异机可恢复，RPO/RTO明确 |
| 容量与安全 | 目标资源/当前镜像扫描通过 |

详细操作：[监控](monitoring.md)、[备份](backup-and-recovery.md)、[验收](acceptance.md)。

SSO 账号站可不登记业务客户端。首发范围不包含尚未联验的 LASLOG/AlienCommon。

## 常用命令

继续在包根、定义了 `dc()` 的终端执行：

```sh
dc ps
dc logs --tail 100 sso
dc logs --tail 100 postgres
dc logs --tail 100 proxy
dc stop proxy sso
dc up -d sso proxy
```

日志仅在私有终端查看、对外先脱敏。`down --volumes` 会删除数据库卷，不用于重启或升级。

PowerShell 操作：将 `dc` 替换为完整 Compose 参数；不支持 Bash 函数与 `<` 重定向，非秘密 SQL 可通过 `Get-Content ... | docker compose ... exec -T postgres psql ...` 传入。密码仍由真实 TTY 隐藏读取。
