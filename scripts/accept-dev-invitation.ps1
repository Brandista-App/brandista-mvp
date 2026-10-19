# PowerShell 7.4+ / .NET 8+. Isolated localhost development only.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$EncryptedFile,
    [Parameter(Mandatory)][uri]$ApiBase
)
$ErrorActionPreference = 'Stop'
$VerbosePreference = 'SilentlyContinue'
$DebugPreference = 'SilentlyContinue'
if ($PSVersionTable.PSVersion -lt [version]'7.4' -or
    $ApiBase.Host -notin @('localhost','127.0.0.1','[::1]') -or
    $ApiBase.Scheme -notin @('http','https') -or $ApiBase.UserInfo -or $ApiBase.Query -or $ApiBase.Fragment) {
    throw 'Use PowerShell 7.4+ and an isolated localhost development API URL.'
}
function Read-Masked([string]$prompt) {
    $secret = Read-Host $prompt -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secret.Dispose() }
}
$key = $null
$plaintext = $null
$cipher = $null
$jwt = $null
$payload = $null
$headers = $null
$body = $null
try {
    $keyText = Read-Masked 'Development delivery AES key (base64)'
    $key = [Convert]::FromBase64String($keyText)
    $keyText = $null
    if ($key.Length -ne 32) { throw 'Expected a 32-byte development key.' }
    $envelope = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $EncryptedFile).Path)
    if ($envelope.Length -lt 29) { throw 'Invalid encrypted invitation.' }
    $nonce = [byte[]]$envelope[0..11]
    $tag = [byte[]]$envelope[($envelope.Length-16)..($envelope.Length-1)]
    $ciphertext = [byte[]]$envelope[12..($envelope.Length-17)]
    $plaintext = [byte[]]::new($ciphertext.Length)
    $cipher = [Security.Cryptography.AesGcm]::new($key,16)
    $cipher.Decrypt($nonce,$ciphertext,$tag,$plaintext)
    $payload = [Text.Encoding]::UTF8.GetString($plaintext) | ConvertFrom-Json
    Write-Output "Synthetic invitation $($payload.invitationId) for $($payload.recipientEmail)."
    $jwt = Read-Masked 'Recipient customer access JWT (already registered and logged in)'
    $headers = @{ Authorization = "Bearer $jwt" }
    $body = @{ token = $payload.token } | ConvertTo-Json -Compress
    $endpoint = $ApiBase.AbsoluteUri.TrimEnd('/') + '/api/v1/brand-invitations/accept'
    $result = Invoke-RestMethod -Method Post -Uri $endpoint -Headers $headers -ContentType 'application/json' -Body $body -MaximumRedirection 0
    Write-Output "Accepted invitation $($result.invitationId), brand $($result.brandId), membership $($result.membershipId). Brand activation remains a separate super-admin action."
} catch {
    # Do not propagate HTTP request diagnostics, decrypted payloads or crypto input.
    throw 'Development acceptance failed. Check the account email/status, encrypted file, key, expiry and safe server audit records.'
} finally {
    if ($cipher) { $cipher.Dispose() }
    if ($key) { [Array]::Clear($key,0,$key.Length) }
    if ($plaintext) { [Array]::Clear($plaintext,0,$plaintext.Length) }
    $keyText = $jwt = $payload = $headers = $body = $null
}
