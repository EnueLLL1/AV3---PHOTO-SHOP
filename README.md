# Estúdio de Imagens Distribuído (AV3 — Java 21)

Serviço distribuído de processamento em lote de imagens de alta resolução desenvolvido em **Java 21** (equipe de 5 integrantes), integrando **Java RMI**, **Sockets TCP**, **fila concorrente**, **threads (linhas e blocos 2D)**, **MPJ Express** (`multicore` e `niodev`), **GPU/OpenCL** (JOCL + PoCL/GPU), **verificação pixel a pixel** e **tabela comparativa de desempenho** (Tempo, Speedup e Eficiência).

---

## 1. Visão Geral da Arquitetura

```text
+-------------------+        Java RMI (controle/status/relatório)        +------------------------+
|  ClientMain (CLI) | <------------------------------------------------> |    CoordinatorMain     |
|  - Envia imagens  |        TCP Socket (payload binário + progresso)    |  - Fila concorrente    |
|  - Recebe ZIP/PNG | <================================================> |  - Limite simultâneo   |
+-------------------+                                                    +-----------+------------+
                                                                                     |
                        +--------------------+-------------------+-------------------+-------------------+
                        |                    |                   |                   |                   |
                        v                    v                   v                   v                   v
                 SEQUENTIAL           THREADS_ROWS        THREADS_BLOCKS       MPJ (multicore /       GPU (OpenCL
              (referência 1T)         (faixas Y)           (tiles 2D)            niodev via MPI)       via JOCL)
```

### Componentes principais
- **RMI (`CoordinatorRmiService` / `CoordinatorRmiImpl`)**: submissão de jobs (`JobRequest` -> `JobTicket`), consulta de estado (`JobStatus`) e retorno de `BenchmarkReport`.
- **Socket TCP (`TcpProtocol`)**: transferência binária de imagens de entrada/saída e streaming de eventos de progresso em tempo real sem gargalo no RMI.
- **Fila Concorrente no Coordenador**: recebe múltiplos clientes em paralelo, enfileira tarefas e limita execuções simultâneas sem race conditions nem deadlocks.
- **5 Motores (`estudio.engine`)**:
  1. `SEQUENTIAL` (`SequentialEngine`): execução single-thread de referência.
  2. `THREADS_ROWS` (`ThreadedRowsEngine`): paralelismo por faixas horizontais de linhas.
  3. `THREADS_BLOCKS` (`ThreadedBlocksEngine`): paralelismo por blocos/tiles 2D.
  4. `MPJ` (`MpjSubprocessEngine` + `MpjWorkerMain`): comunicação ponto a ponto (`Send`/`Recv` de faixas com halo) e coletivas (`Bcast`, `Barrier`, `Reduce`) montando o resultado no Rank 0, **sem exigir NFS** entre nós. Suporta `multicore` (local) e `niodev` (cluster).
  5. `GPU` (`OpenClGpuEngine` + `kernels/filters.cl`): execução via JOCL em GPU dedicada/integrada ou CPU via PoCL.
- **3 Filtros (`FilterType` / `ConvolutionOps` / `filters.cl`)**:
  - `BLUR` (Gaussian 5x5)
  - `SHARPEN` (3x3)
  - `SOBEL` (3x3 com `isqrt` inteiro)
  - Todos usam **aritmética inteira determinística** e tratamento de bordas **Clamp-to-Edge**, garantindo **paridade pixel a pixel (`0` diferenças)** validada pelo `PixelVerifier` entre todos os motores.

---

## 2. Pré-requisitos

1. **JDK 21** (`java -version` e `javac -version` >= 21).
2. **Dependências locais (`lib/` e `tools/mpj/`)**:
   - **Windows (PowerShell):**
     ```powershell
     .\scripts\setup-deps.ps1
     ```
   - **Linux (Bash):**
     ```bash
     chmod +x scripts/*.sh
     ./scripts/setup-deps.sh
     ```
   Isso prepara `lib/jocl-2.0.5.jar`, `lib/junit-platform-console-standalone-1.10.2.jar` e `tools/mpj` (MPJ Express v0.44).
3. **OpenCL / PoCL no Linux do laboratório (caso a máquina não tenha GPU com driver OpenCL):**
   ```bash
   sudo apt update
   sudo apt install -y pocl-opencl-icd ocl-icd-libopencl1 clinfo
   clinfo -l
   ```

---

