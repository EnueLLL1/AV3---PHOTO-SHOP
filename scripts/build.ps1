<#
.SYNOPSIS
    Compila os fontes Java 21 (main e test) e copia recursos para out/main.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$MainSrcDir = Join-Path $ProjectRoot 'src\main\java'
$ResourcesDir = Join-Path $ProjectRoot 'src\main\resources'
$TestSrcDir = Join-Path $ProjectRoot 'src\test\java'
$OutMainDir = Join-Path $ProjectRoot 'out\main'
$OutTestDir = Join-Path $ProjectRoot 'out\test'

Push-Location $ProjectRoot
try {
    if (-not (Test-Path -LiteralPath $MainSrcDir)) {
        throw "Diretorio de fontes nao encontrado: $MainSrcDir"
    }

    $mainSources = Get-ChildItem -LiteralPath $MainSrcDir -Recurse -Filter '*.java' |
        ForEach-Object { $_.FullName }

    if (-not $mainSources -or $mainSources.Count -eq 0) {
        throw "Nenhum arquivo .java encontrado em $MainSrcDir"
    }

    New-Item -ItemType Directory -Force -Path $OutMainDir | Out-Null

    Write-Host "[build] Compilando $($mainSources.Count) arquivos de src/main/java -> out/main"
    & javac --release 21 -Xlint:all -Werror -cp "lib/jocl-2.0.5.jar;tools/mpj/lib/mpj.jar" -d out/main @mainSources
    if ($LASTEXITCODE -ne 0) {
        throw "Falha na compilacao de src/main/java (exit code $LASTEXITCODE)."
    }

    if (Test-Path -LiteralPath $ResourcesDir) {
        $resourceItems = Get-ChildItem -LiteralPath $ResourcesDir -Force
        if ($resourceItems.Count -gt 0) {
            Write-Host "[build] Copiando recursos de src/main/resources -> out/main"
            Copy-Item -Path (Join-Path $ResourcesDir '*') -Destination $OutMainDir -Recurse -Force
        }
    }

    if (Test-Path -LiteralPath $TestSrcDir) {
        $testSources = Get-ChildItem -LiteralPath $TestSrcDir -Recurse -Filter '*.java' |
            ForEach-Object { $_.FullName }

        if ($testSources -and $testSources.Count -gt 0) {
            New-Item -ItemType Directory -Force -Path $OutTestDir | Out-Null
            Write-Host "[build] Compilando $($testSources.Count) arquivos de src/test/java -> out/test"
            & javac --release 21 -Xlint:all -Werror -cp "out/main;lib/jocl-2.0.5.jar;tools/mpj/lib/mpj.jar;lib/junit-platform-console-standalone-1.10.2.jar" -d out/test @testSources
            if ($LASTEXITCODE -ne 0) {
                throw "Falha na compilacao de src/test/java (exit code $LASTEXITCODE)."
            }
        } else {
            Write-Host "[build] Nenhum .java em src/test/java; etapa de testes ignorada."
        }
    } else {
        Write-Host "[build] Diretorio src/test/java ausente; etapa de testes ignorada."
    }

    Write-Host "[build] Concluido com sucesso."
}
finally {
    Pop-Location
}
