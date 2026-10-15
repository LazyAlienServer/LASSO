"""Expand the explicit source-to-package manifest; render declared text only."""
import json
import shutil
from pathlib import Path, PurePosixPath

from release_version import template_values


def relative_path(value):
    if not isinstance(value, str):
        raise ValueError("Package manifest paths must be strings")
    path = PurePosixPath(value)
    if not path.parts or "\\" in value or path.is_absolute() or ".." in path.parts or ":" in value:
        raise ValueError("Unsafe package manifest path")
    return path


def entries(root, manifest=None, candidates=None):
    root = root.resolve()
    data = json.loads((manifest or root / "release/package-files.json").read_text(encoding="utf-8"))
    if data.get("schemaVersion") != 1:
        raise ValueError("Unsupported package manifest version")
    selected = {}

    def add(source, target, render):
        relative_path(source)
        if candidates is not None and source not in candidates:
            raise ValueError("Package input is not a reviewed source candidate")
        target = relative_path(target).as_posix()
        direct = root / source
        resolved = direct.resolve()
        if (not resolved.is_relative_to(root) or not direct.is_file()
                or any(path.is_symlink() for path in (direct, *direct.parents))):
            raise ValueError("Package input missing or leaves source tree")
        if "__pycache__" in direct.parts or direct.suffix in {".pyc", ".pyo"}:
            raise ValueError("Generated Python input in package manifest")
        folded = target.casefold()
        if any(folded == name.casefold() or folded.startswith(name.casefold() + "/")
               or name.casefold().startswith(folded + "/") for name in selected):
            raise ValueError("Duplicate or conflicting package target")
        if not isinstance(render, bool) or render and direct.suffix == ".py":
            raise ValueError("Executable Python inputs must be copied unchanged")
        selected[target] = (direct, render)

    for row in data["files"]:
        add(row["source"], row["target"], row["render"])
    for row in data["trees"]:
        directory = root / relative_path(row["source"])
        relative_path(row["target"])
        pattern = row["pattern"]
        if (not isinstance(pattern, str) or not pattern or pattern.startswith("/")
                or ".." in PurePosixPath(pattern).parts or "\\" in pattern or ":" in pattern):
            raise ValueError("Unsafe package manifest glob")
        matched = [path for path in sorted(directory.glob(pattern)) if path.is_file()
                   and path.relative_to(directory).as_posix() not in row.get("exclude", [])]
        if not matched:
            raise ValueError("Package manifest tree selected no files")
        for path in matched:
            add(path.relative_to(root).as_posix(), (PurePosixPath(row["target"]) / path.relative_to(directory).as_posix()).as_posix(), row["render"])
    return [(source, target, render) for target, (source, render) in sorted(selected.items())]


def copy_files(root, stage, version, candidates=None):
    values = template_values(version)
    stage = stage.resolve()
    for source, target, render in entries(root, candidates=candidates):
        destination = stage / target
        if (destination.exists() or not destination.resolve().is_relative_to(stage)
                or any(path.is_symlink() for path in (destination, *destination.parents))):
            raise ValueError("Package destination exists or leaves staging")
        destination.parent.mkdir(parents=True, exist_ok=True)
        if render:
            text = source.read_text(encoding="utf-8")
            for token, value in values.items():
                text = text.replace(token, value)
            destination.write_text(text, encoding="utf-8", newline="\n")
        else:
            shutil.copyfile(source, destination)
