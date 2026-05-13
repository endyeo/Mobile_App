param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("start", "append", "status", "validate-commit", "finalize")]
    [string]$Action,

    [string]$Name,
    [string]$Summary,
    [string]$ChangedFiles,
    [string]$Verification,
    [string]$Remaining
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$ReportRoot = Join-Path $ProjectRoot "REPORT"
$ActiveDir = Join-Path $ReportRoot "active"
$PlansDir = Join-Path $ReportRoot "plans"
$RecordsDir = Join-Path $ReportRoot "records"
$StatePath = Join-Path $ActiveDir "current-task.json"
$Utf8BomEncoding = [System.Text.UTF8Encoding]::new($true)

function Write-TextFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    [System.IO.File]::WriteAllText($Path, $Value, $Utf8BomEncoding)
}

function Add-TextFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    [System.IO.File]::AppendAllText($Path, $Value, $Utf8BomEncoding)
}

function Ensure-ReportDirs {
    foreach ($Path in @($ActiveDir, $PlansDir, $RecordsDir)) {
        if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
            New-Item -ItemType Directory -Path $Path | Out-Null
        }
    }
}

function Get-GitBranch {
    $Branch = (git -C $ProjectRoot branch --show-current).Trim()
    if ([string]::IsNullOrWhiteSpace($Branch)) {
        throw "Detached HEAD is not allowed. Create a work branch before starting work."
    }
    return $Branch
}

function Assert-WorkBranch {
    $Branch = Get-GitBranch
    if ($Branch -in @("main", "master")) {
        throw "Work reports require a work branch. Create one first: git switch -c <branch-name>"
    }
    return $Branch
}

function ConvertTo-Slug {
    param([string]$Value)

    $Slug = ($Value -replace "[^\p{L}\p{Nd}]+", "-").Trim("-").ToLowerInvariant()
    if ([string]::IsNullOrWhiteSpace($Slug)) {
        return "task"
    }
    if ($Slug.Length -gt 60) {
        return $Slug.Substring(0, 60).Trim("-")
    }
    return $Slug
}

