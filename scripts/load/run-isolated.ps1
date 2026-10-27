param([int]$HoldSeconds = 30, [ValidateSet(10,50,100,250,500)][int]$MaximumUsers = 500)
$ErrorActionPreference = 'Stop'
if ($HoldSeconds -lt 10 -or $HoldSeconds -gt 120) { throw 'HoldSeconds must be 10–120.' }
Set-Location (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent)
$taskId = 'brandista-prod001-' + [guid]::NewGuid().ToString('N').Substring(0,10)
$taskContainer = $null
$taskMonitor = $null
$taskPrior = @{}
$taskVariables = @('MYSQL_ROOT_PASSWORD','BRANDISTA_PROD001_URL','BRANDISTA_PROD001_PASSWORD','BRANDISTA_PROD001_LOAD','PROD001_HOLD_MS','PROD001_MAX_USERS','BRANDISTA_PROD001_OUTPUT')
foreach ($taskVariable in $taskVariables) { $taskPrior[$taskVariable] = [Environment]::GetEnvironmentVariable($taskVariable) }
try {
    $env:MYSQL_ROOT_PASSWORD = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $taskContainer = docker run --rm -d --name $taskId --label brandista.task=prod001 --memory=640m --cpus=1 `
        --env MYSQL_ROOT_PASSWORD --env MYSQL_DATABASE=brandista_prod001 -p 127.0.0.1::3306 `
        mysql:8.4.11-oraclelinux9 --innodb-buffer-pool-size=128M --max-connections=40 --performance-schema=ON `
        --slow-query-log=ON --long-query-time=0.1 --log-output=TABLE
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create disposable MySQL.' }
    $taskReady=$false
    for ($taskAttempt=0;$taskAttempt -lt 60;$taskAttempt++) {
        docker exec $taskContainer sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=TCP -h127.0.0.1 -uroot -N -e "SELECT 1"' *> $null
        if ($LASTEXITCODE -eq 0) { $taskReady=$true;break }
        Start-Sleep -Seconds 2
    }
    if (!$taskReady) {throw 'Disposable MySQL readiness failed'}
    $taskPort=docker port $taskContainer 3306/tcp
    if($taskPort -notmatch '^127\.0\.0\.1:(\d+)$'){throw 'Unsafe port binding'}
    $env:BRANDISTA_PROD001_URL="jdbc:mysql://127.0.0.1:$($Matches[1])/brandista_prod001?useSSL=false&allowPublicKeyRetrieval=true"
    $env:BRANDISTA_PROD001_PASSWORD=$env:MYSQL_ROOT_PASSWORD
    $env:BRANDISTA_PROD001_LOAD='1'
    $env:PROD001_HOLD_MS=[string]($HoldSeconds*1000)
    $env:PROD001_MAX_USERS=[string]$MaximumUsers
    $taskEvidence=Join-Path (Get-Location) "target/prod001-load/$taskId"
    New-Item -ItemType Directory -Force $taskEvidence | Out-Null
    $env:BRANDISTA_PROD001_OUTPUT=$taskEvidence
    @{maximumUsers=$MaximumUsers;holdSeconds=$HoldSeconds;workloadSha=(git hash-object scripts/load/mixed-load.mjs);probeSha=(git hash-object src/test/java/com/brandista/brandista/production/Perf002Diagnostics.java);otherRunningContainers=@(docker ps --filter "label!=brandista.task=prod001" --format "{{.Names}}");host=(node -e "console.log(JSON.stringify({cpus:require('os').cpus().length,totalMemory:require('os').totalmem(),freeMemory:require('os').freemem()}))" | ConvertFrom-Json);sourceSha=(git rev-parse HEAD);workingTree=(git status --short);startedUtc=[DateTime]::UtcNow.ToString('o');mysqlImage=(docker inspect $taskContainer --format '{{.Image}}');nodeVersion=(node --version);databaseMemoryMiB=640;databaseCpuLimit=1;jvmHeapMiB=512} | ConvertTo-Json -Depth 5 > (Join-Path $taskEvidence 'environment.json')
    $taskMonitor=Start-Job -ArgumentList $taskContainer,(Join-Path $taskEvidence 'mysql-resource-samples.jsonl') -ScriptBlock {
        param($taskDb,$taskFile)
        while($true){
            $taskSample=docker stats --no-stream --format '{{json .}}' $taskDb 2>$null
            if($LASTEXITCODE -ne 0){break}
            $taskLocks=docker exec $taskDb sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "SELECT (SELECT COUNT(*) FROM performance_schema.data_lock_waits),COUNT(*),COALESCE(MAX(TIMESTAMPDIFF(SECOND,trx_started,NOW())),0) FROM information_schema.innodb_trx"' 2>$null
            $taskLockExit=$LASTEXITCODE
            $taskLockFields=if($taskLockExit -eq 0){$taskLocks.Trim() -split '\s+'}else{@()}
            [IO.File]::AppendAllText($taskFile,([ordered]@{time=[DateTime]::UtcNow.ToString('o');stats=($taskSample | ConvertFrom-Json);lockSampleOk=($taskLockFields.Count -eq 3);lockWaitEdges=$(if($taskLockFields.Count -eq 3){[long]$taskLockFields[0]}else{$null});activeTransactions=$(if($taskLockFields.Count -eq 3){[long]$taskLockFields[1]}else{$null});oldestTransactionSeconds=$(if($taskLockFields.Count -eq 3){[long]$taskLockFields[2]}else{$null})} | ConvertTo-Json -Compress)+"`n")
            Start-Sleep -Seconds 3
        }
    }
    & .\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=ProductionLoadHarnessTests' '-DargLine=-Xmx512m' test
    if($LASTEXITCODE -ne 0){throw 'Isolated load/acceptance failed; inspect target/prod001-load/ and Surefire logs.'}
    docker inspect $taskContainer --format '{{json .State}}' > (Join-Path $taskEvidence 'container-state.json')
    $taskRestoreClock=[Diagnostics.Stopwatch]::StartNew()
    # Synthetic data only, target/ is ignored. No production database is read.
    docker exec $taskContainer sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF brandista_prod001' > (Join-Path $taskEvidence 'synthetic-backup.sql')
    if($LASTEXITCODE -ne 0){throw 'Synthetic backup failed'}
    'CREATE DATABASE brandista_prod001_restore;' | docker exec -i $taskContainer sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot'
    Get-Content -Raw (Join-Path $taskEvidence 'synthetic-backup.sql') | docker exec -i $taskContainer sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot brandista_prod001_restore'
    if($LASTEXITCODE -ne 0){throw 'Synthetic restore failed'}
    $taskCounts=@{}
    foreach($taskTable in @('users','products','customer_orders','customer_order_items','merchant_order_fulfillments','merchant_order_events')){
        $taskComparison="SELECT (SELECT COUNT(*) FROM brandista_prod001.$taskTable),(SELECT COUNT(*) FROM brandista_prod001_restore.$taskTable);"
        $taskRows=$taskComparison | docker exec -i $taskContainer sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N'
        $taskPair=$taskRows.Trim() -split '\s+'
        if($LASTEXITCODE -ne 0 -or $taskPair.Count -ne 2 -or $taskPair[0] -ne $taskPair[1]){throw 'Restore count mismatch'}
        $taskCounts[$taskTable]=[int]$taskPair[0]
    }
    @{status='PASS';method='isolated consistent mysqldump and restore to a new schema';seconds=$taskRestoreClock.Elapsed.TotalSeconds;tableCounts=$taskCounts} | ConvertTo-Json -Depth 4 > (Join-Path $taskEvidence 'restore.json')
} finally {
    if($taskMonitor){Stop-Job $taskMonitor;Remove-Job $taskMonitor -Force}
    foreach ($taskVariable in $taskVariables) { [Environment]::SetEnvironmentVariable($taskVariable,$taskPrior[$taskVariable]) }
    if($taskContainer){docker stop $taskContainer | Out-Null;Write-Output 'Disposable PROD-001 container removed.'}
}