## 3. Build (Compilação Offline)

- **Windows:**
  ```powershell
  .\scripts\build.ps1
  ```
- **Linux:**
  ```bash
  ./scripts/build.sh
  ```

O build compila `src/main/java` para `out/main` com `javac --release 21 -Xlint:all -Werror`, copia `src/main/resources/*` (incluindo `kernels/filters.cl`) para `out/main/` e, quando houver testes em `src/test/java`, compila para `out/test`.

Para rodar a suíte JUnit 5 (quando `out/test` existir):
- **Windows:**
  ```powershell
  java -jar lib/junit-platform-console-standalone-1.10.2.jar execute --class-path "out/test;out/main;lib/jocl-2.0.5.jar;tools/mpj/lib/mpj.jar" --scan-class-path
  ```
- **Linux:**
  ```bash
  java -jar lib/junit-platform-console-standalone-1.10.2.jar execute --class-path "out/test:out/main:lib/jocl-2.0.5.jar:tools/mpj/lib/mpj.jar" --scan-class-path
  ```

---

## 4. Execução Local (Windows e Linux)

### 4.1 Subir o Coordenador
- **Windows:**
  ```powershell
  .\scripts\run-coordinator.ps1 --host 127.0.0.1
  ```
- **Linux:**
  ```bash
  ./scripts/run-coordinator.sh --host 127.0.0.1
  ```
*(Use `--help` no `CoordinatorMain` ou `ClientMain` para listar todas as opções de porta, workers e dispositivo MPI.)*

### 4.2 Gerar Dataset Sintético (`20 x 4000x3000`)
É possível gerar o dataset padrão determinístico de 20 imagens `4000x3000` pela classe `DatasetGenerator` (padrão: `--out data/input --count 20 --width 4000 --height 3000`):
- **Windows:**
  ```powershell
  java -cp "out/main" estudio.core.DatasetGenerator --out dataset_4k --count 20 --width 4000 --height 3000
  ```
- **Linux:**
  ```bash
  java -cp "out/main" estudio.core.DatasetGenerator --out dataset_4k --count 20 --width 4000 --height 3000
  ```

### 4.3 Executar Cliente e Modo `BENCHMARK_ALL`
Com o coordenador ativo em outro terminal, dispare o `BENCHMARK_ALL` (que executa `SEQUENTIAL`, `THREADS_ROWS`, `THREADS_BLOCKS`, `MPJ` e `GPU`, verifica paridade pixel a pixel com `SEQUENTIAL` e imprime a tabela de **Tempo / Speedup / Eficiência**):
- **Windows:**
  ```powershell
  .\scripts\run-client.ps1 --host 127.0.0.1 --mode BENCHMARK_ALL --filter SOBEL --input dataset_4k --output out_benchmark
  ```
- **Linux:**
  ```bash
  ./scripts/run-client.sh --host 127.0.0.1 --mode BENCHMARK_ALL --filter SOBEL --input dataset_4k --output out_benchmark
  ```

### 4.4 Teste de Concorrência (`--concurrent-demo`)
Para validar múltiplos clientes simultâneos na fila do coordenador (sem race condition nem deadlock):
- **Windows:**
  ```powershell
  .\scripts\run-client.ps1 --host 127.0.0.1 --input dataset_4k --output out_concurrent --concurrent-demo 4
  ```
- **Linux:**
  ```bash
  ./scripts/run-client.sh --host 127.0.0.1 --input dataset_4k --output out_concurrent --concurrent-demo 4
  ```

---

## 5. Passo a Passo — MPJ Express `-dev niodev` em 2+ Máquinas (Laboratório UNEB)

1. **Preparar as máquinas Linux:**
   - Garanta o **mesmo caminho** do repositório e de `tools/mpj` em todas as máquinas participantes (ex.: `/home/aluno/AV3-PHOTO-SHOP`).
   - Garanta JDK 21 e dependências (`./scripts/setup-deps.sh && ./scripts/build.sh`) em todas as máquinas.
   - Verifique conectividade de rede entre os IPs do laboratório e liberação das portas usadas pelo RMI/TCP e MPJ Daemon.

2. **Configurar `config/machines`:**
   ```bash
   cp config/machines.example config/machines
   nano config/machines
   ```
   Liste um IP real por linha (sem espaços extras), por exemplo:
   ```text
   192.168.1.101
   192.168.1.102
   ```

