# Run only after reviewing docs/production/SUPER-ADMIN-RUNBOOK.md.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9.-]+\.aivencloud\.com$')][string]$DatabaseHost,
    [Parameter(Mandatory)][ValidateRange(1,65535)][int]$DatabasePort,
    [Parameter(Mandatory)][string]$TrustStore,
    [string]$Jar = (Join-Path $PSScriptRoot '../target/brandista-0.0.1-SNAPSHOT.jar'),
    [switch]$AuthorizeMutation,
    [switch]$AuthorizePasswordRecovery
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($AuthorizePasswordRecovery -and !$AuthorizeMutation) { throw 'Recovery also requires AuthorizeMutation.' }
# These can inject logging agents, arbitrary config, or connection properties. Use a clean terminal.
foreach ($name in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH','SPRING_CONFIG_LOCATION','SPRING_CONFIG_ADDITIONAL_LOCATION')) {
    if ([Environment]::GetEnvironmentVariable($name)) { throw "Unset $name in this dedicated terminal before proceeding." }
}
$jarPath = (Resolve-Path -LiteralPath $Jar).Path
$storePath = (Resolve-Path -LiteralPath $TrustStore).Path
if ([IO.Path]::GetExtension($storePath) -notin @('.p12','.pfx')) { throw 'Use the verified Aiven CA PKCS12 truststore.' }
$storeUrl = ([Uri]$storePath).AbsoluteUri
$dbUser = Read-Host 'Approved database username (observer for VERIFY; operator for approved mutation)'
if ([string]::IsNullOrWhiteSpace($dbUser)) { throw 'Database username is required.' }
$dbSecret = Read-Host 'Database password' -AsSecureString
$storeSecret = Read-Host 'PKCS12 truststore password' -AsSecureString
$dbPointer = [IntPtr]::Zero
$storePointer = [IntPtr]::Zero
$priorJson = $env:SPRING_APPLICATION_JSON
try {
    $dbPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($dbSecret)
    $storePointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($storeSecret)
    # Fixed schema and TLS properties: no user-supplied JDBC query parameters or credentials in URLs.
    $config = @{
        'spring.datasource.url' = "jdbc:mysql://${DatabaseHost}:${DatabasePort}/brandista?sslMode=VERIFY_IDENTITY&connectTimeout=5000&socketTimeout=30000"
        'spring.datasource.username' = $dbUser
        'spring.datasource.password' = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($dbPointer)
        'spring.datasource.hikari.data-source-properties' = @{
            trustCertificateKeyStoreUrl = $storeUrl
            trustCertificateKeyStoreType = 'PKCS12'
            trustCertificateKeyStorePassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($storePointer)
            fallbackToSystemTrustStore = 'false'
        }
        'spring.datasource.hikari.maximum-pool-size' = 2
        'spring.datasource.hikari.minimum-idle' = 0
        'spring.datasource.hikari.connection-timeout' = 10000
        'spring.datasource.hikari.initialization-fail-timeout' = 1
        'spring.flyway.enabled' = $false
        'brandista.security.provisioning.enabled' = [bool]$AuthorizeMutation
        'brandista.security.provisioning.recovery-enabled' = [bool]$AuthorizePasswordRecovery
        'logging.level.root' = 'OFF'
        'logging.level.com.zaxxer.hikari' = 'OFF'
        'logging.level.org.springframework.jdbc' = 'OFF'
        'logging.level.com.mysql' = 'OFF'
    }
    $env:SPRING_APPLICATION_JSON = ConvertTo-Json $config -Depth 5 -Compress
    Write-Host "Target: ${DatabaseHost}:${DatabasePort}/brandista; account: uzair@brandistaapp.com; TLS: VERIFY_IDENTITY."
    Write-Host 'Use VERIFY first. Mutation requires separate owner approval and the exact Java confirmation.'
    & java '-Dloader.main=com.brandista.operator.OwnerProvisioningMain' -cp $jarPath org.springframework.boot.loader.launch.PropertiesLauncher
    if ($LASTEXITCODE -ne 0) { throw 'Owner workflow rejected, incomplete, or failed. Use VERIFY before retrying; do not print diagnostic secrets.' }
    Write-Host 'PASS: committed owner identity, enabled status, BCrypt format and SUPER_ADMIN grant verified. Production login remains a separate check.'
} finally {
    $env:SPRING_APPLICATION_JSON = $priorJson
    if ($dbPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($dbPointer) }
    if ($storePointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($storePointer) }
    $dbSecret.Dispose()
    $storeSecret.Dispose()
    $config = $null
    $priorJson = $null
}
