FROM postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15
# Repository security updates are resolved at build time, never during container startup.
# Retain the resulting image identity, package list and scan; a later build is new bytes.
RUN apk upgrade --no-cache \
    && apk add --no-cache su-exec \
    && rm /usr/local/bin/gosu \
    && ln -s /sbin/su-exec /usr/local/bin/gosu \
    && postgres --version
LABEL org.opencontainers.image.title="LAS SSO PostgreSQL security-update layer"
