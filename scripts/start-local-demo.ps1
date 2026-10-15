$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$env:JAVA_HOME = Split-Path (Split-Path (Get-Command java).Source -Parent) -Parent
New-Item -ItemType Directory -Force target | Out-Null
$passwordFile = Join-Path (Get-Location) 'target/launch-demo-password.local'
if (!(Test-Path $passwordFile)) {
    [IO.File]::WriteAllText($passwordFile, [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)))
}
$env:LOCAL_DEMO_PASSWORD = [IO.File]::ReadAllText($passwordFile)
$env:SPRING_DATASOURCE_URL = 'jdbc:h2:file:./target/launch-demo;MODE=MySQL;DB_CLOSE_DELAY=-1'
$env:SPRING_DATASOURCE_USERNAME = 'sa'
$env:SPRING_DATASOURCE_PASSWORD = ''
$env:JWT_SECRET_BASE64 = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:CORS_ALLOWED_ORIGINS = 'http://127.0.0.1:5173,http://127.0.0.1:5175'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '8081'
Write-Host 'Supplementary LOCAL H2 demo only. MySQL validation still required.'
Write-Host 'Credentials: customer@demo.brandista.test / admin@demo.brandista.test; password in target/launch-demo-password.local'
& .\mvnw.cmd spring-boot:test-run '-Dspring-boot.run.useTestClasspath=true' '-Dspring-boot.run.main-class=com.brandista.launch.LocalDemoApplication' '-Dspring-boot.run.arguments=--spring.config.location=file:src/main/resources/application.yml'
if ($LASTEXITCODE -ne 0) { throw 'Local demo startup failed' }
