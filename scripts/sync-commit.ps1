param(
    [string]$Message = "동기화: 팀원 최신 코드 반영",

    [switch]$AddAll
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)

if ($AddAll) {
    git -C $ProjectRoot add .
    if ($LASTEXITCODE -ne 0) {
        throw "git add . failed."
    }
}

$StagedFiles = @(git -C $ProjectRoot diff --cached --name-only)
if ($StagedFiles.Count -eq 0) {
    throw "No staged files. Stage files first or run with -AddAll."
}

$ForbiddenPatterns = @(
    '(^|/|\\)REPORT(/|\\)',
    '(^|/|\\)\.vscode(/|\\)',
    '(^|/|\\)\.idea(/|\\)',
    '(^|/|\\)\.dart_tool(/|\\)',
    '(^|/|\\)\.gradle(/|\\)',
    '(^|/|\\)build(/|\\)',
    '(^|/|\\)env$',
    '(^|/|\\)\.env',
    'local\.properties$',
    'google-services\.json$',
    'application\.yml$',
    '\.(key|pem|p12|jks|keystore|secret)$'
)

foreach ($File in $StagedFiles) {
    $Normalized = $File -replace '/', '\'
    foreach ($Pattern in $ForbiddenPatterns) {
        if ($Normalized -match $Pattern) {
            throw "Refusing sync commit because forbidden file is staged: $File"
        }
    }
}

$FilesToScanForSecrets = @(
    $StagedFiles |
        Where-Object {
            ($_ -replace '/', '\') -notin @(
                "scripts\sync-commit.ps1",
                "scripts\pre-commit.ps1",
                "scripts\post-commit.ps1"
            )
        }
)

if ($FilesToScanForSecrets.Count -gt 0) {
    $Diff = git -C $ProjectRoot diff --cached -- $FilesToScanForSecrets
} else {
    $Diff = @()
}
$HardSecretPatterns = @(
    'AKIA[0-9A-Z]{16}',
    'AIza[0-9A-Za-z_-]{20,}',
    'sk-[A-Za-z0-9_-]{20,}',
    '-----BEGIN'
)

$SoftSecretPatterns = @(
    'api-key:\s*[^$\{\s]',
    'password:\s*[^$\{\s]',
    'secret:\s*[^$\{\s]',
    'token:\s*[^$\{\s]'
)

foreach ($Pattern in $HardSecretPatterns) {
    $Matches = $Diff | Select-String -Pattern $Pattern -CaseSensitive:$false
    if ($Matches) {
        throw "Refusing sync commit because staged diff matched secret-like pattern: $Pattern"
    }
}

foreach ($Pattern in $SoftSecretPatterns) {
    $Matches = $Diff | Select-String -Pattern $Pattern -CaseSensitive:$false
    if ($Matches) {
        Write-Warning "Staged diff contains secret-like text. Confirm it is a placeholder before pushing. Pattern: $Pattern"
    }
}

$Previous = $env:FLOWER_SYNC_COMMIT
try {
    $env:FLOWER_SYNC_COMMIT = "1"
    git -C $ProjectRoot commit -m $Message
    if ($LASTEXITCODE -ne 0) {
        throw "git commit failed."
    }
} finally {
    if ($null -eq $Previous) {
        Remove-Item Env:\FLOWER_SYNC_COMMIT -ErrorAction SilentlyContinue
    } else {
        $env:FLOWER_SYNC_COMMIT = $Previous
    }
}
