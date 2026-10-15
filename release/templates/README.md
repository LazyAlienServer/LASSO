# LAS SSO {{VERSION}}

**{{DELIVERY_ZH}} / {{DELIVERY_EN}}**

Verify at the extracted package root / 在解压包根校验：

```sh
python scripts/verify-package.py
```

[中文手册](docs/zh-CN/README.md) · [English handbook](docs/en/README.md) · [Changes](CHANGELOG.md)

Default deployment: SSO/PostgreSQL containers and one external host Nginx, loopback app publication, private management/database. 首选同机单层外部Nginx；容器镜像另构建/加载。

| Path | Contents |
| --- | --- |
| application/ | Executable SSO |
| sdk/maven/ | Exact Starter JAR/POM/native metadata |
| examples/reference-bff/ | Sample BFF, not a business backend |
| docs/ and api/ | Shared bilingual guides and OpenAPI |
| config/, deploy/, scripts/ | External-input templates and operator tools |
| provenance/, database/, source/ | Hashes, build/scan identity, embedded migrations and clean source |

Use this package's source/manifest/evidence, not moving main. Software delivery does not approve public deployment. No real secrets/keys/backups are included. 生产域名/HTTPS/保管/通知/恢复/容量另行验收；当前范围见ROADMAP.md。
