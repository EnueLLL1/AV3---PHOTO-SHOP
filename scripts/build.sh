#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_ROOT"

if [[ ! -d "src/main/java" ]]; then
  echo "Erro: diretorio src/main/java nao encontrado." >&2
  exit 1
fi

mapfile -t MAIN_SOURCES < <(find src/main/java -type f -name '*.java' | sort)
if [[ ${#MAIN_SOURCES[@]} -eq 0 ]]; then
  echo "Erro: nenhum arquivo .java encontrado em src/main/java." >&2
  exit 1
fi

mkdir -p out/main

echo "[build] Compilando ${#MAIN_SOURCES[@]} arquivos de src/main/java -> out/main"
javac --release 21 -Xlint:all -Werror -cp "lib/jocl-2.0.5.jar:tools/mpj/lib/mpj.jar" -d out/main "${MAIN_SOURCES[@]}"

if [[ -d "src/main/resources" ]] && [[ -n "$(ls -A src/main/resources)" ]]; then
  echo "[build] Copiando recursos de src/main/resources -> out/main"
  cp -R src/main/resources/. out/main/
fi

if [[ -d "src/test/java" ]]; then
  mapfile -t TEST_SOURCES < <(find src/test/java -type f -name '*.java' | sort)
  if [[ ${#TEST_SOURCES[@]} -gt 0 ]]; then
    mkdir -p out/test
    echo "[build] Compilando ${#TEST_SOURCES[@]} arquivos de src/test/java -> out/test"
    javac --release 21 -Xlint:all -Werror -cp "out/main:lib/jocl-2.0.5.jar:tools/mpj/lib/mpj.jar:lib/junit-platform-console-standalone-1.10.2.jar" -d out/test "${TEST_SOURCES[@]}"
  else
    echo "[build] Nenhum .java em src/test/java; etapa de testes ignorada."
  fi
else
  echo "[build] Diretorio src/test/java ausente; etapa de testes ignorada."
fi

echo "[build] Concluido com sucesso."
