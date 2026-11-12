#Requires -Version 5.1
<#
Signs Windows artifacts with Authenticode, then reads the signature back.

Credentials come from the environment, so a fork or a local build still packages:
  WINDOWS_SIGN_PFX_BASE64     base64 of a .pfx, the CI form
  WINDOWS_SIGN_PFX            path to a .pfx, the local form
  WINDOWS_SIGN_PASSWORD       password for that .pfx
  WINDOWS_SIGN_THUMBPRINT     thumbprint of a certificate already in the store
  WINDOWS_SIGN_TIMESTAMP_URL  default http://timestamp.digicert.com, "none" signs without one
  SIGNTOOL                    explicit signtool.exe path

Without credentials the script prints SKIPPED and exits 0, unless -Required is passed.
-Required also demands a trusted chain, so a tag build cannot publish an unsigned or self-signed MSI.
#>
param(
    [Parameter(Mandatory = $true)]
    [string[]]$Files,
    [switch]$Required
)

$ErrorActionPreference = 'Stop'

function Find-SignTool {
    if ($env:SIGNTOOL) {
        if (-not (Test-Path $env:SIGNTOOL)) { throw "SIGNTOOL points at a missing file: $env:SIGNTOOL" }
        return $env:SIGNTOOL
    }
    $built = Get-Command signtool.exe -ErrorAction SilentlyContinue
    if ($built) { return $built.Source }
    $kits = Get-ChildItem "C:\Program Files (x86)\Windows Kits\10\bin" -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending
    foreach ($kit in $kits) {
        $candidate = Join-Path $kit.FullName "x64\signtool.exe"
        if (Test-Path $candidate) { return $candidate }
    }
    throw "signtool.exe not found: install the Windows SDK or set SIGNTOOL"
}

function Resolve-Targets([string[]]$patterns) {
    $found = @()
    foreach ($pattern in $patterns) {
        if (Test-Path $pattern) {
            $found += (Resolve-Path $pattern).Path
        } else {
            $found += (Get-ChildItem -Path $pattern -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
        }
    }
    return ($found | Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique)
}

$targets = Resolve-Targets $Files
if (-not $targets -or $targets.Count -eq 0) { throw "nothing to sign: no file matches $($Files -join ', ')" }

$timestampUrl = $env:WINDOWS_SIGN_TIMESTAMP_URL
if (-not $timestampUrl) { $timestampUrl = 'http://timestamp.digicert.com' }

$signArgs = @()
$tempPfx = $null
$haveCredentials = $false
try {
    if ($env:WINDOWS_SIGN_PFX_BASE64) {
        $tempPfx = Join-Path ([System.IO.Path]::GetTempPath()) ("nepenthe-sign-" + [guid]::NewGuid().ToString('n') + ".pfx")
        [System.IO.File]::WriteAllBytes($tempPfx, [Convert]::FromBase64String($env:WINDOWS_SIGN_PFX_BASE64))
        $signArgs += @('/f', $tempPfx)
        $haveCredentials = $true
    } elseif ($env:WINDOWS_SIGN_PFX) {
        if (-not (Test-Path $env:WINDOWS_SIGN_PFX)) { throw "WINDOWS_SIGN_PFX is missing: $env:WINDOWS_SIGN_PFX" }
        $signArgs += @('/f', $env:WINDOWS_SIGN_PFX)
        $haveCredentials = $true
    } elseif ($env:WINDOWS_SIGN_THUMBPRINT) {
        $signArgs += @('/sha1', $env:WINDOWS_SIGN_THUMBPRINT)
        if ($env:WINDOWS_SIGN_MACHINE -eq '1') { $signArgs += '/sm' }
        $haveCredentials = $true
    }

    if (-not $haveCredentials) {
        if ($Required) {
            Write-Output "ERROR no signing credentials: set WINDOWS_SIGN_PFX_BASE64 (or _PFX, or _THUMBPRINT)"
            exit 1
        }
        Write-Output "SKIPPED no signing certificate configured, artifacts stay unsigned"
        exit 0
    }

    if ($env:WINDOWS_SIGN_PASSWORD) { $signArgs += @('/p', $env:WINDOWS_SIGN_PASSWORD) }

    $signTool = Find-SignTool
    $stampArgs = @()
    if ($timestampUrl -ne 'none') { $stampArgs = @('/tr', $timestampUrl, '/td', 'SHA256') }

    foreach ($file in $targets) {
        Write-Output "signing $file"
        $log = & $signTool sign /fd SHA256 @stampArgs @signArgs $file 2>&1
        if ($LASTEXITCODE -ne 0) {
            $log | ForEach-Object { Write-Output $_ }
            throw "signtool failed on $file with exit $LASTEXITCODE"
        }
        if ($env:WINDOWS_SIGN_PASSWORD) { $log = $log -replace [regex]::Escape($env:WINDOWS_SIGN_PASSWORD), '***' }
        $log | ForEach-Object { Write-Output $_ }
    }

    $bad = @()
    foreach ($file in $targets) {
        $sig = Get-AuthenticodeSignature -FilePath $file
        $subject = if ($sig.SignerCertificate) { $sig.SignerCertificate.Subject } else { '<none>' }
        Write-Output ("verified {0} status={1} signer={2}" -f (Split-Path $file -Leaf), $sig.Status, $subject)
        if ($Required) {
            if ($sig.Status -ne 'Valid') { $bad += "$file status=$($sig.Status)" }
        } elseif ($sig.Status -notin @('Valid', 'UnknownError')) {
            $bad += "$file status=$($sig.Status)"
        } elseif ($sig.Status -eq 'UnknownError') {
            Write-Output "WARNING untrusted chain, expected only with a self-signed certificate"
        }
    }
    if ($bad.Count -gt 0) {
        Write-Output ("ERROR signature check failed: " + ($bad -join '; '))
        exit 1
    }
    Write-Output "SIGNED $($targets.Count) file(s)"
    exit 0
} finally {
    if ($tempPfx -and (Test-Path $tempPfx)) {
        Remove-Item -LiteralPath $tempPfx -Force
    }
}
