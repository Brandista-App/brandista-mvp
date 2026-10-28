# Isolated wrapper contract tests. Java and prompts are mocked; no DB/network/password is used.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
# The Java mock writes the native-process exit variable. Preserve caller state so
# Actions' pwsh epilogue cannot mistake the expected failure fixture for this test's result.
$hadNativeExit = Test-Path variable:global:LASTEXITCODE
$priorNativeExit = if ($hadNativeExit) { $global:LASTEXITCODE } else { $null }
$testRoot = Join-Path $PSScriptRoot '../target/owner-wrapper-test'
New-Item -ItemType Directory -Force $testRoot | Out-Null
$fixtureJar = Join-Path $testRoot 'fixture.jar'
$fixtureStore = Join-Path $testRoot 'fixture.p12'
Set-Content -LiteralPath $fixtureJar -Value 'synthetic fixture, never launched'
Set-Content -LiteralPath $fixtureStore -Value 'synthetic fixture, never loaded'
$prior = @{}
$names = @('SPRING_APPLICATION_JSON','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH','SPRING_CONFIG_LOCATION','SPRING_CONFIG_ADDITIONAL_LOCATION')
foreach ($name in $names) {
    $prior[$name] = [Environment]::GetEnvironmentVariable($name)
    [Environment]::SetEnvironmentVariable($name, $null)
}
$global:ownerWrapperFixture = @{ javaCalls=0; failJava=$false; expectedMutation=$false; expectedRecovery=$false }
$global:ownerWrapperFixture.failJava = $false
$global:ownerWrapperFixture.expectedMutation = $false
$global:ownerWrapperFixture.expectedRecovery = $false
function Read-Host {
    param([string]$Prompt, [switch]$AsSecureString)
    if ($AsSecureString) { return ConvertTo-SecureString 'synthetic-fixture-only' -AsPlainText -Force }
    return 'synthetic_observer'
}
function java {
    $global:ownerWrapperFixture.javaCalls++
    $settings = $env:SPRING_APPLICATION_JSON | ConvertFrom-Json
    if ($settings.'spring.datasource.url' -ne 'jdbc:mysql://fixture.aivencloud.com:12345/brandista?sslMode=VERIFY_IDENTITY&connectTimeout=5000&socketTimeout=30000') { throw 'TLS URL contract failed' }
    if ($settings.'spring.flyway.enabled' -ne $false) { throw 'Flyway must be disabled' }
    if ($settings.'brandista.security.provisioning.enabled' -ne $global:ownerWrapperFixture.expectedMutation) { throw 'Mutation gate failed' }
    if ($settings.'brandista.security.provisioning.recovery-enabled' -ne $global:ownerWrapperFixture.expectedRecovery) { throw 'Recovery gate failed' }
    $tls = $settings.'spring.datasource.hikari.data-source-properties'
    if ($tls.fallbackToSystemTrustStore -ne 'false' -or $tls.trustCertificateKeyStoreType -ne 'PKCS12') { throw 'Truststore contract failed' }
    if ($args -contains 'synthetic-fixture-only') { throw 'Secret leaked into argv' }
    if ($args[0] -ne '-Dloader.main=com.brandista.operator.OwnerProvisioningMain') { throw 'Wrong entry point' }
    $global:LASTEXITCODE = if ($global:ownerWrapperFixture.failJava) { 2 } else { 0 }
}
function Assert-Restored {
    if ($env:SPRING_APPLICATION_JSON -ne '{"synthetic_prior":true}') { throw 'Environment was not restored' }
}
$arguments = @{ DatabaseHost='fixture.aivencloud.com'; DatabasePort=12345; TrustStore=$fixtureStore; Jar=$fixtureJar }
try {
    $env:SPRING_APPLICATION_JSON = '{"synthetic_prior":true}'
    & "$PSScriptRoot/production-owner.ps1" @arguments
    Assert-Restored
    $global:ownerWrapperFixture.expectedMutation = $true
    $global:ownerWrapperFixture.expectedRecovery = $true
    & "$PSScriptRoot/production-owner.ps1" @arguments -AuthorizeMutation -AuthorizePasswordRecovery
    Assert-Restored
    $global:ownerWrapperFixture.failJava = $true
    $global:ownerWrapperFixture.expectedMutation = $false
    $global:ownerWrapperFixture.expectedRecovery = $false
    $rejected = $false
    try { & "$PSScriptRoot/production-owner.ps1" @arguments } catch { $rejected = $true }
    if (!$rejected) { throw 'Java failure was ignored' }
    Assert-Restored
    $callsBefore = $global:ownerWrapperFixture.javaCalls
    $rejected = $false
    try { & "$PSScriptRoot/production-owner.ps1" @arguments -AuthorizePasswordRecovery } catch { $rejected = $true }
    if (!$rejected -or $global:ownerWrapperFixture.javaCalls -ne $callsBefore) { throw 'Recovery without mutation authorization reached Java' }
    $env:JAVA_TOOL_OPTIONS = '-Dsynthetic.fixture=true'
    $rejected = $false
    try { & "$PSScriptRoot/production-owner.ps1" @arguments } catch { $rejected = $true }
    if (!$rejected -or $global:ownerWrapperFixture.javaCalls -ne $callsBefore) { throw 'Injected Java options reached Java' }
    Write-Output 'PASS: wrapper TLS, fixed target, secret-free argv, default gates, recovery gates, failure propagation, environment restoration and option rejection (3 mocked Java invocations).'
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $prior[$name]) }
    Remove-Item Function:Read-Host,Function:java -ErrorAction SilentlyContinue
    Remove-Variable ownerWrapperFixture -Scope Global -ErrorAction SilentlyContinue
    if ($hadNativeExit) { $global:LASTEXITCODE = $priorNativeExit }
    else { Remove-Variable LASTEXITCODE -Scope Global -ErrorAction SilentlyContinue }
}
