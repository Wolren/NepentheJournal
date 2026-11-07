<#
.SYNOPSIS
  Runs the iOS simulator pass on a macOS runner and downloads its screenshots.

.DESCRIPTION
  There is no iOS simulator on Windows. Kotlin/Native also refuses to compile the
  iOS target here: the securityEx and ccGcm cinterops include Apple SDK headers,
  and cinterop cannot run on a non-Apple host. So the real iPhone/macOS check
  runs in CI, and this script starts it and brings the pictures back.

  Needs gh, authenticated against a remote that has Actions enabled.

.EXAMPLE
  ./scripts/ios-visual.ps1 -Appearance dark
#>
param(
    [ValidateSet('light', 'dark')][string]$Appearance = 'light',
    [int]$SettleSeconds = 25,
    [string]$OutDir = (Join-Path $env:TEMP 'nepenthe-ios-visual')
)

$ErrorActionPreference = 'Stop'
$workflow = 'ios-visual.yml'

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw 'gh is not installed. Install GitHub CLI, then run gh auth login.'
}

gh auth status 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'gh is not authenticated. Run: gh auth login' }

Write-Host "Starting $workflow (appearance=$Appearance, settle=${SettleSeconds}s)"
gh workflow run $workflow -f "appearance=$Appearance" -f "settle_seconds=$SettleSeconds"
if ($LASTEXITCODE -ne 0) { throw "Could not start $workflow. Is the workflow pushed to the remote?" }

Start-Sleep -Seconds 8
$run = gh run list --workflow=$workflow --limit 1 --json databaseId,status,createdAt | ConvertFrom-Json | Select-Object -First 1
if (-not $run) { throw 'No run appeared. Check the Actions tab.' }
Write-Host "Watching run $($run.databaseId)"

gh run watch $run.databaseId --exit-status
$watchExit = $LASTEXITCODE

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
gh run download $run.databaseId -D $OutDir

$shots = Get-ChildItem -Path $OutDir -Recurse -Include *.png -ErrorAction SilentlyContinue
Write-Host "Run finished with exit $watchExit. $($shots.Count) PNG file(s) in $OutDir"
$shots | Select-Object -First 5 | ForEach-Object { Write-Host "  $($_.FullName)" }
if ($watchExit -ne 0) { throw "The $workflow run failed. Logs: gh run view $($run.databaseId) --log-failed" }
