# Launcher contracts; real console/JAR/TLS acceptance lives in test-owner-packaged.py.
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$hadNativeExit=Test-Path variable:global:LASTEXITCODE
$priorNativeExit=if($hadNativeExit){$global:LASTEXITCODE}else{$null}
$testRoot=Join-Path $PSScriptRoot '../target/owner-wrapper-test'
$fixtureRepo=Join-Path $testRoot 'repo'
$fixtureBin=Join-Path $testRoot 'jdk/bin'
New-Item -ItemType Directory -Force $fixtureBin,(Join-Path $fixtureRepo 'scripts'),(Join-Path $fixtureRepo 'target') | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'production-owner.ps1') -Destination (Join-Path $fixtureRepo 'scripts/production-owner.ps1')
$fixtureJar=Join-Path $fixtureRepo 'target/brandista-0.0.1-SNAPSHOT.jar'
$fixtureStore=Join-Path $testRoot 'fixture.p12'
$argsFile=Join-Path $testRoot 'arguments.txt'
Set-Content $fixtureJar 'synthetic artifact, never loaded'
Set-Content $fixtureStore 'synthetic truststore, never loaded'
Set-Content (Join-Path $fixtureBin 'javac.exe') 'synthetic prerequisite'
@"
@echo off
if "%~1"=="-version" (
  echo openjdk version "21.0.1" 1>&2
  exit /b 0
)
echo %* > "%OWNER_WRAPPER_ARGS%"
exit /b %OWNER_WRAPPER_EXIT%
"@ | Set-Content (Join-Path $fixtureBin 'java.cmd')
@"
@echo off
exit /b 1
"@ | Set-Content (Join-Path $fixtureRepo 'mvnw.cmd')
$head='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
@{commit=$head;sha256=(Get-FileHash $fixtureJar -Algorithm SHA256).Hash} | ConvertTo-Json | Set-Content (Join-Path $fixtureRepo 'target/owner-reviewed-build.json')
$global:ownerWrapperFixture=@{repo=(Resolve-Path $fixtureRepo).Path;head=$head;main=$head;treeMatch=$false;switches=0;
    marker=(Join-Path $fixtureRepo 'target/owner-reviewed-build.json');sha=(Get-FileHash $fixtureJar -Algorithm SHA256).Hash}
