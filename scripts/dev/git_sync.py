#!/usr/bin/env python3
"""Sincroniza rama actual: add/commit/pull --rebase/push."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


def run_git(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=REPO_ROOT,
        text=True,
        capture_output=True,
        check=check,
    )


def current_branch() -> str:
    result = run_git("rev-parse", "--abbrev-ref", "HEAD")
    return result.stdout.strip()


def has_changes() -> bool:
    result = run_git("status", "--porcelain", check=False)
    return bool(result.stdout.strip())


def main() -> int:
    parser = argparse.ArgumentParser(description="Sincroniza cambios de Git.")
    parser.add_argument(
        "-m",
        "--message",
        default="chore: mantenimiento del repositorio",
        help="Mensaje de commit.",
    )
    args = parser.parse_args()

    if not (REPO_ROOT / ".git").exists():
        print("No se detecta un repositorio Git en el directorio esperado.")
        return 1

    branch = current_branch()
    print(f"Rama actual: {branch}")

    run_git("add", "-A")
    if has_changes():
        commit = run_git("commit", "-m", args.message, check=False)
        if commit.returncode == 0:
            print("Commit creado correctamente.")
        elif "nothing to commit" in commit.stdout.lower():
            print("Sin cambios para commit.")
        else:
            print(commit.stdout.strip())
            print(commit.stderr.strip())
            return commit.returncode
    else:
        print("No hay cambios para commit.")

    pull = run_git("pull", "--rebase", "origin", branch, check=False)
    if pull.returncode != 0:
        print("Falló pull --rebase:")
        print(pull.stderr.strip() or pull.stdout.strip())
        return pull.returncode

    push = run_git("push", "origin", branch, check=False)
    if push.returncode != 0:
        print("Falló push:")
        print(push.stderr.strip() or push.stdout.strip())
        return push.returncode

    print("Sincronización completada.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
