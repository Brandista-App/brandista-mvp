$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$source = Join-Path (Split-Path (Get-Location) -Parent) 'brandista-frontend'
$destination = Join-Path (Get-Location) 'target/m01-frontend'
New-Item -ItemType Directory -Force "$destination/src" | Out-Null
Copy-Item "$source/src/*" "$destination/src" -Recurse -Force
Copy-Item "$source/package.json", "$source/package-lock.json", "$source/index.html", "$source/vite.config.ts", "$source/tsconfig*.json", "$source/eslint.config.js" $destination -Force
Set-Location $destination
if (!(Test-Path node_modules/vite/bin/vite.js)) {
    & npm.cmd ci --ignore-scripts --offline --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed; rerun npm ci without --offline if registry access is needed' }
}
$env:VITE_API_BASE_URL = 'http://127.0.0.1:8082/api/v1'
Write-Host 'M0.1 owner registration: http://127.0.0.1:5176/register (real API/MySQL; no mock server)'
& npm.cmd run dev -- --host 127.0.0.1 --port 5176 --strictPort
if ($LASTEXITCODE -ne 0) { throw 'M0.1 frontend exited with an error' }