function Read-State {
    if (-not (Test-Path -LiteralPath $StatePath -PathType Leaf)) {
        throw "No active report task. Start one with: .\scripts\work-report.ps1 -Action start -Name `"task name`""
    }
    return Get-Content -LiteralPath $StatePath -Raw -Encoding UTF8 | ConvertFrom-Json
}

function ConvertTo-AbsolutePath {
    param([string]$RelativePath)
    return Join-Path $ProjectRoot $RelativePath
}

function Assert-PlanCompleted {
    param($State)

    $PlanPath = ConvertTo-AbsolutePath $State.planPath
    if (-not (Test-Path -LiteralPath $PlanPath -PathType Leaf)) {
        throw "Active plan file is missing: $($State.planPath)"
    }

    $OpenItems = Get-Content -LiteralPath $PlanPath -Encoding UTF8 | Select-String -Pattern "^\s*-\s+\[\s\]"
    if ($OpenItems) {
        throw "Active plan has unchecked items. Complete them before commit: $($State.planPath)"
    }
}

function Assert-ActiveTaskForCommit {
    $Branch = Assert-WorkBranch
    $State = Read-State
    if ($State.branch -ne $Branch) {
        throw "Active report branch mismatch. Started on '$($State.branch)', current branch is '$Branch'."
    }

    Assert-PlanCompleted -State $State

    $RecordPath = ConvertTo-AbsolutePath $State.recordPath
    if (-not (Test-Path -LiteralPath $RecordPath -PathType Leaf)) {
        throw "Active record file is missing: $($State.recordPath)"
    }

    Write-Output "Report commit gate passed for '$($State.name)'."
}

function Move-ReportFile {
    param(
        [string]$FromRelative,
        [string]$ToDir
    )

    $From = ConvertTo-AbsolutePath $FromRelative
    if (-not (Test-Path -LiteralPath $From -PathType Leaf)) {
        throw "Report file is missing: $FromRelative"
    }

    $To = Join-Path $ToDir (Split-Path -Leaf $From)
    $Index = 2
    while (Test-Path -LiteralPath $To) {
        $Base = [System.IO.Path]::GetFileNameWithoutExtension($From)
        $Ext = [System.IO.Path]::GetExtension($From)
        $To = Join-Path $ToDir "$Base-$Index$Ext"
        $Index += 1
    }

    Move-Item -LiteralPath $From -Destination $To
    return $To
}

Ensure-ReportDirs

switch ($Action) {
    "start" {
        if ([string]::IsNullOrWhiteSpace($Name)) {
            throw "Name is required for start."
        }
        if (Test-Path -LiteralPath $StatePath -PathType Leaf) {
            throw "An active report task already exists. Finish it with a commit before starting another."
        }

        $Branch = Assert-WorkBranch
        $Now = Get-Date
        $Date = $Now.ToString("yyyyMMdd")
        $Slug = ConvertTo-Slug $Name
        $PlanRelative = "REPORT\active\$Date-$Slug-plan.md"
        $RecordRelative = "REPORT\active\$Date-$Slug-record.md"
        $PlanPath = ConvertTo-AbsolutePath $PlanRelative
        $RecordPath = ConvertTo-AbsolutePath $RecordRelative

        $PlanContent = @"
# 작업 계획 보고서

## 날짜

$($Now.ToString("yyyy-MM-dd HH:mm"))

## 작업명

$Name

## 브랜치

$Branch

## 목표

- [ ] 작업 목표를 구체화한다.

## 작업 범위

- [ ] 변경할 영역을 확인한다.
- [ ] 필요한 파일만 수정한다.

## 검증 계획

- [ ] 변경 범위에 맞는 검증을 실행하거나, 실행하지 못한 이유를 기록한다.

## 완료 조건

- [ ] 작업 기록 보고서에 변경 이유와 검증 결과를 남긴다.
"@
        Write-TextFile -Path $PlanPath -Value $PlanContent

        $RecordContent = @"
# 작업 기록 보고서

## 날짜

$($Now.ToString("yyyy-MM-dd HH:mm"))

## 작업명

$Name

## 브랜치

$Branch

## 작업 기록

"@
        Write-TextFile -Path $RecordPath -Value $RecordContent

        $State = [ordered]@{
            name = $Name
            branch = $Branch
            createdAt = $Now.ToString("o")
            planPath = $PlanRelative
            recordPath = $RecordRelative
        }
        $State | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $StatePath -Encoding UTF8

        Write-Output "Started report task: $Name"
        Write-Output "Plan: $PlanRelative"
        Write-Output "Record: $RecordRelative"
    }

    "append" {
        if ([string]::IsNullOrWhiteSpace($Summary)) {
            throw "Summary is required for append."
        }

        $State = Read-State
        $RecordPath = ConvertTo-AbsolutePath $State.recordPath
        $Now = Get-Date
        $Entry = @"
### $($Now.ToString("yyyy-MM-dd HH:mm"))

요약:
$Summary

변경한 파일:
$ChangedFiles

검증:
$Verification

남은 문제:
$Remaining

"@
        Add-TextFile -Path $RecordPath -Value $Entry
        Write-Output "Appended to record: $($State.recordPath)"
    }

    "status" {
        $State = Read-State
        $State | ConvertTo-Json -Depth 4
    }

    "validate-commit" {
        Assert-ActiveTaskForCommit
    }

    "finalize" {
        $State = Read-State
        Assert-ActiveTaskForCommit
        $MovedPlan = Move-ReportFile -FromRelative $State.planPath -ToDir $PlansDir
        $MovedRecord = Move-ReportFile -FromRelative $State.recordPath -ToDir $RecordsDir
        Remove-Item -LiteralPath $StatePath -Force
        Write-Output "Finalized report task: $($State.name)"
        Write-Output "Plan moved to: $MovedPlan"
        Write-Output "Record moved to: $MovedRecord"
    }
}
