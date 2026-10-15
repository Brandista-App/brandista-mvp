$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$projectRoot = (Get-Location).Path
$shellPath = (Get-Command pwsh).Source
function Test-LocalUrl($url) {
    try { return (Invoke-WebRequest $url -TimeoutSec 3 -SkipHttpErrorCheck).StatusCode -eq 200 }
    catch { return $false }
}
function Start-LocalScript($name, $log) {
    Start-Process -FilePath $shellPath -ArgumentList @('-NoProfile', '-File', (Join-Path $PSScriptRoot $name)) -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $projectRoot "target/$log.out.log") -RedirectStandardError (Join-Path $projectRoot "target/$log.err.log") | Out-Null
}
New-Item -ItemType Directory -Force target | Out-Null
docker info --format '{{.ServerVersion}}' *> $null
if ($LASTEXITCODE -ne 0) {
    Start-Process -FilePath 'C:\Program Files\Docker\Docker\Docker Desktop.exe' -WindowStyle Hidden
    $engineReady = $false
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        docker info --format '{{.ServerVersion}}' *> $null
        if ($LASTEXITCODE -eq 0) { $engineReady = $true; break }
        Start-Sleep -Seconds 2
    }
    if (!$engineReady) { throw 'Docker Desktop engine did not start; see Docker Desktop diagnostics' }
}
if (!(Test-LocalUrl 'http://127.0.0.1:8082/actuator/health/readiness')) {
    Start-LocalScript 'start-m01-backend.ps1' 'm01-background-backend'
}
if (!(Test-LocalUrl 'http://127.0.0.1:5176/')) {
    Start-LocalScript 'start-m01-frontend.ps1' 'm01-background-frontend'
}
$appReady = $false
for ($attempt = 0; $attempt -lt 90; $attempt++) {
    if ((Test-LocalUrl 'http://127.0.0.1:8082/actuator/health/readiness') -and (Test-LocalUrl 'http://127.0.0.1:5176/')) { $appReady = $true; break }
    Start-Sleep -Seconds 2
}
if (!$appReady) { throw 'Application did not become ready; inspect target/m01-background-*.log' }
# Do not replace an existing process listening on the older demo port.
if (!(Test-NetConnection 127.0.0.1 -Port 5175 -InformationLevel Quiet -WarningAction SilentlyContinue)) {
    Start-Process -FilePath (Get-Command node).Source -ArgumentList @((Join-Path $PSScriptRoot 'm01-legacy-redirect.mjs')) -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $projectRoot 'target/m01-redirect.out.log') -RedirectStandardError (Join-Path $projectRoot 'target/m01-redirect.err.log') | Out-Null
}
Write-Host 'READY: http://127.0.0.1:5176/register (MySQL). Existing 5175 links redirect here.'
