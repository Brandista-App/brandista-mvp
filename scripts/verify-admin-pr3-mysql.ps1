$ErrorActionPreference = 'Stop'
$mavenCommand = if ($env:OS -eq 'Windows_NT') { Join-Path $PSScriptRoot '../mvnw.cmd' } else { 'sh' }
[string[]]$mavenPrefix = if ($env:OS -eq 'Windows_NT') { @() } else { @(Join-Path $PSScriptRoot '../mvnw') }
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force target | Out-Null
$containerName = 'brandista-admin-pr3-isolated-' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$priorRootSecret = $env:MYSQL_ROOT_PASSWORD
$priorTestUrl = $env:BRANDISTA_PR3_TEST_MYSQL_URL
$priorTestSecret = $env:BRANDISTA_PR3_TEST_MYSQL_PASSWORD
$env:MYSQL_ROOT_PASSWORD = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$containerId = $null
function Invoke-IsolatedSql([string]$sql) {
    $result = $sql | docker exec -i $containerId sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N'
    if ($LASTEXITCODE -ne 0) { throw 'Isolated MySQL verification SQL failed' }
    return $result
}
try {
    $containerId = docker run --rm -d --name $containerName --label brandista.task=admin-pr3-verification `
        --env MYSQL_ROOT_PASSWORD --env MYSQL_DATABASE=brandista_pr3_verify `
        -p 127.0.0.1::3306 mysql:8.4.11-oraclelinux9
    if ($LASTEXITCODE -ne 0) { throw 'Cannot start disposable MySQL verification container' }
    $ready = $false
    for ($attempt = 0; $attempt -lt 45; $attempt++) {
        # The image's temporary initialization server exposes a socket before it
        # restarts. TCP readiness waits for the final server, avoiding that race.
        docker exec $containerId sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=TCP -h127.0.0.1 -uroot -N -e "SELECT 1"' *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 2
    }
    if (!$ready) { throw 'Disposable MySQL failed readiness' }
    Invoke-IsolatedSql 'CREATE DATABASE brandista_pr3_upgrade;'
    $baseline = (Get-ChildItem src/main/resources/db/migration/V*__*.sql |
        Where-Object { $_.Name -match '^V(\d+)__' -and [int]$Matches[1] -le 11 } |
        Sort-Object { [int]($_.Name -replace '^V(\d+)__.*', '$1') } |
        ForEach-Object { Get-Content $_.FullName -Raw }) -join "`n"
    Invoke-IsolatedSql ("USE brandista_pr3_upgrade;`n" + $baseline + "`n" +
        (Get-Content src/test/resources/db/brand-membership-upgrade-fixture.sql -Raw))
    $snapshot = 'USE brandista_pr3_upgrade; ' + ((@('users','brands','categories','products','customer_addresses','carts','cart_items',
        'customer_orders','customer_order_items','brand_applications','user_platform_grants','security_provisioning_state') |
        ForEach-Object { "SELECT * FROM $_ ORDER BY id;" }) -join ' ') +
        ' SELECT id,actor,operation,target_user_id,occurred_at,outcome,reason FROM security_audit_events ORDER BY id;'
    $before = Invoke-IsolatedSql $snapshot
    Invoke-IsolatedSql ("USE brandista_pr3_upgrade;`n" + (Get-Content src/main/resources/db/migration/V12__brand_admin_memberships.sql -Raw))
    $after = Invoke-IsolatedSql $snapshot
    if (($before -join "`n") -ne ($after -join "`n")) { throw 'V12 changed existing commerce/security rows' }
    $memberships = Invoke-IsolatedSql 'USE brandista_pr3_upgrade; SELECT COUNT(*) FROM brand_admin_memberships;'
    if ([int]$memberships -ne 0) { throw 'Unexpected automatic membership' }
    $portBinding = docker port $containerId 3306/tcp
    if ($LASTEXITCODE -ne 0 -or $portBinding -notmatch '^127\.0\.0\.1:(\d+)$') { throw 'Unexpected isolated port binding' }
    $env:BRANDISTA_PR3_TEST_MYSQL_URL = "jdbc:mysql://127.0.0.1:$($Matches[1])/brandista_pr3_verify?useSSL=false&allowPublicKeyRetrieval=true"
    $env:BRANDISTA_PR3_TEST_MYSQL_PASSWORD = $env:MYSQL_ROOT_PASSWORD
    & $mavenCommand @mavenPrefix --batch-mode --no-transfer-progress '-Dtest=MerchantIsolationIntegrationTests' test *> target/admin-pr3-mysql.log
    if ($LASTEXITCODE -ne 0) { throw 'MySQL merchant isolation tests failed; inspect target/admin-pr3-mysql.log' }
    if (!(Select-String -Path target/admin-pr3-mysql.log -Pattern 'Tests run: [1-9]' -Quiet) -or !(Select-String -Path target/admin-pr3-mysql.log -Pattern 'BUILD SUCCESS' -Quiet)) { throw 'Maven did not produce successful nonzero test evidence' }
    Write-Output 'PASS: isolated MySQL 8.4.11 V11 -> V12 commerce/security preservation and merchant isolation integration tests.'
} finally {
    $env:MYSQL_ROOT_PASSWORD = $priorRootSecret
    $env:BRANDISTA_PR3_TEST_MYSQL_URL = $priorTestUrl
    $env:BRANDISTA_PR3_TEST_MYSQL_PASSWORD = $priorTestSecret
    if ($containerId) {
        docker stop $containerId | Out-Null
        Write-Output 'Disposable PR3 verification container removed.'
    }
}
