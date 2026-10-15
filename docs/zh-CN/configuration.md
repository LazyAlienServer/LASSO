# 配置与秘密

[目录](README.md) · [English](../en/configuration.md) · [部署](deployment.md)

真实配置放在包外、源码外。模板只定义输入，不自动建立 DNS、TLS、采集器或备份。Docker 通过 Compose 挂载秘密文件，再由 configtree 读取，不将秘密直接写入环境变量。

## 选择布局

| 布局 | 模板 |
| --- | --- |
| 默认Docker+主机Nginx | `deploy/docker/compose.host-nginx.yml`、`deployment.host-nginx.env.example`、`server.properties.example` |
| 内置代理 | `deploy/docker/compose.yml`、`deployment.env.example` |
| 主机JAR | `config/server/server.properties.example`及可选monitoring模板 |
| 参考BFF/queue | `config/reference-bff/*.properties.example`、独立client/queue秘密 |

源码的主机/参考配置在`release/templates/config/`，部署文件在`las-sso-server/deploy/`；上表为包内稳定路径。

外部布局示例：`/etc/las-sso/deployment.env`、`config/server.properties`、`secrets/`、`signing.p12`。已有安装保留项目名、数据库卷、签名密钥和 pepper。

## 非秘密输入

| 输入 | 含义 |
| --- | --- |
| LAS_SSO_PROJECT | 现有或独立project，决定卷/网络名 |
| LAS_SSO_IMAGE / LAS_SSO_DB_IMAGE | 已加载的不可变 ID/digest，不用浮动 tag |
| LAS_SSO_HOST / LAS_SSO_ISSUER | 浏览器规范主机名及 HTTPS issuer，此布局无尾斜线或路径前缀 |
| LAS_SSO_APP_PORT | 未占用的主机回环端口，例如 19000，不是 issuer 端口 |
| LAS_SSO_HTTP_PORT / LAS_SSO_HTTPS_PORT | 实际公网入口端口 |
| LAS_SSO_CONFIG_DIR / LAS_SSO_SECRET_DIR / LAS_SSO_KEY_PATH | 绝对、受保护的外部路径 |
| LAS_SSO_SIGNING_KEY_ALIAS | PKCS12 现有 entry，例如 las-sso-signing |
| LAS_SSO_SIGNING_KEY_ID | 稳定公开 kid，1–128 `[A-Za-z0-9._-]`，拒绝 las-local/las-test 及示例标记 |
| LAS_SSO_MEMORY_LIMIT / LAS_SSO_CPUS；LAS_SSO_DB_MEMORY_LIMIT / LAS_SSO_DB_CPUS | 有限资源限额，给主机/JVM 原生内存留余量 |

主机入口不需proxy镜像/TLS挂载/proxy限额。容器server/管理内部监听0.0.0.0，靠发布/网络边界隔离；主机JAR才监听loopback。issuer变化需协调身份命名空间和consumer配置。

## 生成独立秘密文件

使用私有Bash、OpenSSL、JDK21 keytool、htpasswd及POSIX ACL工具，失败即停，查权限不打印内容。**新安装**可执行：

```sh
(
  set +x
  set -euo pipefail
  umask 077
  SECRET_DIR='/etc/las-sso/secrets'
  install -d -m 0700 "$SECRET_DIR"
  names=(db-admin-password db-runtime-password db-migrator-password db-backup-password signing-password recovery-pepper transient-pepper captcha-secret)
  for name in "${names[@]}"; do test ! -e "$SECRET_DIR/$name"; done
  set -o noclobber
  for name in "${names[@]}"; do openssl rand -hex 32 > "$SECRET_DIR/$name"; done
)
```

各文件使用独立随机值。数据库/密钥密码至少 12 UTF-8 字节，pepper/HMAC 至少 32 字节；普通重启复用原值。更改 pepper 可能使旧摘要无法识别，轮换前需评估恢复影响。

监控使用独立的 24–48 字符随机 ASCII 密码，保存在密码管理器或采集器秘密文件中（bcrypt 密码不超过 72 字节）。生成摘要：

```sh
(
  set +x
  set -euo pipefail
  umask 077
  HASH_FILE='/etc/las-sso/secrets/monitoring-hash'
  test ! -e "$HASH_FILE"
  line="$(htpasswd -nBC 12 las-sso-metrics)"
  hash="${line#las-sso-metrics:}"
  [[ "$hash" =~ ^\$2[aby]\$12\$[./A-Za-z0-9]{53}$ ]]
  set -o noclobber
  printf '{bcrypt}%s' "$hash" > "$HASH_FILE"
)
```

htpasswd 提示两次隐藏输入。SSO 接收 `{bcrypt}` 加摘要，cost 为 10–14；采集器接收原密码，用户名为 las-sso-metrics。摘要文件不含用户名前缀、引号或空格。轮换时保留受保护的旧恢复材料，同步更新两端，并按需重新创建 SSO。

## 签名key和权限

生成持久签名密钥，与 TLS 及队列 AES 密钥分开：

```sh
keytool -genkeypair -alias las-sso-signing -keyalg RSA -keysize 3072 \
  -sigalg SHA256withRSA -storetype PKCS12 -validity 365 \
  -keystore /etc/las-sso/signing.p12 -dname 'CN=LAS SSO Signing' \
  -storepass:file /etc/las-sso/secrets/signing-password \
  -keypass:file /etc/las-sso/secrets/signing-password
```

alias 必须匹配，私钥与存储密码相同；有效期及保管方案按环境选择。重启保留原密钥和 kid。

1. 配置、密钥和秘密文件设为 0600。给 SSO UID 10001 读取 server.properties、signing.p12 及 runtime/signing/pepper/CAPTCHA/monitoring 秘密的权限。
2. 用 `docker run --rm --network none --entrypoint id <exact-db-image> -u postgres` 查询 PostgreSQL UID，给它读取四个数据库密码文件的权限；runtime/migrator 密码同时供 SSO 读取。
3. 用 `setfacl -m u:<uid>:r <file>` 或等效的受限所有者/组配置，使用 getfacl 检查；user namespace 按实际 UID 映射处理。主机 TLS 权限按主机 Nginx 读者配置，不用容器 UID 101。
4. 复制 Docker server.properties、填写 deployment.env，再运行验证器。输入检查不读取 PKCS12；启动失败时按当次异常类别排查。
