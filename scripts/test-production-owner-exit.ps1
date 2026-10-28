# Exercise the contract script in child pwsh processes with the Actions exit epilogue.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$shellPath = (Get-Process -Id $PID).Path
$contractPath = Join-Path $PSScriptRoot 'test-production-owner.ps1'
$contractLiteral = $contractPath.Replace("'", "''")

function Assert-ChildExit([string]$Body, [int]$Expected) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Body))
    # Process avoids leaking this regression's expected child failure into its own
    # LASTEXITCODE, and captures expected stderr without treating it as a runner error.
    $child = [Diagnostics.Process]::new()
    try {
        $child.StartInfo.FileName = $shellPath
        $child.StartInfo.UseShellExecute = $false
        $child.StartInfo.RedirectStandardOutput = $true
        $child.StartInfo.RedirectStandardError = $true
        foreach ($argument in @('-NoLogo','-NoProfile','-NonInteractive','-EncodedCommand',$encoded)) {
            $child.StartInfo.ArgumentList.Add($argument)
        }
        [void]$child.Start()
        $stdout = $child.StandardOutput.ReadToEndAsync()
        $stderr = $child.StandardError.ReadToEndAsync()
        $child.WaitForExit()
        if ($child.ExitCode -ne $Expected) {
            throw "Runner exit regression: expected $Expected, got $($child.ExitCode). $($stdout.Result) $($stderr.Result)"
        }
    } finally { $child.Dispose() }
}

$epilogue = 'if (Test-Path -LiteralPath variable:\LASTEXITCODE) { exit $LASTEXITCODE }'
# A pristine runner must not inherit the mock's expected exit 2.
Assert-ChildExit @"
`$ErrorActionPreference = 'Stop'
Remove-Variable LASTEXITCODE -Scope Global -ErrorAction SilentlyContinue
& '$contractLiteral'
if (Test-Path variable:global:LASTEXITCODE) { throw 'Mock native exit code leaked' }
$epilogue
"@ 0
# A caller's existing successful native result must also survive the negative fixture.
Assert-ChildExit @"
`$ErrorActionPreference = 'Stop'
`$global:LASTEXITCODE = 0
& '$contractLiteral'
if (`$LASTEXITCODE -ne 0) { throw 'Caller native exit code changed' }
$epilogue
"@ 0
# Preserve nonzero caller state without turning it into a successful test result.
Assert-ChildExit @"
`$ErrorActionPreference = 'Stop'
`$global:LASTEXITCODE = 7
& '$contractLiteral'
if (`$LASTEXITCODE -ne 7) { throw 'Caller failure was cleared' }
"@ 0
# An actual assertion failure still fails the runner; no unconditional exit 0 is used.
Assert-ChildExit @"
`$ErrorActionPreference = 'Stop'
& '$contractLiteral'
throw 'Synthetic assertion failure: expected child failure'
$epilogue
"@ 1
Write-Output 'PASS: Actions pwsh exit semantics, native exit-state restoration, and assertion failure propagation.'
