$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$schema = docker compose --env-file .env.m01 -p brandista-m01 exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -N -e "SELECT DATABASE(); SELECT COUNT(*) FROM flyway_schema_history WHERE success=1;"'
if ($LASTEXITCODE -ne 0 -or $schema[0] -ne 'brandista_m01' -or [int]$schema[1] -ne 9) {
    throw 'Expected dedicated M0.1 MySQL database with all nine migrations; start backend first'
}
Get-Content scripts/m01-catalogue.sql -Raw | docker compose --env-file .env.m01 -p brandista-m01 exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -N'
if ($LASTEXITCODE -ne 0) { throw 'Catalogue seed failed; do not reset the volume' }
