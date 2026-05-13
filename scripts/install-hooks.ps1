$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$HooksDir = Join-Path $ProjectRoot ".git\hooks"

if (-not (Test-Path -LiteralPath (Join-Path $ProjectRoot ".git") -PathType Container)) {
    throw "Git repository not found at project root: $ProjectRoot"
}

if (-not (Test-Path -LiteralPath $HooksDir -PathType Container)) {
    New-Item -ItemType Directory -Path $HooksDir | Out-Null
}

$PreCommit = @'
#!/bin/sh
PROJECT_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$PROJECT_ROOT/scripts/pre-commit.ps1"
exit $?
'@

$PostCommit = @'
#!/bin/sh
PROJECT_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$PROJECT_ROOT/scripts/post-commit.ps1"
exit 0
'@

$CommitMsg = @'
#!/bin/sh
PROJECT_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$PROJECT_ROOT/scripts/commit-msg.ps1" "$1"
exit $?
'@

Set-Content -LiteralPath (Join-Path $HooksDir "pre-commit") -Value $PreCommit -Encoding ASCII -NoNewline
Set-Content -LiteralPath (Join-Path $HooksDir "post-commit") -Value $PostCommit -Encoding ASCII -NoNewline
Set-Content -LiteralPath (Join-Path $HooksDir "commit-msg") -Value $CommitMsg -Encoding ASCII -NoNewline

Write-Output "Installed report hooks."