3. **Subir os daemons MPJ nas máquinas:**
   ```bash
   export MPJ_HOME="$(pwd)/tools/mpj"
   export PATH="$MPJ_HOME/bin:$PATH"

   # Com SSH sem senha configurado:
   "$MPJ_HOME/bin/mpjboot" config/machines

   # Ou informando os hosts diretamente:
   "$MPJ_HOME/bin/mpjdaemon" -boot 192.168.1.101 192.168.1.102
   ```
   *(Alternativamente, em cada máquina sem SSH automático, inicie o daemon manualmente conforme a documentação do MPJ Express.)*

4. **Iniciar o Coordenador na Máquina 0 (`niodev`):**
   ```bash
   ./scripts/run-lab-niodev.sh 192.168.1.101
   ```
   O script verifica `MPJ_HOME`, checa opcionalmente `clinfo` (PoCL), valida `config/machines` e sobe:
   ```bash
   java -Xmx3g -cp "out/main:lib/jocl-2.0.5.jar:tools/mpj/lib/*" \
     estudio.net.CoordinatorMain \
     --host 192.168.1.101 \
     --mpi-dev niodev \
     --mpi-machines config/machines
   ```

5. **Executar o Cliente (de qualquer máquina da rede do lab):**
   ```bash
   ./scripts/run-client.sh --host 192.168.1.101 --mode MPJ --mpi-np 4 --filter BLUR --input dataset_4k --output out_niodev
   ```
   Ou rodar `--mode BENCHMARK_ALL` para comparar os 5 motores distribuindo o MPJ nos nós do cluster.

6. **Encerrar os daemons ao finalizar:**
   ```bash
   "$MPJ_HOME/bin/mpjhalt" config/machines
   # ou: "$MPJ_HOME/bin/mpjdaemon" -halt 192.168.1.101 192.168.1.102
   ```

---

## 6. Execução GPU/OpenCL no Google Colab ou Kaggle

Caso queira demonstrar aceleração OpenCL em GPU dedicada (ex.: NVIDIA T4/P100) fora do laboratório:

1. **Instale JDK 21 e suporte ICD OpenCL no notebook:**
   ```bash
   !apt-get update -qq
   !apt-get install -y -qq openjdk-21-jdk ocl-icd-libopencl1 clinfo
   !clinfo -l
   ```
   *(Em ambientes NVIDIA no Colab/Kaggle, o driver CUDA geralmente já expõe `/etc/OpenCL/vendors/nvidia.icd`; caso contrário, instale `nvidia-opencl-icd` ou `pocl-opencl-icd` para fallback CPU.)*

2. **Envie/clone o projeto no ambiente e prepare dependências + build:**
   ```bash
   %cd /content/AV3-PHOTO-SHOP
   !chmod +x scripts/*.sh
   !./scripts/setup-deps.sh
   !./scripts/build.sh
   ```

3. **Execute o Coordenador em background e dispare o Cliente:**
   ```bash
   !nohup ./scripts/run-coordinator.sh --host 127.0.0.1 > coordinator.log 2>&1 &
   !sleep 2
   !java -cp "out/main" estudio.core.DatasetGenerator --out dataset_4k --count 20 --width 4000 --height 3000
   !./scripts/run-client.sh --host 127.0.0.1 --mode BENCHMARK_ALL --filter SOBEL --input dataset_4k --output out_colab
   ```
   Para forçar seleção de GPU no JOCL:
   `-Destudio.opencl.deviceType=GPU` (ou `CPU` para PoCL).

---

## 7. Estrutura de Diretórios

- `src/main/java/estudio/model/` — Contratos (`ExecutionMode`, `FilterType`, `JobRequest`, `JobTicket`, `JobStatus`, `BenchmarkReport`).
- `src/main/java/estudio/core/` — Buffers ARGB, `ConvolutionOps`, `DatasetGenerator` e `PixelVerifier`.
- `src/main/java/estudio/engine/` — Motores Sequencial, Threads (Linhas/Blocos), MPJ e OpenCL/JOCL.
- `src/main/java/estudio/net/` — Coordenador RMI/TCP, protocolo binário e Cliente CLI.
- `src/main/resources/kernels/filters.cl` — Kernels OpenCL (`BLUR`, `SHARPEN`, `SOBEL`).
- `scripts/` — Scripts de dependências, build e execução Windows/Linux.
- `config/machines.example` — Modelo de arquivo de nós para `niodev`.
