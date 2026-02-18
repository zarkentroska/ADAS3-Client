# Scripts de mantenimiento

Este repositorio agrupa los scripts utilitarios dentro de `scripts/` para mantener limpia la raíz.

## `scripts/dev/`

- `accept_android_licenses.sh`: acepta licencias de Android SDK sin interacción.
- `git_sync.py`: hace stage, commit (si procede), luego ejecuta `pull --rebase` y `push`.

## `scripts/release/`

- `update_github_release.py`: sube un asset APK a un release existente en GitHub.

## Notes

- Estos scripts resuelven dinámicamente la raíz del repositorio.
- Mantén credenciales fuera de git (`.github_token` o `GITHUB_TOKEN`).
