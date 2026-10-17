$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force target | Out-Null
$previousRootPassword=$env:MYSQL_ROOT_PASSWORD
$containerName='brandista-admin-pr1-migration-check'
if(docker ps -aq --filter "name=^$containerName$"){throw 'An existing verification container must not be overwritten'}
$env:MYSQL_ROOT_PASSWORD=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$containerId=$null
function Invoke-VerificationSql([string]$sql) {
    $result=$sql | docker exec -i $containerId sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot "$MYSQL_DATABASE" -N'
    if($LASTEXITCODE -ne 0){throw 'Isolated MySQL SQL execution failed'}
    return $result
}
try {
    $containerId=docker run --rm -d --name $containerName --network none --label brandista.task=admin-pr1-migration --env MYSQL_ROOT_PASSWORD --env MYSQL_DATABASE=brandista_pr1_verify mysql:8.4.11-oraclelinux9
    if($LASTEXITCODE -ne 0){throw 'Cannot start isolated MySQL verification container'}
    Write-Output 'Started disposable MySQL 8.4 verification container: no published ports or existing volumes.'
    $ready=$false
    for($attempt=0;$attempt -lt 45;$attempt++) {
        docker exec $containerId sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot "$MYSQL_DATABASE" -N -e "SELECT 1"' *> $null
        if($LASTEXITCODE -eq 0){$ready=$true;break}
        Start-Sleep -Seconds 2
    }
    if(!$ready){throw 'Isolated MySQL did not become ready'}
    $migrations=Get-ChildItem src/main/resources/db/migration/V*__*.sql | Where-Object Name -Match '^V[1-9]__' | Sort-Object { [int]($_.Name -replace '^V(\d+)__.*','$1') }
    $baseline=($migrations | ForEach-Object { Get-Content $_.FullName -Raw }) -join "`n"
    Invoke-VerificationSql $baseline
    Invoke-VerificationSql "INSERT INTO users(email,password_hash,role,status,created_at,updated_at) VALUES ('customer@example.invalid','isolated-test-only-hash','CUSTOMER','ENABLED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),('admin@example.invalid','isolated-test-only-hash','ADMIN','ENABLED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));"
    $before=Invoke-VerificationSql 'SELECT id,email,password_hash,role,status,created_at,updated_at FROM users ORDER BY id;'
    Invoke-VerificationSql (Get-Content src/main/resources/db/migration/V10__create_platform_grants.sql -Raw)
    $after=Invoke-VerificationSql 'SELECT id,email,password_hash,role,status,created_at,updated_at FROM users ORDER BY id;'
    if(($before -join "`n") -ne ($after -join "`n")){throw 'V10 modified existing user data'}
    $count=Invoke-VerificationSql 'SELECT COUNT(*) FROM user_platform_grants;'
    if([int]$count -ne 0){throw 'V10 created an automatic grant'}
    Invoke-VerificationSql "INSERT INTO user_platform_grants(user_id,grant_type,created_at) SELECT id,'SUPER_ADMIN',UTC_TIMESTAMP(6) FROM users WHERE role='CUSTOMER';"
    $rejected=0
    foreach($sql in @(
        "INSERT INTO user_platform_grants(user_id,grant_type,created_at) SELECT id,'SUPER_ADMIN',UTC_TIMESTAMP(6) FROM users WHERE role='CUSTOMER';",
        "INSERT INTO user_platform_grants(user_id,grant_type,created_at) VALUES (999999,'SUPER_ADMIN',UTC_TIMESTAMP(6));",
        "INSERT INTO user_platform_grants(user_id,grant_type,created_at) SELECT id,'BRAND_ADMIN',UTC_TIMESTAMP(6) FROM users WHERE role='ADMIN';"
    )) {
        $sql | docker exec -i $containerId sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot "$MYSQL_DATABASE" -N' *> $null
        if($LASTEXITCODE -eq 0){throw 'Expected database constraint was not enforced'}
        $rejected++
    }
    $version=Invoke-VerificationSql 'SELECT VERSION();'
    [ordered]@{engine=$version;baselineMigrations=9;upgrade='V10';existingCustomerAndAdminUnchanged=$true;automaticGrants=0;duplicateForeignKeyAndBrandAdminRejected=($rejected -eq 3);scope='SQL upgrade and constraints, not full MySQL application acceptance';network='none';existingVolumesUsed=$false} | ConvertTo-Json | Set-Content target/mysql-migration-check.json
    Write-Output 'PASS: MySQL V1-V9 -> V10; users unchanged; no grants backfilled; uniqueness/FK/grant-type checks enforced.'
} finally {
    if($null -eq $previousRootPassword){Remove-Item Env:MYSQL_ROOT_PASSWORD -ErrorAction SilentlyContinue}
    else {$env:MYSQL_ROOT_PASSWORD=$previousRootPassword}
    if($containerId){docker stop $containerId | Out-Null; Write-Output 'Disposable verification container removed.'}
}
