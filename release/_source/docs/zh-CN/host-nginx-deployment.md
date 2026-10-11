# Docker配合同机单层外部Nginx — {{VERSION}}

[目录](README.md) · [English](../en/host-nginx-deployment.md) · [内置代理替代方案](docker-deployment.md) · [监控](monitoring.md) · [运维](operations.md)

**已选拓扑：** Docker同机直接安装Nginx，一个SSO容器及独立PG。主机入口负责HTTPS；示例只将SSO发布到`127.0.0.1:19000`，management9091/PG不发布。另一Nginx容器或异机代理需不同连接/信任设计。

```text
浏览器 → 主机Nginx HTTPS → 127.0.0.1:19000 → SSO:9000 → PG
                                          └→ 私有9091 ← 采集器
```

**位置：** 解压包根、Bash。正式public域名/CA/续期在目标正式上线配置；软件检查先用隔离、有明确身份的HTTPS fixture。production仍要求合法HTTPS issuer，不将公共示例或loopback HTTP作为issuer。路径是示例；包保持不变，配置使用外部副本。

## N01 验软件并准备两个镜像

**前提：** Docker/Compose、Python3.10+、生成signing材料的JDK21 keytool、主机Nginx及已审查Linux所有者/资源布局。

1. 核可信发行checksum，再`python3 scripts/verify-package.py`；exit0，精确version/source/artifact一致。
2. 按[Docker步骤2.1](docker-deployment.md)构建/加载精确SSO镜像，保image-build回执及不可变image ID。
3. 只构建/加载PG；主机入口不需要内置proxy镜像：

```sh
set -euo pipefail
WORK="/var/tmp/las-sso-{{VERSION}}"
mkdir -p "$WORK"
python3 scripts/build_docker_infrastructure.py \
  --postgres-tag las-sso-postgres:{{VERSION}} \
  --output-dir "$WORK/infrastructure"
docker image inspect las-sso:{{VERSION}} --format '{{.Id}}'
docker image inspect las-sso-postgres:{{VERSION}} --format '{{.Id}}'
```

4. 保回执并扫描精确两个镜像及实际主机Nginx/OS；重建是新字节，不沿用旧回执。

**预期：** 包已验、两个镜像已定位、主机Nginx可用。**失败：** 修source/image/version不符，不将dev/RC重标或绕dirty检查。

## N02 外配置、密钥和监控哈希

**前提：** 私密外部目录/所有者、已选fixture或正式issuer、CPU/RAM限制；已有安装保project名、PG卷、key及pepper。

1. 外部复制`deploy/docker/server.properties.example`为server.properties，`deployment.host-nginx.env.example`为deployment.env。按[Docker步骤3](docker-deployment.md)供应九秘密文件、signing key及SSO/PG权限；主机TLS文件读者按主机Nginx，不套容器UID101。
2. 填精确SSO/PG image ID、配置/秘密/key路径、有限资源、当前alias及稳定key ID。`LAS_SSO_APP_PORT=19000`为未占用主机loopback端口；host/issuer/HTTP/HTTPS描述浏览器入口。此Compose不要求proxy镜像/TLS目录/proxy限额。
3. kid仅1–128位ASCII字母/数字/点/下划线/横线，拒las-local/las-test前缀及local-only/test-only/change-me/changeme标记。alias匹配keytool；ID为公开kid，不是密码/路径，见[启动错误](troubleshooting.md)。
4. 监控秘密为`{bcrypt}`+cost10–14的bcrypt，不是明文或htpasswd整行。按[私密生成](monitoring.md)操作，原密码供collector；不加引号，不通过shell参数展开哈希美元符号。
5. 普通/维护共用同一helper并执行host拓扑验证：

```sh
set -euo pipefail
dc() { docker compose --env-file /etc/las-sso/deployment.env -f "$PWD/deploy/docker/compose.host-nginx.yml" "$@"; }
python3 scripts/verify_docker_deployment.py \
  --env-file /etc/las-sso/deployment.env \
  --compose deploy/docker/compose.host-nginx.yml --topology host-nginx
```

**预期：** host校验exit0，SSO仅一项loopback应用发布，无DB/管理/维护端口发布。**失败：** 查shell覆盖、kid和路径；验证器不核实际秘密内容/Nginx/TLS。此文件独立使用，不与内置Compose合并。

## N03 空库、离线迁移及初始管理员

**前提：** N01/N02通过，新DB；已有可用管理员不再bootstrap。

1. PG启动等healthy，同一精确镜像执行offline迁移，再授权runtime只读Flyway历史：

```sh
set -euo pipefail
dc up -d --wait --wait-timeout 120 postgres
dc --profile maintenance run --rm -T maintenance database-migrate
dc exec -T postgres psql -X -U postgres -d las_sso -v ON_ERROR_STOP=1 < deploy/docker/postgres-runtime-permissions.sql
```

