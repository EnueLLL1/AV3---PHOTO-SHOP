#!/usr/bin/env bash
# Guia e script executor para rodar o Coordenador com MPJ Express (-dev niodev)
# em 2 ou mais maquinas Linux do laboratorio da UNEB.
#
# Uso:
#   ./scripts/run-lab-niodev.sh <IP_DA_MAQUINA_0> [opcoes extras do CoordinatorMain...]
# Exemplo:
#   ./scripts/run-lab-niodev.sh 192.168.1.101
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_ROOT"

# 1. Configuracao de MPJ_HOME (padrao: tools/mpj)
export MPJ_HOME="${MPJ_HOME:-$PROJECT_ROOT/tools/mpj}"
export PATH="$MPJ_HOME/bin:$PATH"

if [[ ! -f "$MPJ_HOME/lib/starter.jar" ]]; then
  echo "Erro: MPJ_HOME invalido ($MPJ_HOME). Execute ./scripts/setup-deps.sh antes." >&2
  exit 1
fi

if [[ ! -d "out/main" ]]; then
  echo "Erro: 'out/main' nao encontrado. Execute ./scripts/build.sh antes." >&2
  exit 1
fi

# 2. Verificacao opcional do runtime OpenCL / PoCL
echo "=== [1/4] Verificacao MPJ_HOME & OpenCL (PoCL) ==="
echo "MPJ_HOME = $MPJ_HOME"
if command -v clinfo >/dev/null 2>&1; then
  if clinfo -l 2>/dev/null; then
    echo "[ok] Plataformas/dispositivos OpenCL detectados."
  else
    echo "[aviso] clinfo executado, mas nenhuma plataforma OpenCL foi listada."
    echo "        Para habilitar PoCL no Linux: sudo apt install pocl-opencl-icd ocl-icd-libopencl1"
  fi
else
  echo "[info] 'clinfo' nao encontrado (opcional)."
  echo "       Se precisar de OpenCL em CPU no lab: sudo apt install pocl-opencl-icd ocl-icd-libopencl1 clinfo"
fi

# 3. Validacao de config/machines
echo ""
echo "=== [2/4] Verificacao de config/machines ==="
MACHINES_FILE="$PROJECT_ROOT/config/machines"
if [[ ! -f "$MACHINES_FILE" ]]; then
  echo "Erro: '$MACHINES_FILE' nao encontrado." >&2
  echo "Crie a partir do modelo e edite com os IPs reais das maquinas do lab:" >&2
  echo "  cp config/machines.example config/machines" >&2
  exit 1
fi

mapfile -t MACHINE_HOSTS < <(grep -Ev '^\s*(#|$)' "$MACHINES_FILE" | tr -d '\r' | xargs -n1 || true)
if [[ ${#MACHINE_HOSTS[@]} -lt 2 ]]; then
  echo "[aviso] config/machines tem menos de 2 maquinas listadas (${#MACHINE_HOSTS[@]})."
  echo "        Para demonstracao distribuida niodev, liste pelo menos 2 IPs em config/machines."
fi

# 4. Lembrete do daemon MPJ nas maquinas do cluster
echo ""
echo "=== [3/4] Lembrete MPJ Daemon (mpjdaemon -boot / mpjboot) ==="
echo "Certifique-se de que:"
echo "  a) O projeto e MPJ_HOME ($MPJ_HOME) existem no MESMO caminho em todas as maquinas."
echo "  b) Os daemons MPJ estao ativos nas maquinas de config/machines:"
echo "       $MPJ_HOME/bin/mpjdaemon -boot ${MACHINE_HOSTS[*]:-<IP_0> <IP_1>}"
echo "     (ou com SSH configurado: $MPJ_HOME/bin/mpjboot config/machines)"
echo "  c) Ao encerrar os testes, finalize os daemons com:"
echo "       $MPJ_HOME/bin/mpjdaemon -halt ${MACHINE_HOSTS[*]:-<IP_0> <IP_1>}"
echo "     (ou: $MPJ_HOME/bin/mpjhalt config/machines)"

# 5. Determina <IP_DA_MAQUINA_0>
HOST_IP="${1:-}"
if [[ -n "$HOST_IP" && "$HOST_IP" != -* ]]; then
  shift
elif [[ ${#MACHINE_HOSTS[@]} -gt 0 ]]; then
  HOST_IP="${MACHINE_HOSTS[0]}"
  echo ""
  echo "[info] Nenhum IP passado via argumento; usando o primeiro IP de config/machines: $HOST_IP"
else
  echo "Uso: $0 <IP_DA_MAQUINA_0> [argumentos extras do CoordinatorMain...]" >&2
  exit 1
fi

echo ""
echo "=== [4/4] Subindo CoordinatorMain (niodev) ==="
echo "Comando: java -Xmx3g -cp \"out/main:lib/jocl-2.0.5.jar:tools/mpj/lib/*\" estudio.net.CoordinatorMain --host $HOST_IP --mpi-dev niodev --mpi-machines config/machines $*"

exec java -Xmx3g -cp "out/main:lib/jocl-2.0.5.jar:tools/mpj/lib/*" \
  estudio.net.CoordinatorMain \
  --host "$HOST_IP" \
  --mpi-dev niodev \
  --mpi-machines config/machines \
  "$@"
