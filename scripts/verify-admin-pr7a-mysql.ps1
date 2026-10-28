$ErrorActionPreference = 'Stop'
$mavenCommand = if ($env:OS -eq 'Windows_NT') { Join-Path $PSScriptRoot '../mvnw.cmd' } else { 'sh' }
$mavenPrefix = if ($env:OS -eq 'Windows_NT') { @() } else { @(Join-Path $PSScriptRoot '../mvnw') }
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force target | Out-Null
$containerName = 'brandista-pr7a-isolated-' + [guid]::NewGuid().ToString('N')
$priorRootSecret = $env:MYSQL_ROOT_PASSWORD
$priorTestUrl = $env:BRANDISTA_PR7A_TEST_MYSQL_URL
$priorTestSecret = $env:BRANDISTA_PR7A_TEST_MYSQL_PASSWORD
$env:MYSQL_ROOT_PASSWORD = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$containerId = $null
try {
    $containerId = docker run --rm -d --name $containerName --label brandista.task=pr7a-verification `
        --env MYSQL_ROOT_PASSWORD --env MYSQL_DATABASE=brandista_pr7a_verify `
        -p 127.0.0.1::3306 mysql:8.4.11-oraclelinux9
    if ($LASTEXITCODE -ne 0) { throw 'Cannot start disposable PR7A MySQL container' }
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        docker exec $containerId sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=TCP -h127.0.0.1 -uroot -N -e "SELECT 1"' *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 2
    }
    if (!$ready) { throw 'Disposable PR7A MySQL failed readiness' }
    $portBinding = docker port $containerId 3306/tcp
    if ($LASTEXITCODE -ne 0 -or $portBinding -notmatch '^127\.0\.0\.1:(\d+)$') { throw 'Unexpected isolated port binding' }
    $isolatedPort = $Matches[1]
    docker exec $containerId sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "CREATE DATABASE brandista_pr7a_upgrade"'
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create disposable upgrade schema' }
    $env:BRANDISTA_PR7A_TEST_MYSQL_URL = "jdbc:mysql://127.0.0.1:$isolatedPort/brandista_pr7a_verify?useSSL=false&allowPublicKeyRetrieval=true"
    $env:BRANDISTA_PR7A_TEST_MYSQL_PASSWORD = $env:MYSQL_ROOT_PASSWORD
    Write-Output "Target: new disposable container $containerName; $portBinding/brandista_pr7a_verify; synthetic test fixtures only."
    & $mavenCommand @mavenPrefix --batch-mode --no-transfer-progress '-Dtest=MerchantOrderIntegrationTests,MerchantOrderMigrationTests' test *> target/admin-pr7a-mysql.log
    if ($LASTEXITCODE -ne 0) { throw 'PR7A MySQL tests failed; inspect target/admin-pr7a-mysql.log securely' }
    Write-Output 'PASS: isolated MySQL 8.4.11 PR7A populated V13-V14 upgrade, merchant order authorization, finance, audit and concurrency tests.'
} finally {
    $env:MYSQL_ROOT_PASSWORD = $priorRootSecret
    $env:BRANDISTA_PR7A_TEST_MYSQL_URL = $priorTestUrl
    $env:BRANDISTA_PR7A_TEST_MYSQL_PASSWORD = $priorTestSecret
    if ($containerId) {
        docker stop $containerId | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning 'Disposable container cleanup failed; inspect only the recorded PR7A container ID.' }
        else { Write-Output 'Disposable PR7A container and its anonymous volume removed (--rm).' }
    }
}