2. 每条exit0才继续，核V1–V16及迁移所有者。已有卷不重跑角色/密码初始化。
3. 真实终端隐藏密码初始化唯一管理员：

```sh
dc --profile maintenance run --rm --no-TTY=false maintenance admin-bootstrap --username=InitialAdmin
```

4. 只记管理员UUID/audit/time，临时密码私密保存；启动SSO并查私有readiness：

```sh
set -euo pipefail
dc up -d --wait --wait-timeout 120 sso
dc exec -T sso curl --fail --silent --show-error --max-time 45 http://127.0.0.1:9091/actuator/health/readiness
curl --silent --show-error --output /dev/null --write-out '%{http_code}\n' http://127.0.0.1:19000/login
```

**预期：** PG/SSO健康，readiness UP、本机login GET200。**失败：** 私有有界SSO日志查本次gate/DB/key/monitoring错误；PG健康不代SSO可用。保已有数据，不down --volumes。

## N04 安装单层主机入口并验转发

**前提：** 主机Nginx、已选HTTPS issuer/证书、N03健康。public证书/DNS/续期属于正式上线；隔离fixture仅按该fixture显式信任。

1. 将`deploy/nginx/sso-container-site.conf.example`复制到主机Nginx已加载站点目录；替换全部示例host/证书/端口，upstream匹配LAS_SSO_APP_PORT。`deploy/nginx/las-forwarding.conf`外部复制到`/etc/nginx/snippets/las-sso-forwarding.conf`。
2. 唯一canonical host、账号/API/OIDC完整路径。include清其他代理头，主机入口覆盖scheme/port/真实IP。非标准public TLS端口须同时改listen/redirect/issuer；不增加另一层或信客户端X-Forwarded-For。
3. TLS权限按主机Nginx读者；完整配置先test再reload（systemd示例）：

```sh
set -euo pipefail
nginx -t
systemctl reload nginx
```

4. 经选定HTTPS验证discovery issuer/JWKS、HTTP canonical跳转、本site未知Host拒绝及公网actuator404。主机应用仅IPv4 loopback绑定，非可信机器不可直达app/DB/管理端口。
5. 浏览器U03强改临时密码、重新登录/U07保恢复码；A01/A10读、CSRF/同源拒及伪代理头不改地址。只保存安全结果，不留认证抓包。

**预期：** 单层主机入口提供正确HTTPS/login/metadata，私有边界保持。**失败：** 查hostNginx/listener/TLS/issuer/upstream，不关TLS/SecureCookie/CSRF；主机其他站点保自身default行为。

## N05 切换已有内置安装、重启与升级

**前提：** 已验backup、现project/DB/image/config记录及授权窗口；不隐含新key/pepper/bootstrap/空project。

1. 旧helper停内置proxy，关闭/排空本site入口。保原LAS_SSO_PROJECT、PG镜像/数据卷及外配置；helper切到独立host文件，N02验证并核渲染PG卷仍`<project>_postgres_data`。
2. host文件启动/recreate SSO使loopback发布生效。旧proxy可保停止的orphan；不广泛remove-orphans/down --volumes，只退休明确旧proxy。
3. 常规restart为`dc restart sso`再readiness；env/image/port变化按审查`dc up -d --no-deps --force-recreate sso`，restart不载新env。N04单独控制主机入口。
4. O03升级用此helper及`--topology host-nginx`；主机入口排空后停SSO/其他writer，迁移/权限，再`dc up -d --wait --wait-timeout 120 sso`并验证，不对此拓扑运行内置proxy命令。
5. O02reset、B01/B02备份恢复共用独立目标helper并停writer。主机续期为`nginx -t`后主机reload，不`dc exec proxy`；backup/restore用独立project，切换前epoch/key/consumer屏障。

**预期：** 同数据/key/project、精确新软件及已验主机入口。**失败：** 保backup/state，核真实迁移提交；旧binary回退须schema兼容，失败目标保持关闭。

## N06 查看指标并完成验收

**前提：** N03/N04正常，独立collector密码及已选监控平台。

1. 私有终端不发布9091即可查看文本指标，curl提示时输collector原密码（不用-T）：

```sh
dc exec sso curl --fail --silent --show-error --user las-sso-metrics http://127.0.0.1:9091/actuator/prometheus
```

2. 持续采集将Prometheus加入`<project>_monitoring`、target为`sso:9091`并挂私有原密码文件，不用另一容器127.0.0.1，见M01–M04；Grafana读Prometheus，不需公开SSO管理口。
3. 核up=1、snapshot有效/新鲜、真实面板和firing/resolved；独立备份/维护/限额演练及V01本候选支持路径。
4. 正式目标上线时供应真实域名/publicCA/续期，验证issuer/client变更及V02再开放。域名变化是身份/配置变化，不静默把fixture当正式身份别名。

**预期：** 私有认证指标及真实版本/目标验收记录。**失败：** 401查原密码/哈希，旧snapshot查DB/sampler；fixture或文本dump不代公众上线/图表/通知。