function git {
    $global:LASTEXITCODE=0
    if ($args -contains '--show-toplevel') { return $global:ownerWrapperFixture.repo }
    if ($args -contains 'get-url') { return 'https://github.com/Brandista-App/brandista-mvp.git' }
    if ($args -contains '--porcelain') { return }
    if ($args -contains 'fetch') { return }
    if ($args -contains '--is-ancestor') { $global:LASTEXITCODE=1; return }
    if ($args -contains 'diff') { $global:LASTEXITCODE=if($global:ownerWrapperFixture.treeMatch){0}else{1}; return }
    if ($args -contains 'switch') { $global:ownerWrapperFixture.switches++; return }
    if ($args -contains '--ff-only') {
        $global:ownerWrapperFixture.head=$global:ownerWrapperFixture.main
        # Same synthetic artifact for a content-identical reviewed fixture revision.
        @{commit=$global:ownerWrapperFixture.head;sha256=$global:ownerWrapperFixture.sha} | ConvertTo-Json |
            Set-Content $global:ownerWrapperFixture.marker
        return
    }
    if (($args -contains 'rev-parse') -and ($args -contains 'origin/main')) { return $global:ownerWrapperFixture.main }
    if ($args -contains 'rev-parse') { return $global:ownerWrapperFixture.head }
    throw 'Unexpected Git command in fixture'
}
$names=@('PATH','JAVA_HOME','SPRING_APPLICATION_JSON','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH','SPRING_CONFIG_LOCATION','SPRING_CONFIG_ADDITIONAL_LOCATION','MAVEN_OPTS','OWNER_WRAPPER_ARGS','OWNER_WRAPPER_EXIT')
$prior=@{}
foreach($name in $names){$prior[$name]=[Environment]::GetEnvironmentVariable($name);[Environment]::SetEnvironmentVariable($name,$null)}
$env:PATH=$fixtureBin+[IO.Path]::PathSeparator+$prior['PATH']
$env:OWNER_WRAPPER_ARGS=$argsFile
$env:OWNER_WRAPPER_EXIT='0'
$env:SPRING_APPLICATION_JSON='{"synthetic_prior":true}'
$arguments=@{DatabaseHost='fixture.aivencloud.com';DatabasePort=12345;TrustStore=$fixtureStore;ReviewedCommit=$head;VerifyOnly=$true}
$launcher=Join-Path $fixtureRepo 'scripts/production-owner.ps1'
# The production hash check must work even if the script cmdlet cannot autoload
# across PowerShell editions, as reproduced by the real 5.1 console job.
function Get-FileHash { throw 'Production launcher must use the edition-independent SHA256 implementation' }
try {
    & $launcher @arguments
    $argv=Get-Content $argsFile -Raw
    if($argv -notmatch '--verify-only' -or $argv -notmatch '--host fixture.aivencloud.com' -or $argv -match '(password|SPRING_APPLICATION_JSON|AuthorizeMutation)') {throw 'Non-secret Java argument contract failed'}
    if($env:SPRING_APPLICATION_JSON -ne '{"synthetic_prior":true}') {throw 'Environment restoration failed'}
    $env:OWNER_WRAPPER_EXIT='25'
    $rejected=$false
    try{& $launcher @arguments}catch{if($_.Exception.Message -notmatch 'code 25'){throw};$rejected=$true}
    if(!$rejected){throw 'Java failure was ignored'}
    if($env:SPRING_APPLICATION_JSON -ne '{"synthetic_prior":true}') {throw 'Failure environment restoration failed'}
    $env:OWNER_WRAPPER_EXIT='0'
    $defaultArguments=$arguments.Clone()
    $defaultArguments.Remove('ReviewedCommit')
    & $launcher @defaultArguments
    $global:ownerWrapperFixture.main='bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
    $rejected=$false
    try{& $launcher @defaultArguments}catch{if($_.Exception.Message -notmatch 'not on reviewed'){throw};$rejected=$true}
    if(!$rejected -or $global:ownerWrapperFixture.switches -ne 0){throw 'Unreviewed tree was allowed to update'}
    $global:ownerWrapperFixture.treeMatch=$true
    & $launcher @defaultArguments
    if($global:ownerWrapperFixture.switches -ne 1 -or $global:ownerWrapperFixture.head -ne $global:ownerWrapperFixture.main){throw 'Reviewed squash-merge update failed'}
    $arguments.ReviewedCommit=$global:ownerWrapperFixture.head
    $env:JAVA_TOOL_OPTIONS='-Dsynthetic.fixture=true'
    $rejected=$false
    try{& $launcher @arguments}catch{$rejected=$true}
    if(!$rejected){throw 'Injected Java options were allowed'}
    Remove-Item Env:JAVA_TOOL_OPTIONS
    Set-Content $fixtureJar 'tampered synthetic artifact'
    $rejected=$false
    try{& $launcher @arguments}catch{if($_.Exception.Message -notmatch 'build failed'){throw};$rejected=$true}
    if(!$rejected){throw 'Tampered cached artifact was trusted'}
    Write-Output 'PASS: launcher prerequisites, non-secret argv, reviewed revision/JAR integrity, reviewed squash update, unreviewed-tree rejection, Java/build failure propagation, environment cleanup and injection rejection.'
} finally {
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$prior[$name])}
    Remove-Item Function:git -ErrorAction SilentlyContinue
    Remove-Item Function:Get-FileHash -ErrorAction SilentlyContinue
    Remove-Variable ownerWrapperFixture -Scope Global -ErrorAction SilentlyContinue
    if($hadNativeExit){$global:LASTEXITCODE=$priorNativeExit}else{Remove-Variable LASTEXITCODE -Scope Global -ErrorAction SilentlyContinue}
}
