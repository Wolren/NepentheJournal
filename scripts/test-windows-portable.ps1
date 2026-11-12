#Requires -Version 5.1
<#
Extracts the portable zip, launches the launcher it holds, and requires the app to stay alive and to
reach its window. Exits non-zero on the first failed check, so a published zip cannot be dead weight.

The launcher is whatever single .exe sits at the archive root, because the product name may change.
#>
param(
    [Parameter(Mandatory = $true)][string]$Zip,
    [int]$LaunchSeconds = 20
)

$ErrorActionPreference = 'Stop'
$failures = New-Object System.Collections.Generic.List[string]

function Fail([string]$message) {
    $failures.Add($message) | Out-Null
    Write-Output "FAIL $message"
}

if (-not (Test-Path $Zip)) { throw "zip not found: $Zip" }
$Zip = (Resolve-Path $Zip).Path
Write-Output "zip: $Zip ($([math]::Round((Get-Item $Zip).Length / 1MB, 1)) MB)"

$out = Join-Path $env:TEMP 'nepenthe-portable-check'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }

$app = $null
try {
    Write-Output "=== extract ==="
    Expand-Archive -Path $Zip -DestinationPath $out -Force
    $files = Get-ChildItem $out -Recurse -File
    Write-Output "ok $($files.Count) files"

    $launchers = Get-ChildItem $out -Filter *.exe -File
    if ($launchers.Count -ne 1) {
        Fail "expected one launcher at the archive root, found $($launchers.Count)"
    } else {
        $exe = $launchers[0].FullName
        Write-Output "ok launcher $($launchers[0].Name)"

        Write-Output "=== launch ==="
        $appLog = Join-Path $env:USERPROFILE '.nepenthe\nepenthe.log'
        $app = Start-Process -FilePath $exe -PassThru
        Start-Sleep -Seconds $LaunchSeconds
        if (-not (Get-Process -Id $app.Id -ErrorAction SilentlyContinue)) {
            Fail "the app exited within $LaunchSeconds seconds"
            if (Test-Path $appLog) { Get-Content $appLog -Tail 30 | ForEach-Object { Write-Output "  $_" } }
        } else {
            Write-Output "ok process alive after $LaunchSeconds seconds"
        }

        if (Test-Path $appLog) {
            $tail = Get-Content $appLog -Tail 40
            $initLine = $tail | Where-Object { $_ -match 'Initialized: \d+ substances, \d+ sessions' } | Select-Object -Last 1
            $windowLine = $tail | Where-Object { $_ -match '\(Window\) native handle' } | Select-Object -Last 1
            if ($initLine) { Write-Output "ok startup $($initLine.Trim())" } else { Fail "no 'Initialized' line in $appLog" }
            if ($windowLine) { Write-Output "ok window $($windowLine.Trim())" } else { Fail "no 'native handle' line in $appLog" }
        } else {
            Fail "the app wrote no log at $appLog"
        }
    }
} finally {
    if ($app) { Stop-Process -Id $app.Id -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 2
    if (Test-Path $out) { Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue }
}

if ($failures.Count -gt 0) {
    Write-Output ""
    Write-Output "PORTABLE SMOKE TEST FAILED ($($failures.Count) problem(s))"
    exit 1
}
Write-Output ""
Write-Output "PORTABLE SMOKE TEST PASSED"
exit 0
