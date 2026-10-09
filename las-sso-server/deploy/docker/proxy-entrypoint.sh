#!/bin/sh
set -eu
case "${LAS_SSO_HOST:-}" in
  ''|*[!a-z0-9.-]*|*.example|example.com|*.example.com|example.net|*.example.net|example.org|*.example.org|localhost|*.localhost)
    echo 'A non-example canonical DNS hostname is required.' >&2; exit 2 ;;
esac
case "${LAS_SSO_HTTPS_PORT:-}" in ''|*[!0-9]*) echo 'An external HTTPS port is required.' >&2; exit 2 ;; esac
[ "$LAS_SSO_HTTPS_PORT" -ge 1 ] && [ "$LAS_SSO_HTTPS_PORT" -le 65535 ] || exit 2
envsubst '${LAS_SSO_HOST} ${LAS_SSO_HTTPS_PORT}' < /etc/las-sso/nginx.conf.template > /tmp/nginx.conf
nginx -t -c /tmp/nginx.conf
exec nginx -c /tmp/nginx.conf -g 'daemon off;'
