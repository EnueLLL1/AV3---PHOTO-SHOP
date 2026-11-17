#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
LIB_DIR="$PROJECT_ROOT/lib"
TOOLS_DIR="$PROJECT_ROOT/tools"
MPJ_DIR="$TOOLS_DIR/mpj"
MPJ_LIB_DIR="$MPJ_DIR/lib"

mkdir -p "$LIB_DIR" "$TOOLS_DIR"

download_if_missing() {
  local url="$1"
  local dest="$2"
  local user_agent="${3:-}"

  if [[ -f "$dest" ]]; then
    echo "[skip] Ja existe: $dest"
    return 0
  fi

  echo "[download] $url -> $dest"
  if [[ -n "$user_agent" ]]; then
    curl -fSL -A "$user_agent" "$url" -o "$dest"
  else
    curl -fSL "$url" -o "$dest"
  fi
}

JOCL_JAR="$LIB_DIR/jocl-2.0.5.jar"
JUNIT_JAR="$LIB_DIR/junit-platform-console-standalone-1.10.2.jar"
MPJ_ZIP="$TOOLS_DIR/mpj-v0_44.zip"

download_if_missing \
  "https://repo1.maven.org/maven2/org/jocl/jocl/2.0.5/jocl-2.0.5.jar" \
  "$JOCL_JAR"

download_if_missing \
  "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/1.10.2/junit-platform-console-standalone-1.10.2.jar" \
  "$JUNIT_JAR"

download_if_missing \
  "https://sourceforge.net/projects/mpjexpress/files/releases/mpj-v0_44.zip/download" \
  "$MPJ_ZIP" \
  "Wget/1.21"

if [[ ! -f "$MPJ_LIB_DIR/mpj.jar" ]]; then
  if [[ "$(head -c 2 "$MPJ_ZIP" | od -An -tx1 | tr -d ' \n')" != "504b" ]]; then
    echo "Erro: '$MPJ_ZIP' nao parece um arquivo ZIP valido (possivel pagina HTML do SourceForge)." >&2
    exit 1
  fi

  echo "[extract] Extraindo $MPJ_ZIP em $TOOLS_DIR"
  if command -v unzip >/dev/null 2>&1; then
    unzip -qo "$MPJ_ZIP" -d "$TOOLS_DIR"
  elif command -v bsdtar >/dev/null 2>&1; then
    bsdtar -xf "$MPJ_ZIP" -C "$TOOLS_DIR"
  elif command -v python3 >/dev/null 2>&1; then
    python3 -m zipfile -e "$MPJ_ZIP" "$TOOLS_DIR"
  else
    echo "Erro: 'unzip', 'bsdtar' ou 'python3' necessario para extrair $MPJ_ZIP." >&2
    exit 1
  fi

  if [[ -d "$TOOLS_DIR/mpj-v0_44" && ! -d "$MPJ_DIR" ]]; then
    echo "[move] $TOOLS_DIR/mpj-v0_44 -> $MPJ_DIR"
    mv "$TOOLS_DIR/mpj-v0_44" "$MPJ_DIR"
  elif [[ ! -d "$MPJ_DIR" ]]; then
    CANDIDATE="$(find "$TOOLS_DIR" -maxdepth 1 -mindepth 1 -type d -name 'mpj*' | head -n 1 || true)"
    if [[ -n "$CANDIDATE" ]]; then
      echo "[move] $CANDIDATE -> $MPJ_DIR"
      mv "$CANDIDATE" "$MPJ_DIR"
    fi
  fi
fi

if [[ ! -f "$MPJ_LIB_DIR/mpj.jar" ]]; then
  echo "Erro: mpj.jar nao encontrado em '$MPJ_LIB_DIR'." >&2
  exit 1
fi

for jar_name in mpj.jar starter.jar daemon.jar niodev.jar smpdev.jar; do
  src="$MPJ_LIB_DIR/$jar_name"
  dst="$LIB_DIR/$jar_name"
  if [[ -f "$src" ]]; then
    cp -f "$src" "$dst"
    echo "[copy] $src -> $dst"
  else
    echo "[info] Opcional ausente em tools/mpj/lib: $jar_name"
  fi
done

echo ""
echo "=== Resumo de JARs em lib/ ==="
ls -lh "$LIB_DIR"/*.jar 2>/dev/null || echo "  (nenhum JAR encontrado)"

echo ""
echo "=== Resumo de JARs em tools/mpj/lib/ ==="
ls -lh "$MPJ_LIB_DIR"/*.jar 2>/dev/null || echo "  (nenhum JAR encontrado)"
