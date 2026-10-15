# 配合同机单层外部Nginx部署

[目录](README.md) · [English](../en/deployment.md) · [配置](configuration.md)

**默认布局：** Linux 主机直接安装 Nginx，SSO 和 PostgreSQL 使用 Compose。Nginx 终止 HTTPS，经主机回环端口访问 SSO；管理口 9091 和数据库不发布到主机。正式域名、公共证书和续期方案在上线时确定；软件演练使用显式信任的隔离 HTTPS 环境。

## 1. 验包并构建/加载镜像

需要 Python 3.10+、Docker/Compose 和 Java 21 keytool。在解压包根目录执行：

```sh
python3 scripts/verify-package.py
```

按发行清单核对 JAR 后构建镜像；ZIP 不含镜像：

```sh
set -euo pipefail
WORK="/var/tmp/las-sso-{{VERSION}}"
mkdir -p "$WORK"
HASH="$(python3 -c 'import json; print(json.load(open("provenance/release-manifest.json"))["artifacts"]["server"]["sha256"])')"
python3 scripts/build_docker_image.py --jar application/las-sso-server-{{VERSION}}.jar \
  --expected-sha256 "$HASH" --version {{VERSION}} --tag las-sso:{{VERSION}} \
  --release-manifest provenance/release-manifest.json --output-dir "$WORK/server"
python3 scripts/build_docker_infrastructure.py --postgres-tag las-sso-postgres:{{VERSION}} --output-dir "$WORK/database"
docker image inspect las-sso:{{VERSION}} --format '{{.Id}}'
docker image inspect las-sso-postgres:{{VERSION}} --format '{{.Id}}'
```

保存构建回执，扫描实际镜像及主机 Nginx。跨机使用 save/load 后复核镜像 ID；重新构建需作为新制品核验。

## 2. 外部配置

按[配置](configuration.md)准备九个秘密文件、签名密钥及读取权限。将主机入口 env/server.properties 模板复制到包外，填写镜像 ID、资源限额和规范 HTTPS issuer。已有安装沿用原项目名和数据库卷。

```sh
dc() { docker compose --env-file /etc/las-sso/deployment.env -f "$PWD/deploy/docker/compose.host-nginx.yml" "$@"; }
python3 scripts/verify_docker_deployment.py --env-file /etc/las-sso/deployment.env \
  --compose deploy/docker/compose.host-nginx.yml --topology host-nginx
```

预期退出码 0，仅发布 `127.0.0.1:<APP_PORT>→9000`。后续运行和维护共用 `dc` 函数；此 Compose 文件独立使用。

## 3. 迁移和初始化

```sh
set -euo pipefail
dc up -d --wait --wait-timeout 120 postgres
dc --profile maintenance run --rm -T maintenance database-migrate
dc exec -T postgres psql -X -U postgres -d las_sso -v ON_ERROR_STOP=1 < deploy/docker/postgres-runtime-permissions.sql
```

新数据库在真实交互终端执行初始化，密码隐藏输入：

```sh
dc --profile maintenance run --rm --no-TTY=false maintenance admin-bootstrap --username=InitialAdmin
```

预期迁移退出码 0，创建唯一管理员，其临时密码有效期为 24 小时。已初始化数据库使用[重置流程](operations.md)，不重复 bootstrap；已有卷不会重跑 PostgreSQL 角色或密码初始化。

## 4. 启动SSO与主机入口

```sh
set -euo pipefail
dc up -d --wait --wait-timeout 120 sso
dc exec -T sso curl --fail --silent --show-error --max-time 45 http://127.0.0.1:9091/actuator/health/readiness
curl --silent --show-error --output /dev/null --write-out '%{http_code}\n' http://127.0.0.1:19000/login
```

预期 readiness 为 UP，登录页 GET 返回 200；APP_PORT 不同时替换 19000。在包外安装 `deploy/nginx/sso-container-site.conf.example` 和 `las-forwarding.conf`，填写主机名、证书、监听端口和 upstream，将 include 安装到站点引用的位置，然后执行：

```sh
set -euo pipefail
nginx -t
systemctl reload nginx
```

include 覆盖转发的主机名、协议、端口和客户端 IP。保留完整 `/api/v1` 和 OIDC 路径，拒绝本站错误 Host 和公网 actuator 请求；主机 upstream 不指向其他容器的 localhost。

## 5. 验证并开放

1. HTTPS discovery 的 issuer 一致，HTTP 跳转规范地址，JWKS 仅含公钥，Cookie 为 Secure/host-only。错误 Host 返回 421，公网 actuator 返回 404；外部不能直达应用、数据库或管理口。
2. 按用户指南 U03 修改临时密码，管理员重新登录后按 U07 保存恢复码；验证用户/管理员读取及 CSRF、同源拒绝行为。
3. 验证私有指标采集、实际通知、异机备份恢复及资源/安全配置。失败时按[排障](troubleshooting.md)处理。

## 替代布局

- 迁移已有内置代理安装：使用旧部署函数只停原 proxy，保留项目名、数据库卷、密钥和秘密；改用主机入口函数并重新创建 SSO，避免删除其他容器或卷。
- 内置 Nginx：独立选用 compose.yml 和对应 env，通过 `--proxy-tag` 构建代理镜像，配置 TLS 和代理资源限额。proxy 随 SSO 停启，两套部署函数分别使用。
- 主机 JAR：使用外部主机配置。production 离线迁移、初始化和重置时关闭 Web/jobs，之后以 runtime 数据库角色运行 Web。systemd service 和 sso-site 是主机模板，管理口监听回环；核验实际进程管理和读取权限。

升级见[运维](operations.md)，还原见[恢复](recovery.md)。启动检查失败时保持入口关闭，处理当次异常，不通过删除数据库重试。
