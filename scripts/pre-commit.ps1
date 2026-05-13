$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)

if ($env:FLOWER_SYNC_COMMIT -eq "1") {
    Write-Output "Report gate skipped: FLOWER_SYNC_COMMIT=1."
    exit 0
}

function Test-TrivialDocOnlyCommit {
    $Files = @(git -C $ProjectRoot diff --cached --name-only)
    if ($Files.Count -eq 0) {
        return $false
    }

    foreach ($File in $Files) {
        $Normalized = $File -replace "/", "\"
        $Allowed =
            $Normalized -eq "AGENTS.md" -or
            $Normalized -eq ".gitignore" -or
            $Normalized -eq "README.md" -or
            $Normalized -eq "HAR_FLOWER_README.md" -or
            $Normalized -like "docs\README.md" -or
            $Normalized -like "docs\*\README.md"

        if (-not $Allowed) {
            return $false
        }
    }

    return $true
}

if (Test-TrivialDocOnlyCommit) {
    Write-Output "Report gate skipped: trivial guidance document commit."
    exit 0
}

& (Join-Path $ProjectRoot "scripts\work-report.ps1") -Action validate-commit
