# Docker Compose本地验收，2026-10-09

[English](../../implementation/docker-compose-acceptance-2026-10-09.md) · [环境责任矩阵](../operations/docker-compose-deployment.md) · [可执行步骤](../../../las-sso-server/deploy/docker/README.zh-CN.md)

## 范围和精确制品

P01确认仅SSO账号站首发、单机Compose/同机Nginx/无CDN/独立SSO-PG、用户初期运维保管。本记录只完成Docker本地实现/验收，真实P06–P10、目标容量安全/最终候选仍待。

隔离镜像明确为**未验证源码fixture**：checkout基线 `11eca6b1a4d85ee5032905224f9ed31daf84e83f` 加应交工作区改动，不宣称干净release来源。核精确JAR manifest/startclass/version/hash及受限context。未新建release；`0.1.0-dev.20261007.1`保持字节、不含本轮。

| 制品 | 本地身份 |
| --- | --- |
| Server | `0.1.0-SNAPSHOT`，SHA256 `fbd8fad3dbe105fa5da5b42306d4c77af3dadce101f5a3cec3dae64301a3caa0` |
| SSO image | `sha256:de23bc34a3aebdee84c0e70ce28f63975878bfd283c3cdaf1db48f817f9f2e46` |
| 修补PG image | `sha256:09e2b24f9794c76db7b73ad0f398a9dfab5ca6ce9742989491679d253634587c`，PG18.4 |
| 修补proxy image | `sha256:67822e9fadd8ea32223d3f87348f786ff9de67d45c8b641fe1adef01b01dd4f6`，Nginx1.29.8 |
| JRE base | Temurin21 JRE digest `cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5`，JRE21.0.12.1+1-LTS |

delivery构建要求匹配干净 `package_release.py` source/version/serverhash/tests，或已验独立包provenance；fixture sourceCommit=null、label标未验证，不把任意旧JAR重标HEAD。部署验证器要求immutableID/digest，拒issuer/host/port不一致、继承host冲突、缺输入、零/无限额、秘密输入且不打印值。

## 命令与结果

JDK21三模块严格构建：

```text
.\gradlew.bat spotlessApply build -PrequireDatabaseTests=true --rerun-tasks --no-daemon --console=plain
python scripts/verify.py tests --require-no-skips
```

**834项：server588 +starter196 +reference50，失败/错误/跳过均0。** 新增两个runner测试及实际PG productioncontext重复 `database-migrate`、非servlet、V1–V16完成。早期Flyway final字段赋值编译失败已用实际不可变构造器修复；依赖锁/既有迁移无改动。

Docker29.4.0/Compose v5.1.2、Windows Docker Desktop的Linux/amd64。最终修补fixture随机私密secret、`auth.las.test`两天自签证书、随机loopback代理端口，限SSO768MiB/DB512MiB/proxy128MiB；**均非目标容量域名选定值**。HTTP TLS传输校指定fixture证书/hostname，Edge154/Playwright1.55.1用fixture-only SPKI例外，不是公共CA验收。

| 真实路径 | 结果 |
| --- | --- |
| 空库初始化/迁移 | admin/migrator/runtime/backup分离、migrator拥有表、离线V1–V16及重复exit0 |
| Runtime权限 | 域DML允许，无CREATE，postmigration SQL后Flyway历史UPDATE拒 |
| Backup权限 | SELECT可、域UPDATE拒，真实认证custom dump有PGDMP，私密dump删除 |
| 隐藏console | 真实Compose TTY bootstrap成功、密码无echo；无TTY reset拒、密码argv拒 |
| 离线reset | 唯一管理员/YES/两次隐藏输入成功、凭据ACTIVE、旧活跃session零、新密码account登录；强改只bootstrap |
| 管理员首登 | TLS下强改、成功后重新登录/account |
| 容器隔离 | SSO10001/proxy101、readonly/dropALL/no-new-privileges；app/DB无host映射 |
| Proxy | canonical308、HTTP/HTTPS未知Host421/HSTS、伪Forwarded/Prefix/host/proto/port不改Discovery、actuator404 |
| Cookie | __Host/Secure/HttpOnly/Lax/Path=/、hostonly、document.cookie不可读 |
| 重启持久 | SSO重启保持JDBC登录和相同JWKS |
| Key/metrics负例 | 错PKCS12密码拒，私有metrics无认证401/认证200，明文采集密码不进argv |
| DB故障 | readiness503/liveness200，恢复readiness200、账号登录保持 |
| 日志/元信息 | 容器日志/Docker运行config无精确fixture密码secret、有界日志、maintenance:none |
| 浏览器 | 所测流程无JS pageerror |

