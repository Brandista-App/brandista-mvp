param([switch]$Build)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$env:JAVA_HOME = Split-Path (Split-Path (Get-Command java).Source -Parent) -Parent
if (!(Test-Path .env.m01)) {
    $randomSecret = { [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)) }
    @(
        'MYSQL_DATABASE=brandista_m01'
        'MYSQL_USER=brandista_m01_app'
        "MYSQL_PASSWORD=$(& $randomSecret)"
        "MYSQL_ROOT_PASSWORD=$(& $randomSecret)"
        'MYSQL_PORT=3308'
        'BACKEND_PORT=8082'
        'SERVER_PORT=8080'
        "JWT_SECRET_BASE64=$(& $randomSecret)"
        'JWT_ISSUER=brandista-m01'
        'JWT_AUDIENCE=brandista-m01-api'
        'JWT_ACCESS_TOKEN_TTL=PT15M'
        'CORS_ALLOWED_ORIGINS=http://127.0.0.1:5176'
    ) | Set-Content .env.m01
}
$settings = @{}
Get-Content .env.m01 | ForEach-Object {
    if ($_ -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
}
if ($settings.MYSQL_DATABASE -ne 'brandista_m01' -or $settings.MYSQL_PORT -ne '3308' -or $settings.BACKEND_PORT -ne '8082') {
    throw 'M0.1 requires its dedicated brandista_m01 database and loopback ports 3308/8082'
}
docker compose --env-file .env.m01 -p brandista-m01 up -d mysql
if ($LASTEXITCODE -ne 0) { throw 'M0.1 MySQL startup failed' }
$ready = $false
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    docker compose --env-file .env.m01 -p brandista-m01 exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -N -e "SELECT 1"' *> $null
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Seconds 2
}
if (!$ready) { throw 'Application-user MySQL connectivity failed; preserve the volume and inspect credentials' }
if ($Build -or !(Test-Path target/brandista-0.0.1-SNAPSHOT.jar)) {
    & .\mvnw.cmd package '-DskipTests'
    if ($LASTEXITCODE -ne 0) { throw 'Backend packaging failed' }
}
$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://127.0.0.1:3308/brandista_m01?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
$env:SPRING_DATASOURCE_USERNAME = $settings.MYSQL_USER
$env:SPRING_DATASOURCE_PASSWORD = $settings.MYSQL_PASSWORD
$env:JWT_SECRET_BASE64 = $settings.JWT_SECRET_BASE64
$env:JWT_ISSUER = $settings.JWT_ISSUER
$env:JWT_AUDIENCE = $settings.JWT_AUDIENCE
$env:JWT_ACCESS_TOKEN_TTL = $settings.JWT_ACCESS_TOKEN_TTL
$env:CORS_ALLOWED_ORIGINS = $settings.CORS_ALLOWED_ORIGINS
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '8082'
$env:COD_CHECKOUT_ENABLED = 'true'
Write-Host 'M0.1 real MySQL backend: http://127.0.0.1:8082; no pre-created customer or admin accounts.'
& java -jar target/brandista-0.0.1-SNAPSHOT.jar
if ($LASTEXITCODE -ne 0) { throw 'M0.1 backend exited with an error' }
