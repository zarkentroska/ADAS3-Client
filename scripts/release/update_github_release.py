#!/usr/bin/env python3
"""Sube un APK a un release existente de GitHub."""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

import requests


REPO_ROOT = Path(__file__).resolve().parents[2]


def resolve_token(explicit_token: str | None) -> str | None:
    if explicit_token:
        return explicit_token
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        return token
    token_file = REPO_ROOT / ".github_token"
    if token_file.exists():
        return token_file.read_text(encoding="utf-8").strip()
    return None


def find_apk(explicit_apk: str | None) -> Path | None:
    if explicit_apk:
        apk_path = Path(explicit_apk)
        return apk_path if apk_path.exists() else None
    candidates = sorted(REPO_ROOT.rglob("*.apk"), key=lambda p: p.stat().st_mtime, reverse=True)
    return candidates[0] if candidates else None


def main() -> int:
    parser = argparse.ArgumentParser(description="Actualiza asset APK de un release.")
    parser.add_argument("--repo", required=True, help="Formato owner/repo.")
    parser.add_argument("--tag", required=True, help="Tag del release (ej. v0.5Alpha).")
    parser.add_argument("--asset-name", default="ADAS3-Client.apk", help="Nombre del asset en GitHub.")
    parser.add_argument("--apk", help="Ruta al APK local (opcional).")
    parser.add_argument("--token", help="Token GitHub (opcional).")
    args = parser.parse_args()

    token = resolve_token(args.token)
    if not token:
        print("No se encontró token de GitHub. Usa --token o GITHUB_TOKEN.")
        return 1

    apk_file = find_apk(args.apk)
    if not apk_file:
        print("No se encontró APK para subir.")
        return 1

    headers = {
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github+json",
    }

    release_url = f"https://api.github.com/repos/{args.repo}/releases/tags/{args.tag}"
    release_resp = requests.get(release_url, headers=headers, timeout=20)
    if release_resp.status_code != 200:
        print(f"No se encontró release '{args.tag}': {release_resp.status_code}")
        print(release_resp.text[:300])
        return 1

    release = release_resp.json()
    release_id = release["id"]
    print(f"Release encontrado: {release_id}")

    assets_url = f"https://api.github.com/repos/{args.repo}/releases/{release_id}/assets"
    assets_resp = requests.get(assets_url, headers=headers, timeout=20)
    assets_resp.raise_for_status()
    for asset in assets_resp.json():
        if asset.get("name") == args.asset_name:
            delete_url = f"https://api.github.com/repos/{args.repo}/releases/assets/{asset['id']}"
            requests.delete(delete_url, headers=headers, timeout=20).raise_for_status()
            print(f"Asset anterior eliminado: {args.asset_name}")
            break

    upload_url = (
        f"https://uploads.github.com/repos/{args.repo}/releases/{release_id}/assets?name={args.asset_name}"
    )
    upload_headers = {
        **headers,
        "Content-Type": "application/vnd.android.package-archive",
    }
    with apk_file.open("rb") as apk_stream:
        upload_resp = requests.post(upload_url, headers=upload_headers, data=apk_stream, timeout=600)
    if upload_resp.status_code != 201:
        print(f"Error subiendo APK: {upload_resp.status_code}")
        print(upload_resp.text[:300])
        return 1

    print(f"APK subido correctamente: {apk_file}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