实际运行修模板缺陷：tmpfs逗号整项加引号、Nginx全部temp移/tmp、加proxyhealth。WindowsPTY的CRLF使第二次隐藏密码读空，改一次回车；保Linux交互console/密码规则。

## 镜像安全结果与限制

Trivy0.75.0，漏洞库更新 `2026-10-08T15:33:46.578791096Z`；Java映射库mirror.gcr.io/ghcr.io下载均unexpectedEOF，明确复用 `2026-10-06T01:08:12.735023878Z` 映射。本次当前漏洞库+旧包映射覆盖，不等最终release全部刷新。

| 目标 | 识别包行 | 报告发现 |
| --- | --- | --- |
| SSO image | 246 OS/Java | Medium40/Low4，报告无High/Critical |
| 原PG18.4 Alpine | 57 | Critical1/High31/Medium33/Low20/Unknown2，OS可修及旧Gogosu |
| 原Nginx1.29 Alpine | 72 | High44/Medium83/Low50/Unknown2，系统包可修 |
| 修proxy image | 71 | 零报告 |
| 修PG归档 | 58 | Critical1/High21/Medium21/Low2/Unknown1，基础层旧Go binary元信息 |
| 修PG实际rootfs | 54 installed包 | 零报告，gosu为 `/sbin/su-exec` symlink，无旧Go executable |
| 修proxy实际rootfs | 71 | 零报告 |

pull后上游digest未变；Bookworm备选也扫出更多High/Critical，未换发行版。构建时Alpine更新修OS，官方PG入口原gosu路径到su-exec；实际空卷/重启已验。保留归档原始发现，不抑制；实际rootfs区分已替换基础层元信息与运行暴露，不因scannerexit0当零。

SSO剩OS Medium/Low（含可修freetype）在最终候选前需审查/更新、目标当前复扫；零报告不证明无漏洞。未宣称目标authenticatedDAST、公共CA/续期/firewall、容量或生产OS/JDK验收。

## 证据保管与剩余任务

私密报告/一次性harness在批准外部临时区 `p06-compose-verification`、`p06-compose-final`、`p06-compose-patched`、`p06-image-scans`、`p06-infrastructure-build`、`p06-tooling`，serverreceipt忽略 `.build-docker/image-build.json`。最终发行须长期保脱敏count/hash证据，不仅临时文件夹。收尾核三个项目均无container/network/volume，外部key/secret/config/private-state删除；dump验后删除，未操作production。

Gitleaks8.30.1 `--redact=100` 扫562当前source候选、零报告，另核fixture精确secret无runtime日志/metadata。五Python工具编译及实际Alpine Shell语法通过；仓库核546文本/1290本地links/三锁/Wrapper/16迁移，历史包再验70files/104links。模式扫描有检测边界，数量不证明无所有缺陷；source总数是本记录最后文字编辑前。

Windows bind不证明Linux UID/ACL。域名/硬件/流量/backup调度位置保留/RPO/RTO/渠道/存放窗口见矩阵待定；真实collector/通知、加密异机备份/恢复新keyepoch/操作员、代表迁移时间空间、最终扫描仍门禁。未来消费方各自P11/P12。
