<#
.SYNOPSIS
    Script central: verifica dependencias, compila, roda toda a suite JUnit 5
    e executa a bancada end-to-end (Coordenador RMI/TCP + BENCHMARK_ALL + concorrência).
#>
[CmdletBinding()]
param(
    [switch]$Full,
    [ValidateSet('BLUR', 'SHARPEN', 'SOBEL')]
    [string]$Filter = 'SOBEL',
    [int]$ConcurrentClients = 4,
    [switch]$SkipBenchmark
)

$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$RmiPort = 1099
$TcpPort = 9090

Push-Location $ProjectRoot
try {
    $requiredDeps = @(
        'lib\jocl-2.0.5.jar',
        'lib\junit-platform-console-standalone-1.10.2.jar',
        'tools\mpj\lib\mpj.jar'
    )
    $missing = $requiredDeps | Where-Object { -not (Test-Path -LiteralPath $_) }
    if ($missing.Count -gt 0) {
        Write-Host "[run-all] Dependencias ausentes ($($missing -join ', ')). Executando setup-deps.ps1..."
        & (Join-Path $PSScriptRoot 'setup-deps.ps1')
        if ($LASTEXITCODE -ne 0) {
            throw "Falha no setup-deps.ps1 (exit code $LASTEXITCODE)."
        }
    }

    Write-Host "`n[1/4] Build (main + test)..."
    & (Join-Path $PSScriptRoot 'build.ps1')
    if ($LASTEXITCODE -ne 0) {
        throw "Falha no build.ps1 (exit code $LASTEXITCODE)."
    }

    Write-Host "`n[2/4] Executando suite JUnit 5..."
    & java -jar "lib/junit-platform-console-standalone-1.10.2.jar" execute `
        --class-path "out/test;out/main;lib/jocl-2.0.5.jar;tools/mpj/lib/mpj.jar" `
        --scan-class-path
    if ($LASTEXITCODE -ne 0) {
        throw "Testes JUnit falharam (exit code $LASTEXITCODE)."
    }

    if ($SkipBenchmark) {
        Write-Host "`n[run-all] Concluido (benchmark ignorado via -SkipBenchmark)."
        exit 0
    }

    $count = if ($Full) { 20 } else { 4 }
    $width = if ($Full) { 4000 } else { 1200 }
    $height = if ($Full) { 3000 } else { 900 }

    $workDir = Join-Path $ProjectRoot 'work'
    New-Item -ItemType Directory -Force -Path $workDir | Out-Null
    $coordOutLog = Join-Path $workDir 'bench_coordinator.out.log'
    $coordErrLog = Join-Path $workDir 'bench_coordinator.err.log'
    $benchInput = 'work/bench_input'
    $benchOut = 'work/bench_out'
    $concurrentOut = 'work/concurrent_out'

    Write-Host "`n[3/4] Subindo CoordinatorMain (RMI:$RmiPort, TCP:$TcpPort)..."
    $coordArgs = @(
        '-Xmx3g',
        '-cp', 'out/main;lib/jocl-2.0.5.jar;tools/mpj/lib/*',
        'estudio.net.CoordinatorMain',
        '--host', '127.0.0.1',
        '--rmi-port', "$RmiPort",
        '--tcp-port', "$TcpPort"
    )
    $coordProc = Start-Process -FilePath 'java' -ArgumentList $coordArgs `
        -WorkingDirectory $ProjectRoot `
        -RedirectStandardOutput $coordOutLog `
        -RedirectStandardError $coordErrLog `
        -PassThru -NoNewWindow

    try {
        $ready = $false
        for ($i = 0; $i -lt 30; $i++) {
            if ($coordProc.HasExited) {
                $errText = if (Test-Path $coordErrLog) { Get-Content $coordErrLog -Raw } else { '' }
                throw "CoordinatorMain encerrou inesperadamente (exit $($coordProc.ExitCode)).`n$errText"
            }
            try {
                $tcp = [System.Net.Sockets.TcpClient]::new()
                $tcp.Connect('127.0.0.1', $TcpPort)
                $tcp.Close()
                $ready = $true
                break
            }
            catch {
                Start-Sleep -Milliseconds 500
            }
        }
        if (-not $ready) {
            throw "Timeout aguardando CoordinatorMain abrir a porta TCP $TcpPort."
        }

        Write-Host "[3/4] Executando BENCHMARK_ALL ($count imagens ${width}x${height}, filtro $Filter)..."
        & (Join-Path $PSScriptRoot 'run-client.ps1') `
            --host 127.0.0.1 --rmi-port $RmiPort `
            --mode BENCHMARK_ALL --filter $Filter `
            --generate --count $count --width $width --height $height `
            --input $benchInput --output $benchOut
        if ($LASTEXITCODE -ne 0) {
            throw "BENCHMARK_ALL falhou (exit code $LASTEXITCODE)."
        }

        Write-Host "`n[4/4] Executando teste de concorrencia (--concurrent-demo $ConcurrentClients)..."
        & (Join-Path $PSScriptRoot 'run-client.ps1') `
            --host 127.0.0.1 --rmi-port $RmiPort `
            --filter $Filter `
            --input $benchInput --output $concurrentOut `
            --concurrent-demo $ConcurrentClients
        if ($LASTEXITCODE -ne 0) {
            throw "Teste de concorrencia falhou (exit code $LASTEXITCODE)."
        }
    }
    finally {
        if ($coordProc -and -not $coordProc.HasExited) {
            Write-Host "`n[run-all] Encerrando CoordinatorMain (PID $($coordProc.Id))..."
            Stop-Process -Id $coordProc.Id -Force -ErrorAction SilentlyContinue
            $coordProc.WaitForExit(3000) | Out-Null
        }
    }

    Write-Host "`n[run-all] Toda a bancada passou com sucesso!"
}
finally {
    Pop-Location
}
