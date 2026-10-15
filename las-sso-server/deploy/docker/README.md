# Container templates

[中文](README.zh-CN.md) · [Deployment guide](../../../docs/en/deployment.md)

- compose.host-nginx.yml / deployment.host-nginx.env.example: default external host edge.
- compose.yml / deployment.env.example: bundled-proxy alternative.
- server.properties.example: shared internal bindings/configtree.
- postgres-init.sh / runtime-permissions SQL: offline migrator/runtime separation.
- Dockerfiles and entrypoints: image/launcher inputs, not real settings.

Select one standalone layout; preserve existing project/volume when adapting. All commands, secret generation and upgrade/restore steps live in the canonical handbook.
