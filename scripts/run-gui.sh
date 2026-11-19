#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_ROOT"

if [[ ! -f "out/main/estudio/gui/PresentationApp.class" ]]; then
  ./scripts/build.sh
fi

exec java -Xmx3g -cp "out/main:lib/jocl-2.0.5.jar:tools/mpj/lib/*" estudio.gui.PresentationApp "$@"
