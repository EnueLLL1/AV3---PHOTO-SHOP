<#
.SYNOPSIS
    Prepara as dependencias locais (JOCL, JUnit Console, MPJ Express) em lib/ e tools/.
.DESCRIPTION
    Executa apenas no ambiente Windows. Baixa os artefatos somente se ainda nao existirem.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$LibDir = Join-Path $ProjectRoot 'lib'
$ToolsDir = Join-Path $ProjectRoot 'tools'
$MpjDir = Join-Path $ToolsDir 'mpj'
$MpjLibDir = Join-Path $MpjDir 'lib'

New-Item -ItemType Directory -Force -Path $LibDir, $ToolsDir | Out-Null

function Invoke-DownloadIfMissing {
    param(
        [Parameter(Mandatory)] [string]$Url,
        [Parameter(Mandatory)] [string]$Destination,
        [string]$UserAgent
    )

    if (Test-Path -LiteralPath $Destination) {
        Write-Host "[skip] Ja existe: $Destination"
        return
    }

    Write-Host "[download] $Url -> $Destination"
    $params = @{
        Uri             = $Url
        OutFile         = $Destination
        UseBasicParsing = $true
    }
    if ($UserAgent) {
        $params['UserAgent'] = $UserAgent
    }

    Invoke-WebRequest @params
}

$joclJar = Join-Path $LibDir 'jocl-2.0.5.jar'
$junitJar = Join-Path $LibDir 'junit-platform-console-standalone-1.10.2.jar'
$mpjZip = Join-Path $ToolsDir 'mpj-v0_44.zip'

Invoke-DownloadIfMissing `
    -Url 'https://repo1.maven.org/maven2/org/jocl/jocl/2.0.5/jocl-2.0.5.jar' `
    -Destination $joclJar

Invoke-DownloadIfMissing `
    -Url 'https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/1.10.2/junit-platform-console-standalone-1.10.2.jar' `
    -Destination $junitJar

Invoke-DownloadIfMissing `
    -Url 'https://sourceforge.net/projects/mpjexpress/files/releases/mpj-v0_44.zip/download' `
    -Destination $mpjZip `
    -UserAgent 'Wget/1.21'

if (-not (Test-Path -LiteralPath (Join-Path $MpjLibDir 'mpj.jar'))) {
    # Garante que o arquivo baixado do SourceForge e realmente um ZIP (assinatura PK)
    $header = [System.IO.File]::ReadAllBytes($mpjZip)
    if ($header.Length -lt 2 -or $header[0] -ne 0x50 -or $header[1] -ne 0x4B) {
        throw "O arquivo '$mpjZip' nao parece um ZIP valido (possivel redirecionamento HTML do SourceForge)."
    }

    Write-Host "[extract] Expandindo $mpjZip em $ToolsDir"
    Expand-Archive -LiteralPath $mpjZip -DestinationPath $ToolsDir -Force

    $extractedSubdir = Join-Path $ToolsDir 'mpj-v0_44'
    if (-not (Test-Path -LiteralPath $extractedSubdir)) {
        $candidate = Get-ChildItem -LiteralPath $ToolsDir -Directory -Filter 'mpj*' |
            Where-Object { $_.Name -ne 'mpj' } |
            Select-Object -First 1
        if ($candidate) {
            $extractedSubdir = $candidate.FullName
        }
    }

    if ((Test-Path -LiteralPath $extractedSubdir) -and -not (Test-Path -LiteralPath $MpjDir)) {
        Write-Host "[move] $extractedSubdir -> $MpjDir"
        Move-Item -LiteralPath $extractedSubdir -Destination $MpjDir
    }
}

if (-not (Test-Path -LiteralPath (Join-Path $MpjLibDir 'mpj.jar'))) {
    throw "mpj.jar nao encontrado em '$MpjLibDir' apos extracao."
}

$mpjJarsToCopy = @('mpj.jar', 'starter.jar', 'daemon.jar', 'niodev.jar', 'smpdev.jar')
foreach ($jarName in $mpjJarsToCopy) {
    $src = Join-Path $MpjLibDir $jarName
    $dst = Join-Path $LibDir $jarName
    if (Test-Path -LiteralPath $src) {
        Copy-Item -LiteralPath $src -Destination $dst -Force
        Write-Host "[copy] $src -> $dst"
    } else {
        Write-Host "[info] Opcional ausente em tools/mpj/lib: $jarName"
    }
}

Write-Host "`n=== Resumo de JARs em lib/ ==="
Get-ChildItem -LiteralPath $LibDir -Filter '*.jar' | Sort-Object Name | ForEach-Object {
    Write-Host ("  - {0} ({1:N0} bytes)" -f $_.Name, $_.Length)
}

Write-Host "`n=== Resumo de JARs em tools/mpj/lib/ ==="
Get-ChildItem -LiteralPath $MpjLibDir -Filter '*.jar' | Sort-Object Name | ForEach-Object {
    Write-Host ("  - {0} ({1:N0} bytes)" -f $_.Name, $_.Length)
}
