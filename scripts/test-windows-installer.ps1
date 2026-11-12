#Requires -Version 5.1
<#
Installs the MSI silently, checks the payload and the Apps and Features entry, launches the app,
then uninstalls and checks that both are gone. Exits non-zero on the first failed check.

The install location comes from the ARP entry, so the script follows a per-user and a per-machine
package alike. A per-machine package needs an elevated shell; a per-user package does not.
Logs go to $env:TEMP\nepenthe-msi-install.log and the app log is printed on failure.
#>
param(
    [Parameter(Mandatory = $true)][string]$Msi,
    [string]$InstallDir = '',
    [string]$ProductName = 'Nepenthe Journal',
    [int]$LaunchSeconds = 25
)

$ErrorActionPreference = 'Stop'
$installLog = Join-Path $env:TEMP 'nepenthe-msi-install.log'
$uninstallLog = Join-Path $env:TEMP 'nepenthe-msi-uninstall.log'
$failures = New-Object System.Collections.Generic.List[string]
$installed = $false
$script:installPath = $InstallDir

function Fail([string]$message) {
    $failures.Add($message) | Out-Null
    Write-Output "FAIL $message"
}

function Run-Msi([string]$arguments) {
    $process = Start-Process msiexec.exe -ArgumentList $arguments -Wait -PassThru -NoNewWindow
    return $process.ExitCode
}

function Find-ArpEntry {
    $roots = @(
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall',
        'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall',
        'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall'
    )
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        Get-ChildItem $root -ErrorAction SilentlyContinue | ForEach-Object {
            $props = Get-ItemProperty $_.PSPath -ErrorAction SilentlyContinue
            if ($props.DisplayName -eq $ProductName) { $props }
        }
    }
}

# jpackage sets ARPINSTALLLOCATION, so the ARP entry names the directory that the package used.
function Resolve-InstallPath {
    if ($script:installPath) { return $script:installPath }
    $arp = Find-ArpEntry | Select-Object -First 1
    if ($arp -and $arp.InstallLocation -and (Test-Path $arp.InstallLocation)) {
        return $arp.InstallLocation.TrimEnd('\')
    }
    $roots = @($env:ProgramFiles, ${env:ProgramFiles(x86)}, $env:LOCALAPPDATA) | Where-Object { $_ }
    foreach ($root in $roots) {
        $candidate = Join-Path $root $ProductName
        if (Test-Path $candidate) { return $candidate }
    }
    Write-Output "note no install directory found, searching $($roots -join ', ')"
    return ''
}

function Stop-InstalledProcesses {
    if (-not $script:installPath) { return }
    Get-Process -ErrorAction SilentlyContinue | ForEach-Object {
        $path = $null
        try { $path = $_.Path } catch { }
        if ($path -and $path.StartsWith($script:installPath, [StringComparison]::OrdinalIgnoreCase)) {
            Write-Output "stopping $($_.ProcessName) pid=$($_.Id)"
            Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue
        }
    }
}

if (-not (Test-Path $Msi)) { throw "MSI not found: $Msi" }
$Msi = (Resolve-Path $Msi).Path
Write-Output "MSI: $Msi"

try {
    Write-Output "=== install ==="
    $installExit = Run-Msi "/i `"$Msi`" /qn /norestart /l*v `"$installLog`""
    $installed = $installExit -in @(0, 3010)
    if (-not $installed) {
        Fail "msiexec /i exited $installExit, see $installLog"
        if (Test-Path $installLog) { Get-Content $installLog -Tail 15 | ForEach-Object { Write-Output "  $_" } }
    }

    if ($installed) {
        $script:installPath = Resolve-InstallPath
        Write-Output "install directory: $($script:installPath)"

        Write-Output "=== payload ==="
        $exe = if ($script:installPath) { Join-Path $script:installPath "$ProductName.exe" } else { '' }
        $checks = @(
            @{ Name = 'launcher exe'; Path = $exe },
            @{ Name = 'bundled runtime'; Path = (Join-Path $script:installPath 'runtime\lib\modules') },
            @{ Name = 'application jars'; Path = (Join-Path $script:installPath 'app') }
        )
        foreach ($check in $checks) {
            if ($check.Path -and (Test-Path $check.Path)) {
                Write-Output "ok $($check.Name)"
            } else {
                Fail "missing $($check.Name) at $($check.Path)"
            }
        }

        Write-Output "=== apps and features entry ==="
        $arp = Find-ArpEntry | Select-Object -First 1
        if (-not $arp) {
            Fail "no uninstall entry named $ProductName"
        } else {
            Write-Output "ok DisplayVersion=$($arp.DisplayVersion) Publisher=$($arp.Publisher)"
            if ($arp.DisplayVersion -ne '0.1.0') { Fail "DisplayVersion is $($arp.DisplayVersion), expected 0.1.0" }
            if ($arp.Publisher -ne 'Wolren') { Fail "Publisher is $($arp.Publisher), expected Wolren" }
        }

        if ($exe -and (Test-Path $exe)) {
            Write-Output "=== launch ==="
            $appLog = Join-Path $env:USERPROFILE '.nepenthe\nepenthe.log'
            $app = Start-Process -FilePath $exe -PassThru
            Start-Sleep -Seconds $LaunchSeconds
            $alive = Get-Process -Id $app.Id -ErrorAction SilentlyContinue
            if (-not $alive) {
                Fail "the app exited within $LaunchSeconds seconds"
                if (Test-Path $appLog) { Get-Content $appLog -Tail 30 | ForEach-Object { Write-Output "  $_" } }
            } else {
                Write-Output "ok process alive after $LaunchSeconds seconds"
            }

            # A hidden console keeps the window unlisted, so the log proves that startup reached the window.
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
    }
} finally {
    Write-Output "=== stop and uninstall ==="
    Stop-InstalledProcesses
    $uninstallExit = Run-Msi "/x `"$Msi`" /qn /norestart /l*v `"$uninstallLog`""
    if ($uninstallExit -notin @(0, 1605, 3010)) {
        Fail "msiexec /x exited $uninstallExit, see $uninstallLog"
        if (Test-Path $uninstallLog) { Get-Content $uninstallLog -Tail 15 | ForEach-Object { Write-Output "  $_" } }
    }

    if ($script:installPath) {
        $deadline = (Get-Date).AddSeconds(30)
        while ((Test-Path $script:installPath) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 2 }
        if (Test-Path $script:installPath) {
            Write-Output "note install dir still present, contents:"
            Get-ChildItem $script:installPath -ErrorAction SilentlyContinue | ForEach-Object { Write-Output "  $($_.Name)" }
            Fail "install directory survived the uninstall: $($script:installPath)"
        } else {
            Write-Output "ok install directory removed"
        }
    }

    if (Find-ArpEntry) {
        Fail "the uninstall entry survived the uninstall"
    } else {
        Write-Output "ok uninstall entry removed"
    }
}

if ($failures.Count -gt 0) {
    Write-Output ""
    Write-Output "INSTALLER SMOKE TEST FAILED ($($failures.Count) problem(s))"
    exit 1
}
Write-Output ""
Write-Output "INSTALLER SMOKE TEST PASSED"
exit 0
