# Docker 部署文件入口

[English](README.md) · **[完整 Docker 部署教程](../../../release/_source/docs/zh-CN/docker-deployment.md)**

首发拓扑：单机 Compose、同机 Nginx、独立 SSO/PostgreSQL，初期无 CDN。

## 先读哪一份

| 任务 | 入口 |
| --- | --- |
| 从解压包首次安装 | [Docker 部署教程](../../../release/_source/docs/zh-CN/docker-deployment.md) |
| 维护、管理员重置、升级 | [运维](../../../release/_source/docs/zh-CN/operations.md) |
| 启动或登录失败 | [排障](../../../release/_source/docs/zh-CN/troubleshooting.md) |
| 核已确认环境输入 | [环境矩阵](../../../docs/zh-CN/operations/docker-compose-deployment.md) |

源码中的手册是发行模板，`{{VERSION}}` 在打包时替换。实际解压包已有精确版本，无需源码或 Git。

## 本目录文件

| 文件 | 用途 |
| --- | --- |
| `compose.yml` | SSO、PG、Nginx 与隔离维护服务 |
| `Dockerfile` | 消费精确 JAR/hash 构建非 root SSO |
| `postgres.Dockerfile` / `proxy.Dockerfile` | 固定基础镜像的构建时安全更新 |
| `deployment.env.example` | 非秘密变量；域名、路径、镜像和限额需填写 |
| `server.properties.example` | 容器 app/management 配置和 configtree |
| `postgres-init.sh` | 仅空卷创建独立 DB 角色和默认权限 |
| `postgres-runtime-permissions.sql` | 每次迁移后设 runtime Flyway 历史只读 |
| `server-entrypoint.sh` | 固定 Web / 离线维护启动边界 |
| `proxy-entrypoint.sh` / `nginx.conf.template` | canonical TLS 入口和转发头覆盖 |

## 源码构建入口

**执行位置：仓库根。** 先完成新版本的干净严格构建。

```text
python scripts/package_release.py build <new-version>
python scripts/build_docker_image.py --jar <exact-server.jar> --expected-sha256 <trusted-hash> --version <new-version> --tag <chosen-tag> --build-evidence <build-evidence.json>
python scripts/build_docker_infrastructure.py --postgres-tag <db-tag> --proxy-tag <proxy-tag> --output-dir <external-existing-parent/new-receipts>
```

| 检查 | 预期 |
| --- | --- |
| 来源、版本、JAR 摘要 | 与干净构建证据一致 |
| Compose 镜像 | 用精确 image ID/digest；不是可变 tag |
| app/DB/management | 无 host 端口发布 |
| 密码维护 | 真实隐藏输入 TTY；无密码参数 |

`--allow-dirty-fixture` 仅开发演练。域名、Linux UID/ACL、TLS、备份、通知和容量需目标验收。
