"""Shared delivery-version policy, also copied into independent release packages."""

import datetime as dt
import re

_NUMBER = r"(?:0|[1-9][0-9]*)"
_VERSION = re.compile(rf"{_NUMBER}\.{_NUMBER}\.{_NUMBER}(?:-dev\.([0-9]{{8}})\.([1-9][0-9]*)|-rc\.([1-9][0-9]*))?")


def delivery_kind(version):
    if not isinstance(version, str) or (match := _VERSION.fullmatch(version)) is None:
        raise ValueError("Use MAJOR.MINOR.PATCH, MAJOR.MINOR.PATCH-rc.N or MAJOR.MINOR.PATCH-dev.YYYYMMDD.N")
    if match.group(1):
        try:
            dt.datetime.strptime(match.group(1), "%Y%m%d")
        except ValueError as error:
            raise ValueError("Development version requires a valid calendar date") from error
        return "development-delivery"
    return "release-candidate" if match.group(3) else "software-release"


def template_values(version):
    kind = delivery_kind(version)
    english, chinese = {
        "development-delivery": ("Development delivery", "开发交付"),
        "release-candidate": ("Release candidate", "发行候选"),
        "software-release": ("Software release", "软件发行"),
    }[kind]
    return {"{{VERSION}}": version, "{{DELIVERY_EN}}": english, "{{DELIVERY_ZH}}": chinese}
