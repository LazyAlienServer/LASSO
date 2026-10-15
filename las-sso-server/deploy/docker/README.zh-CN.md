# 容器模板

[English](README.md) · [部署指南](../../../docs/zh-CN/deployment.md)

- compose.host-nginx.yml及host env：默认同机外部入口。
- compose.yml及bundled env：内置代理替代。
- server.properties：容器监听/configtree。
- PG初始化/权限SQL：离线migrator/runtime分离。
- Dockerfile/entrypoint：镜像和launcher输入，不是真实配置。

单独选择一种布局，已有安装保project/volume。操作、秘密生成、升级恢复统一见手册。
