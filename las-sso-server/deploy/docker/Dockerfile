ARG JAVA_IMAGE=eclipse-temurin:21-jre@sha256:cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5
FROM ${JAVA_IMAGE}
ARG SERVER_JAR
ARG SERVER_SHA256
ARG RELEASE_VERSION
ARG SOURCE_REVISION
LABEL org.opencontainers.image.title="LAS SSO Server" \
      org.opencontainers.image.version="${RELEASE_VERSION}" \
      org.opencontainers.image.revision="${SOURCE_REVISION}" \
      org.las.sso.jar.sha256="${SERVER_SHA256}"
WORKDIR /opt/las-sso
COPY ${SERVER_JAR} /opt/las-sso/server.jar
COPY deploy/docker/server-entrypoint.sh /opt/las-sso/server-entrypoint.sh
RUN test -n "$RELEASE_VERSION" && test -n "$SOURCE_REVISION" \
    && test "${#SERVER_SHA256}" -eq 64 \
    && printf '%s  %s\n' "$SERVER_SHA256" /opt/las-sso/server.jar | sha256sum -c - \
    && groupadd --gid 10001 las-sso && useradd --uid 10001 --gid 10001 --no-create-home las-sso \
    && chmod 0555 /opt/las-sso/server-entrypoint.sh && chmod 0444 /opt/las-sso/server.jar
USER 10001:10001
EXPOSE 9000 9091
STOPSIGNAL SIGTERM
ENTRYPOINT ["/opt/las-sso/server-entrypoint.sh"]
