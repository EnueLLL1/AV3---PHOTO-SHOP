<#
.SYNOPSIS
    Inicia o Cliente CLI do Estudio de Imagens (estudio.net.ClientMain).
#>

$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$OutMainDir = Join-Path $ProjectRoot 'out\main'

if (-not (Test-Path -LiteralPath $OutMainDir)) {
    throw "Diretorio 'out/main' nao encontrado. Execute .\scripts\build.ps1 antes."
}

Push-Location $ProjectRoot
try {
    & java -Xmx2g -cp "out/main;lib/jocl-2.0.5.jar;tools/mpj/lib/*" estudio.net.ClientMain @args
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
