# One-command Windows owner setup. No passwords are read or transported by PowerShell.
[CmdletBinding()]
param(
    [ValidatePattern('^([a-zA-Z0-9.-]+\.aivencloud\.com|localhost|127\.0\.0\.1)$')][string]$DatabaseHost,
    [ValidateRange(0,65535)][int]$DatabasePort = 0,
    [string]$TrustStore = (Join-Path $env:USERPROFILE 'brandista-aiven-truststore.p12'),
    [ValidatePattern('^([a-fA-F0-9]{40})?$')][string]$ReviewedCommit = '',
    [switch]$VerifyOnly
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
foreach ($name in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH','SPRING_CONFIG_LOCATION','SPRING_CONFIG_ADDITIONAL_LOCATION','MAVEN_OPTS')) {
    if ([Environment]::GetEnvironmentVariable($name)) { throw "Use a clean terminal without $name; no passwords were requested." }
}
if (!(Get-Command git -ErrorAction SilentlyContinue)) { throw 'Git is required; no passwords were requested.' }
if (!(Get-Command java -ErrorAction SilentlyContinue)) { throw 'Java 21 JDK is required; no passwords were requested.' }
# Windows PowerShell 5.1 wraps native stderr as ErrorRecords, even on exit 0.
# Capture non-secret prerequisite commands with scoped Continue, then check exit explicitly.
function Invoke-OwnerTool([string]$Executable, [string[]]$Arguments) {
    $priorPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $text = (& $Executable @Arguments 2>&1 | Out-String).Trim()
        return [PSCustomObject]@{ Output=$text; ExitCode=$LASTEXITCODE }
    } finally { $ErrorActionPreference = $priorPreference }
}
function Get-OwnerSha256([string]$Path) {
    # .NET is available in both 5.1 and 7; do not depend on cross-edition
    # PSModulePath / script-module autoloading for the reviewed artifact check.
    $stream = [IO.File]::OpenRead($Path)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($algorithm.ComputeHash($stream))).Replace('-','') }
    finally { $algorithm.Dispose(); $stream.Dispose() }
}
$versionResult = Invoke-OwnerTool (Get-Command java).Source @('-version')
$javaVersion = $versionResult.Output
if ($versionResult.ExitCode -ne 0 -or $javaVersion -notmatch 'version "21[.\"]') { throw 'Java 21 JDK is required; no passwords were requested.' }
$javaPath = (Get-Command java).Source
$javaHome = Split-Path (Split-Path $javaPath -Parent) -Parent
if (!(Test-Path -LiteralPath (Join-Path $javaHome 'bin/javac.exe'))) { throw 'A full Java 21 JDK (including javac) is required.' }
$gitRoot = (& git -C $repo rev-parse --show-toplevel 2>$null | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or [IO.Path]::GetFullPath($gitRoot) -ne [IO.Path]::GetFullPath($repo)) { throw 'Cannot locate the repository containing this script.' }
$remote = (& git -C $repo remote get-url origin 2>$null | Out-String).Trim()
if ($remote -notmatch '^(https://github\.com/Brandista-App/brandista-mvp(\.git)?|git@github\.com:Brandista-App/brandista-mvp\.git)$') { throw 'This checkout does not have the approved Brandista backend origin.' }
$dirty = (& git -C $repo status --porcelain --untracked-files=no | Out-String).Trim()
if ($dirty) { throw 'Tracked checkout changes exist. Preserve/review them before owner setup; no files were overwritten.' }
$head = (& git -C $repo rev-parse HEAD | Out-String).Trim()
if (!$ReviewedCommit) {
    Write-Host 'Checking reviewed origin/main (Git only; no database connection).'
    $fetchOutput = Invoke-OwnerTool 'git' @('-C',$repo,'fetch','origin','main')
    if ($fetchOutput.ExitCode -ne 0) { throw 'Cannot fetch approved origin/main. Check Git access; no passwords were requested.' }
    $ReviewedCommit = (& git -C $repo rev-parse origin/main | Out-String).Trim()
    if ($head -ne $ReviewedCommit) {
        & git -C $repo merge-base --is-ancestor HEAD origin/main
        if ($LASTEXITCODE -ne 0) {
            # A squash/rebase merge changes commit IDs while retaining the reviewed
            # tree. Permit that exact content match, never an unreviewed tree.
            & git -C $repo diff --quiet HEAD origin/main --
            if ($LASTEXITCODE -ne 0) { throw 'This revision is not on reviewed origin/main. Merge/review the PR first; no checkout changes were made.' }
        }
        $switchOutput = Invoke-OwnerTool 'git' @('-C',$repo,'switch','main')
        if ($switchOutput.ExitCode -ne 0) { throw 'Cannot safely select local main; no passwords were requested.' }
        $updateOutput = Invoke-OwnerTool 'git' @('-C',$repo,'merge','--ff-only','origin/main')
        if ($updateOutput.ExitCode -ne 0) { throw 'Local main cannot fast-forward safely. No reset or forced update was attempted.' }
        Write-Host 'Reviewed main updated; restarting the current reviewed launcher.'
        & (Join-Path $repo 'scripts/production-owner.ps1') @PSBoundParameters
        return
    }
} elseif ($head -ne $ReviewedCommit) { throw 'Checkout does not match the explicitly reviewed commit.' }
Write-Host "Reviewed revision: $head"
$priorEnvironment = @{}
$names = @(Get-ChildItem Env: | Where-Object { $_.Name -match '^(SPRING_|BRANDISTA_|JWT_|MYSQL_|AIVEN_)' } | ForEach-Object { $_.Name })
$names += 'JAVA_HOME'
foreach ($name in $names) { $priorEnvironment[$name] = [Environment]::GetEnvironmentVariable($name); [Environment]::SetEnvironmentVariable($name,$null) }
try {
    $env:JAVA_HOME = $javaHome
    $jarPath = Join-Path $repo 'target/brandista-0.0.1-SNAPSHOT.jar'
    $markerPath = Join-Path $repo 'target/owner-reviewed-build.json'
    $cached = $false
    if ((Test-Path -LiteralPath $jarPath) -and (Test-Path -LiteralPath $markerPath)) {
        try {
            $marker = Get-Content -LiteralPath $markerPath -Raw | ConvertFrom-Json
            $cached = $marker.commit -eq $head -and $marker.sha256 -eq (Get-OwnerSha256 $jarPath)
        } catch { $cached = $false }
    }
    if (!$cached) {
        Write-Host 'Building and verifying the reviewed application. This happens before any password prompt.'
        New-Item -ItemType Directory -Force (Join-Path $repo 'target') | Out-Null
        Push-Location $repo
        $buildPreference = $ErrorActionPreference
        try { $ErrorActionPreference='Continue'; & (Join-Path $repo 'mvnw.cmd') --batch-mode --no-transfer-progress verify *> (Join-Path $repo 'target/owner-setup-build.log'); $buildExit=$LASTEXITCODE }
        finally { $ErrorActionPreference=$buildPreference; Pop-Location }
        if ($buildExit -ne 0 -or !(Test-Path -LiteralPath $jarPath)) { throw 'Reviewed application build failed; no database connection or passwords requested. Build log: target/owner-setup-build.log' }
        @{ commit=$head; sha256=(Get-OwnerSha256 $jarPath) } | ConvertTo-Json | Set-Content -LiteralPath $markerPath
    } else { Write-Host 'Verified matching reviewed JAR is available; no rebuild needed.' }
    if (!(Test-Path -LiteralPath $TrustStore -PathType Leaf)) { throw 'Truststore is missing. Expected the approved Aiven CA PKCS12 file at the supplied path (default: user profile/brandista-aiven-truststore.p12).' }
    $storePath = (Resolve-Path -LiteralPath $TrustStore).Path
    if (!$DatabaseHost) { $DatabaseHost = Read-Host 'Approved Aiven MySQL DNS hostname' }
    if ($DatabaseHost -notmatch '^([a-zA-Z0-9.-]+\.aivencloud\.com|localhost|127\.0\.0\.1)$') { throw 'Enter the approved Aiven DNS hostname, without a URL, path or credentials.' }
    if ($DatabasePort -eq 0) { $DatabasePort = [int](Read-Host 'Approved Aiven MySQL port') }
    if ($DatabasePort -lt 1 -or $DatabasePort -gt 65535) { throw 'Database port must be between 1 and 65535.' }
    Write-Host 'Launching Java directly in this terminal. Every password is hidden and stays inside Java.'
    $javaArguments = @('-Dloader.main=com.brandista.operator.OwnerProvisioningMain','-cp',$jarPath,'org.springframework.boot.loader.launch.PropertiesLauncher','--host',$DatabaseHost,'--port',[string]$DatabasePort,'--truststore',$storePath)
    if ($VerifyOnly) { $javaArguments += '--verify-only' }
    & $javaPath @javaArguments
    $ownerResult = $LASTEXITCODE
    if ($ownerResult -ne 0) { throw "Owner setup stopped (code $ownerResult). Read the safe category above. No automatic mutation retry was attempted." }
    Write-Host 'Owner setup completed successfully. Use the private password to sign in at https://admin.brandistaapp.com/login.'
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$priorEnvironment[$name]) }
}
