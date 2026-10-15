# LAS SSO

[English](README.en.md) · [下载](https://github.com/LazyAlienServer/LASSO/releases) · [中文手册](docs/zh-CN/README.md)

基于Java21、Spring Boot和PostgreSQL的身份服务，提供账号/密码恢复、OIDC Code+PKCE、SSO会话、账号生命周期及第一方服务资格。业务成员/角色/权限由接入应用管理。

## 开始使用

| 目的 | 入口 |
| --- | --- |
| 安装发行包 | [部署](docs/zh-CN/deployment.md) → [配置](docs/zh-CN/configuration.md) |
| 接入业务后端 | [接入](docs/zh-CN/integration.md) |
| 使用或管理账号 | [用户](docs/zh-CN/users.md)、[管理员](docs/zh-CN/administrators.md) |
| 运行、监控、恢复 | [运维](docs/zh-CN/operations.md)、[监控](docs/zh-CN/monitoring.md)、[恢复](docs/zh-CN/recovery.md) |
| 调用API | [API说明](docs/zh-CN/api.md)、[OpenAPI](api/openapi.json) |
| 开发贡献 | [CONTRIBUTING](CONTRIBUTING.md) |

最新发行候选为[0.1.0-rc.1](https://github.com/LazyAlienServer/LASSO/releases/tag/v0.1.0-rc.1)。当前限制和下一步集中在[ROADMAP](ROADMAP.md)，版本变化见[CHANGELOG](CHANGELOG.md)。

## 本地开发

需要JDK21、Docker及Python3.10+。在`las-sso-server/`执行：

```powershell
docker compose up -d
.\gradlew.bat bootRun --args="--spring.profiles.active=local"
```

Linux使用`./gradlew`。SSO为`http://127.0.0.1:9000`，参考BFF另运行`:las-sso-reference-bff:bootRun`、端口8088。local使用临时key和示例凭据，仅隔离开发。

首次创建开发管理员时，先停止 bootRun、保留数据库。在真实 PowerShell 终端执行，构建失败即停止：

```powershell
.\gradlew.bat bootJar --no-daemon --console=plain
$jar = (Resolve-Path './build/libs/las-sso-server-0.1.0-SNAPSHOT.jar').Path
$hash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
./scripts/bootstrap-admin.ps1 -Username InitialAdmin -Profile local -JarPath $jar -ExpectedSha256 $hash
```

密码隐藏输入；退出码 0 后重新启动 bootRun，按用户指南 U03 修改临时密码。已有管理员不重复初始化。维护脚本默认 production，开发环境必须显式传 `-Profile local`。

## 目录

| 路径 | 用途 |
| --- | --- |
| `las-sso-server/` | 唯一三模块Gradle构建：server、Starter、参考BFF |
| `docs/en/`、`docs/zh-CN/` | 唯一当前指南，发行包复用 |
| `docs/adr/`、`docs/archive/` | 决策与精选历史证据 |
| `api/` | 已实现HTTP接口规格 |
| `scripts/` | 校验、镜像和发行工具 |
| `release/` | 显式包清单、模板与版本验收摘要 |

真实配置/key/密码/备份放源码和包外；生成制品使用GitHub Release附件。已发布版本保来源与字节，不以当前源码覆盖历史验收。
