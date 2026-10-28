$ErrorActionPreference = 'Stop'
$mavenCommand = if ($env:OS -eq 'Windows_NT') { Join-Path $PSScriptRoot '../mvnw.cmd' } else { 'sh' }
[string[]]$mavenPrefix = if ($env:OS -eq 'Windows_NT') { @() } else { @(Join-Path $PSScriptRoot '../mvnw') }
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force target | Out-Null
$containerName = 'brandista-admin-pr2-isolated-' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$priorRootSecret = $env:MYSQL_ROOT_PASSWORD
$priorTestUrl = $env:BRANDISTA_PR2_TEST_MYSQL_URL
$priorTestSecret = $env:BRANDISTA_PR2_TEST_MYSQL_PASSWORD
$env:MYSQL_ROOT_PASSWORD = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$containerId = $null
function Invoke-IsolatedSql([string]$sql) {
    $result = $sql | docker exec -i $containerId sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N'
    if ($LASTEXITCODE -ne 0) { throw 'Isolated MySQL verification SQL failed' }
    return $result
}
try {
    # New container only; no existing volumes, credentials, schemas or published public interface.
    $containerId = docker run --rm -d --name $containerName --label brandista.task=admin-pr2-verification `
        --env MYSQL_ROOT_PASSWORD --env MYSQL_DATABASE=brandista_pr2_verify `
        -p 127.0.0.1::3306 mysql:8.4.11-oraclelinux9
    if ($LASTEXITCODE -ne 0) { throw 'Cannot start disposable MySQL verification container' }
    $ready = $false
    for ($attempt = 0; $attempt -lt 45; $attempt++) {
        docker exec $containerId sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=TCP -h127.0.0.1 -uroot -N -e "SELECT 1"' *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 2
    }
    if (!$ready) { throw 'Disposable MySQL failed readiness' }
    Invoke-IsolatedSql 'CREATE DATABASE brandista_pr2_upgrade;'
    $baseline = (Get-ChildItem src/main/resources/db/migration/V*__*.sql |
        Where-Object { $_.Name -match '^V(\d+)__' -and [int]$Matches[1] -le 10 } |
        Sort-Object { [int]($_.Name -replace '^V(\d+)__.*', '$1') } |
        ForEach-Object { Get-Content $_.FullName -Raw }) -join "`n"
    Invoke-IsolatedSql ("USE brandista_pr2_upgrade;`n" + $baseline)
    Invoke-IsolatedSql @'
USE brandista_pr2_upgrade;
INSERT INTO users(email,password_hash,role,status,created_at,updated_at)
VALUES ('legacy@example.invalid','synthetic-migration-hash','ADMIN','ENABLED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO user_platform_grants(user_id,grant_type,created_at,revoked_at)
SELECT id,'SUPER_ADMIN',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM users;
'@
    $before = Invoke-IsolatedSql 'USE brandista_pr2_upgrade; SELECT * FROM users; SELECT * FROM user_platform_grants;'
    Invoke-IsolatedSql ("USE brandista_pr2_upgrade;`n" + (Get-Content src/main/resources/db/migration/V11__security_bootstrap_and_audit.sql -Raw))
    $after = Invoke-IsolatedSql 'USE brandista_pr2_upgrade; SELECT * FROM users; SELECT * FROM user_platform_grants;'
    if (($before -join "`n") -ne ($after -join "`n")) { throw 'V11 changed existing account/grant data' }
    $portBinding = docker port $containerId 3306/tcp
    if ($LASTEXITCODE -ne 0 -or $portBinding -notmatch '^127\.0\.0\.1:(\d+)$') { throw 'Unexpected isolated port binding' }
    $env:BRANDISTA_PR2_TEST_MYSQL_URL = "jdbc:mysql://127.0.0.1:$($Matches[1])/brandista_pr2_verify?useSSL=false&allowPublicKeyRetrieval=true"
    $env:BRANDISTA_PR2_TEST_MYSQL_PASSWORD = $env:MYSQL_ROOT_PASSWORD
    # Secrets remain in the process environment; never command-line arguments or log text.
    & $mavenCommand @mavenPrefix --batch-mode --no-transfer-progress '-Dtest=OperatorProvisioningIntegrationTests,OperatorConfigurationTests' test *> target/admin-pr2-mysql.log
    if ($LASTEXITCODE -ne 0) { throw 'MySQL operator integration tests failed; inspect target/admin-pr2-mysql.log' }
    if (!(Select-String -Path target/admin-pr2-mysql.log -Pattern 'Tests run: [1-9]' -Quiet) -or !(Select-String -Path target/admin-pr2-mysql.log -Pattern 'BUILD SUCCESS' -Quiet)) { throw 'Maven did not produce successful nonzero test evidence' }
    Write-Output 'PASS: isolated MySQL 8.4.11 V10 -> V11 data preservation and operator/security integration tests.'
} finally {
    $env:MYSQL_ROOT_PASSWORD = $priorRootSecret
    $env:BRANDISTA_PR2_TEST_MYSQL_URL = $priorTestUrl
    $env:BRANDISTA_PR2_TEST_MYSQL_PASSWORD = $priorTestSecret
    if ($containerId) {
        docker stop $containerId | Out-Null
        Write-Output 'Disposable PR2 verification container removed.'
    }
}
