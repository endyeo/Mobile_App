param(
    [Parameter(Mandatory = $true)]
    [string]$Name,

    [string]$BranchName
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$ActiveStatePath = Join-Path $ProjectRoot "REPORT\active\current-task.json"

function ConvertTo-Slug {
    param([string]$Value)

    $Slug = ($Value -replace "[^\p{L}\p{Nd}]+", "-").Trim("-").ToLowerInvariant()
    if ([string]::IsNullOrWhiteSpace($Slug)) {
        return "task"
    }
    if ($Slug.Length -gt 40) {
        return $Slug.Substring(0, 40).Trim("-")
    }
    return $Slug
}

function Get-CurrentBranch {
    $Branch = (git -C $ProjectRoot branch --show-current).Trim()
    if ([string]::IsNullOrWhiteSpace($Branch)) {
        throw "Detached HEAD is not allowed. Create or switch to a work branch first."
    }
    return $Branch
}

function Ensure-WorkBranch {
    $CurrentBranch = Get-CurrentBranch
    if ($CurrentBranch -notin @("main", "master")) {
        return $CurrentBranch
    }

    if ([string]::IsNullOrWhiteSpace($BranchName)) {
        $Stamp = (Get-Date).ToString("yyyyMMdd-HHmm")
        $Slug = ConvertTo-Slug $Name
        $BranchName = "work/$Stamp-$Slug"
    }

    Write-Output "Current branch is '$CurrentBranch'. Creating work branch '$BranchName'."
    git -C $ProjectRoot switch -c $BranchName | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to create work branch: $BranchName"
    }

    return $BranchName
}

function Ensure-Hooks {
    $PreCommit = Join-Path $ProjectRoot ".git\hooks\pre-commit"
    $PostCommit = Join-Path $ProjectRoot ".git\hooks\post-commit"
    if ((Test-Path -LiteralPath $PreCommit -PathType Leaf) -and (Test-Path -LiteralPath $PostCommit -PathType Leaf)) {
        return
    }

    & (Join-Path $ProjectRoot "scripts\install-hooks.ps1") | Out-Host
    if ($LASTEXITCODE -is [int] -and $LASTEXITCODE -ne 0) {
        throw "Failed to install report hooks."
    }
}

$Branch = Ensure-WorkBranch
Ensure-Hooks

if (Test-Path -LiteralPath $ActiveStatePath -PathType Leaf) {
    $State = Get-Content -LiteralPath $ActiveStatePath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($State.branch -ne $Branch) {
        throw "Active work report belongs to '$($State.branch)', but current branch is '$Branch'. Switch branches or finish that task first."
    }

    & (Join-Path $ProjectRoot "scripts\work-report.ps1") `
        -Action append `
        -Summary "추가 작업 요청: $Name" `
        -ChangedFiles "진행 전" `
        -Verification "진행 전" `
        -Remaining "기존 활성 작업에 이어서 진행"
    Write-Output "Continuing active work report: $($State.name)"
    exit 0
}

& (Join-Path $ProjectRoot "scripts\work-report.ps1") -Action start -Name $Name
