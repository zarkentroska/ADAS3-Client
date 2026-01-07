#!/usr/bin/env python3
"""
Actualiza el repositorio Git: agrega archivos nuevos y elimina duplicados innecesarios
"""
import os
import sys
import subprocess
from pathlib import Path

PROJECT = Path("/home/zarkentroska/others/ADAS3 Android Client")
os.chdir(PROJECT)

print("="*70)
print("  ACTUALIZANDO REPOSITORIO GIT - ADAS3 Android Client")
print("="*70)

def run_git_command(cmd, check=True):
    """Ejecuta un comando git y retorna el resultado"""
    try:
        result = subprocess.run(
            ["git"] + cmd,
            cwd=str(PROJECT),
            capture_output=True,
            text=True,
            check=check
        )
        return result
    except subprocess.CalledProcessError as e:
        print(f"  ✗ Error ejecutando git {' '.join(cmd)}: {e.stderr}")
        return e
    except Exception as e:
        print(f"  ✗ Error: {e}")
        return None

# 1. Verificar estado
print("\n[1/6] Verificando estado de Git...")
status = run_git_command(["status", "--porcelain"], check=False)
if status and status.stdout:
    print("  Archivos modificados/no rastreados encontrados")
else:
    print("  Sin cambios detectados")

# 2. Agregar archivos nuevos importantes
print("\n[2/6] Agregando archivos nuevos...")
files_to_add = [
    "actualizar_github.py",
    ".gitignore",
    "README.md",
    "LICENSE",
    "accept_sdk_licenses.py",
    "ghlogo.png",
]

added = []
for f in files_to_add:
    p = PROJECT / f
    if p.exists():
        result = run_git_command(["add", f], check=False)
        if result and result.returncode == 0:
            added.append(f)
            print(f"  ✓ Agregado: {f}")

# 3. Agregar todos los archivos nuevos (excepto los ignorados)
print("\n[3/6] Agregando todos los cambios...")
result = run_git_command(["add", "-A"], check=False)
if result and result.returncode == 0:
    print("  ✓ Todos los cambios agregados")
else:
    print("  ⚠ Error agregando cambios")

# 4. Verificar qué se va a commitear
print("\n[4/6] Verificando cambios para commit...")
status = run_git_command(["status", "--short"], check=False)
has_changes = False
if status and status.stdout and status.stdout.strip():
    print("  Cambios detectados:")
    for line in status.stdout.strip().split('\n'):
        if line.strip():
            print(f"    {line}")
            has_changes = True
else:
    print("  ℹ No hay cambios nuevos para commitear")
    has_changes = False

# Verificar si hay cambios sin commitear que puedan bloquear el rebase
unstaged = run_git_command(["diff", "--name-only"], check=False)
staged = run_git_command(["diff", "--cached", "--name-only"], check=False)
if (unstaged and unstaged.stdout and unstaged.stdout.strip()) or (staged and staged.stdout and staged.stdout.strip()):
    print("  ℹ Hay cambios sin commitear, asegurándose de que todo esté commiteado...")
    has_changes = True

# 5. Commit (si hay cambios)
print("\n[5/7] Haciendo commit...")
commit_msg = "Actualización: mejoras, nuevos scripts y configuración"

if has_changes:
    # Asegurarse de que todos los cambios estén agregados
    result = run_git_command(["add", "-A"], check=False)
    
    # Commit
    result = run_git_command(["commit", "-m", commit_msg], check=False)
    if result and result.returncode == 0:
        print("  ✓ Commit realizado")
        if result.stdout:
            print(f"    {result.stdout.strip()[:100]}")
    else:
        if result and result.stdout and "nothing to commit" in result.stdout.lower():
            print("  ℹ No hay cambios para commitear")
        else:
            print("  ⚠ Error en commit")
            if result and result.stderr:
                print(f"    {result.stderr.strip()[:200]}")
else:
    print("  ℹ No hay cambios nuevos para commitear")

# 6. Pull y Push
print("\n[6/7] Actualizando desde remoto y haciendo push...")

# Detectar la rama actual
branch_result = run_git_command(["rev-parse", "--abbrev-ref", "HEAD"], check=False)
current_branch = None
if branch_result and branch_result.returncode == 0:
    current_branch = branch_result.stdout.strip()
    print(f"  ✓ Rama actual: {current_branch}")
else:
    # Intentar método alternativo
    branch_result = run_git_command(["branch", "--show-current"], check=False)
    if branch_result and branch_result.returncode == 0:
        current_branch = branch_result.stdout.strip()
        print(f"  ✓ Rama actual: {current_branch}")

if not current_branch:
    # Intentar con ramas comunes
    for branch in ["main", "master"]:
        branch_result = run_git_command(["rev-parse", "--verify", f"origin/{branch}"], check=False)
        if branch_result and branch_result.returncode == 0:
            current_branch = branch
            print(f"  ✓ Usando rama: {current_branch}")
            break

# Pull primero para sincronizar con rebase para evitar conflictos
if current_branch:
    print(f"\n  Haciendo pull con rebase desde origin/{current_branch}...")
    # Primero fetch para obtener cambios remotos
    fetch_result = run_git_command(["fetch", "origin", current_branch], check=False)
    
    # Luego hacer rebase para reconciliar ramas divergentes
    result = run_git_command(["pull", "--rebase", "origin", current_branch], check=False)
    if result and result.returncode == 0:
        print(f"  ✓ Pull con rebase exitoso desde origin/{current_branch}")
        if result.stdout:
            output = result.stdout.strip()
            if "Already up to date" in output or "ya está actualizado" in output.lower() or "current branch is up to date" in output.lower():
                print("    (Ya estaba actualizado)")
            else:
                print(f"    {output[:200]}")
    else:
        # Si el rebase falla, intentar merge
        print("  Rebase falló, intentando merge...")
        result = run_git_command(["pull", "--no-rebase", "origin", current_branch], check=False)
        if result and result.returncode == 0:
            print(f"  ✓ Pull con merge exitoso")
        else:
            print("  ⚠ Error en pull (puede haber conflictos)")
            if result and result.stderr:
                stderr = result.stderr.strip()
                print(f"    {stderr[:300]}")
                if "conflict" in stderr.lower() or "conflicto" in stderr.lower():
                    print("\n  ⚠ Hay conflictos que necesitan resolverse manualmente")
                    print("  Ejecuta manualmente:")
                    print(f"    git pull --rebase origin {current_branch}")
                    print("    (resuelve conflictos si los hay)")
                    print(f"    git push origin {current_branch}")

# Push
print("\n[7/7] Haciendo push...")
if current_branch:
    result = run_git_command(["push", "origin", current_branch], check=False)
    if result and result.returncode == 0:
        print(f"  ✓ Push realizado exitosamente a origin/{current_branch}")
        if result.stdout:
            for line in result.stdout.strip().split('\n'):
                if line.strip() and not line.startswith('Enumerating'):
                    print(f"    {line}")
    else:
        print("  ⚠ Error en push")
        if result and result.stderr:
            print(f"    {result.stderr.strip()[:200]}")
        print("\n  Sugerencia: Puede haber conflictos. Intenta ejecutar:")
        print(f"    git pull origin {current_branch}")
        print(f"    git push origin {current_branch}")
else:
    print("  ⚠ No se pudo detectar la rama actual")
    print("  Ejecuta manualmente: git pull && git push")

print("\n" + "="*70)
print("  ¡COMPLETADO!")
print("="*70)
print(f"\nRepositorio: https://github.com/zarkentroska/ADAS3-Android-Client\n")

