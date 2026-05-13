$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)

if ($env:FLOWER_SYNC_COMMIT -eq "1") {
    Write-Output "Report finalize skipped: FLOWER_SYNC_COMMIT=1."
    exit 0
}

if (-not (Test-Path -LiteralPath (Join-Path $ProjectRoot "REPORT\active\current-task.json") -PathType Leaf)) {
    Write-Output "Report finalize skipped: no active report task."
    exit 0
}

& (Join-Path $ProjectRoot "scripts\work-report.ps1") -Action finalize
