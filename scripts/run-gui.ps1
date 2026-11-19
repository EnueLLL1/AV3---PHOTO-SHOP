<#
.SYNOPSIS
    Inicia a Interface Grafica de Apresentacao (estudio.gui.PresentationApp).
#>

$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$PresentationClass = Join-Path $ProjectRoot 'out\main\estudio\gui\PresentationApp.class'

Push-Location $ProjectRoot
try {
    if (-not (Test-Path -LiteralPath $PresentationClass)) {
        & (Join-Path $PSScriptRoot 'build.ps1')
    }

    & java -Xmx3g -cp "out/main;lib/jocl-2.0.5.jar;tools/mpj/lib/*" estudio.gui.PresentationApp @args
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
