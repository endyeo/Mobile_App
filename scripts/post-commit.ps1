$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
& (Join-Path $ProjectRoot "scripts\work-report.ps1") -Action finalize
