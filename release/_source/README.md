# LAS SSO {{VERSION}}

**独立开发发行包 / Development delivery**

## 从这里开始 / Start here

| 你要做什么 / Task | 中文 | English |
| --- | --- | --- |
| 首次用 Docker 部署 | [Docker 部署教程](docs/zh-CN/docker-deployment.md) | [Docker deployment](docs/en/docker-deployment.md) |
| 查找故障 | [排障速查](docs/zh-CN/troubleshooting.md) | [Troubleshooting](docs/en/troubleshooting.md) |
| 重置管理员、升级、重启 | [运维操作](docs/zh-CN/operations.md) | [Operations](docs/en/operations.md) |
| 备份或恢复旧数据库 | [备份与恢复](docs/zh-CN/backup-and-recovery.md) | [Backup and recovery](docs/en/backup-and-recovery.md) |
| 接入业务网站 | [接入步骤](docs/zh-CN/external-integration.md) | [Integration](docs/en/external-integration.md) |
| 查看所有手册 | [中文目录](docs/zh-CN/README.md) | [Handbook index](docs/en/README.md) |

## 第一步：验证文件 / First: verify files

在解压后的包根目录执行 / Run from the extracted package root:

```text
python scripts/verify-package.py
```

**预期 / Expected:** `Verified {{VERSION}}`，exit code `0`。

| 结果 / Result | 下一步 / Next |
| --- | --- |
| 验证通过 / Passed | 选择上面的操作教程 / Choose a task above |
| 摘要、缺失或多余文件错误 / Integrity failure | 重新取得完整包；保留原包 / Obtain an intact package |

## 包里有什么 / Contents

| 路径 / Path | 内容 / Contents |
| --- | --- |
| `application/` | SSO 可执行 JAR / Executable server |
| `sdk/maven/` | Starter JAR、POM、Gradle metadata / File Maven repository |
| `examples/reference-bff/` | 参考消费方 / Reference consumer |
| `docs/zh-CN/`、`docs/en/` | 任务型手册 / Task-oriented handbooks |
| `deploy/docker/` | Compose、Dockerfile、容器配置模板 / Container templates |
| `deploy/monitoring/` | Prometheus、Alertmanager、Grafana 配置 / Monitoring inputs |
| `scripts/` | 校验、镜像构建、维护、只读报告 / Operator tools |
| `source/` | 本版本干净源码快照 / Clean source snapshot |
| `provenance/` | 来源、测试、扫描、摘要 / Provenance and evidence |

发行变更：[中文](RELEASE-NOTES.zh-CN.md) / [English](RELEASE-NOTES.md)。

**部署范围：** 单机 Compose、同机 Nginx、SSO 账号站首发；容器镜像需另构建或加载。

**Release scope:** Single-host Compose, same-host Nginx, account-site first launch; build/load images separately.

本包不含真实密码、私钥、证书、恢复码或数据库备份。SHA-256 校验是完整性检查；目标环境验收见[中文](docs/zh-CN/acceptance.md) / [English](docs/en/acceptance.md)。
