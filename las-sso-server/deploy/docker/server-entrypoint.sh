#!/bin/sh
set -eu
mode="${LAS_SSO_RUN_MODE:-web}"
case "$mode" in
  web)
    if [ "$#" -ne 0 ]; then
      echo 'Web mode takes no command-line overrides; use reviewed external configuration.' >&2
      exit 2
    fi
    exec java -jar /opt/las-sso/server.jar --spring.profiles.active=production,monitoring
    ;;
  maintenance)
    command="${1:-}"
    case "$command" in
      database-migrate|admin-bootstrap|admin-reset-password|rotate-security-epoch-after-restore) ;;
      *) echo 'A supported offline maintenance command is required.' >&2; exit 2 ;;
    esac
    shift
    for argument in "$@"; do
      case "$command:$argument" in
        admin-bootstrap:--username=*|admin-bootstrap:--user-id=*|rotate-security-epoch-after-restore:--expected-epoch=*|rotate-security-epoch-after-restore:--reason=*) ;;
        *) echo 'Unsupported maintenance argument; passwords must use the hidden console.' >&2; exit 2 ;;
      esac
    done
    exec java -jar /opt/las-sso/server.jar \
      --spring.profiles.active=production --spring.main.web-application-type=none \
      --las.sso.scheduling.enabled=false --spring.session.jdbc.cleanup-cron=- \
      --las.sso.monitoring.enabled=false "--las.sso.maintenance.command=$command" "$@"
    ;;
  *) echo 'Invalid SSO process mode.' >&2; exit 2 ;;
esac
