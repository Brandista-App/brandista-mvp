$ErrorActionPreference = 'Stop'
$mavenCommand = if ($env:OS -eq 'Windows_NT') { Join-Path $PSScriptRoot '../mvnw.cmd' } else { 'sh' }
[string[]]$mavenPrefix = if ($env:OS -eq 'Windows_NT') { @() } else { @(Join-Path $PSScriptRoot '../mvnw') }
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force target | Out-Null
$containerName = 'brandista-pr6b-isolated-' + [guid]::NewGuid().ToString('N')
$priorRootSecret = $env:MYSQL_ROOT_PASSWORD
$priorTestUrl = $env:BRANDISTA_PR6B_TEST_MYSQL_URL
$priorTestSecret = $env:BRANDISTA_PR6B_TEST_MYSQL_PASSWORD
$env:MYSQL_ROOT_PASSWORD = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$containerId = $null
try {
    $containerId = docker run --rm -d --name $containerName --label brandista.task=pr6b-verification `
        --env MYSQL_ROOT_PASSWORD --env MYSQL_DATABASE=brandista_pr6b_verify `
        -p 127.0.0.1::3306 mysql:8.4.11-oraclelinux9
    if ($LASTEXITCODE -ne 0) { throw 'Cannot start disposable PR6B MySQL container' }
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        docker exec $containerId sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=TCP -h127.0.0.1 -uroot -N -e "SELECT 1"' *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 2
    }
    if (!$ready) { throw 'Disposable PR6B MySQL failed readiness' }
    $portBinding = docker port $containerId 3306/tcp
    if ($LASTEXITCODE -ne 0 -or $portBinding -notmatch '^127\.0\.0\.1:(\d+)$') { throw 'Unexpected isolated port binding' }
    $env:BRANDISTA_PR6B_TEST_MYSQL_URL = "jdbc:mysql://127.0.0.1:$($Matches[1])/brandista_pr6b_verify?useSSL=false&allowPublicKeyRetrieval=true"
    $env:BRANDISTA_PR6B_TEST_MYSQL_PASSWORD = $env:MYSQL_ROOT_PASSWORD
    Write-Output "Target: new disposable container $containerName; $portBinding/brandista_pr6b_verify; synthetic test fixtures only."
    & $mavenCommand @mavenPrefix --batch-mode --no-transfer-progress '-Dtest=AccountLifecycleIntegrationTests' test *> target/admin-pr6b-mysql.log
    if ($LASTEXITCODE -ne 0) { throw 'PR6B MySQL tests failed; inspect target/admin-pr6b-mysql.log securely' }
    if (!(Select-String -Path target/admin-pr6b-mysql.log -Pattern 'Tests run: [1-9]' -Quiet) -or !(Select-String -Path target/admin-pr6b-mysql.log -Pattern 'BUILD SUCCESS' -Quiet)) { throw 'Maven did not produce successful nonzero test evidence' }
    Write-Output 'PASS: isolated MySQL 8.4.11 PR6B lifecycle, authorization, audit and concurrency integration tests.'
} finally {
    $env:MYSQL_ROOT_PASSWORD = $priorRootSecret
    $env:BRANDISTA_PR6B_TEST_MYSQL_URL = $priorTestUrl
    $env:BRANDISTA_PR6B_TEST_MYSQL_PASSWORD = $priorTestSecret
    if ($containerId) {
        docker stop $containerId | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning 'Disposable container cleanup failed; inspect only the recorded PR6B container ID.' }
        else { Write-Output 'Disposable PR6B container and its anonymous volume removed (--rm).' }
    }
}
