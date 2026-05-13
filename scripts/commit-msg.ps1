param(
    [Parameter(Mandatory = $true)]
    [string]$CommitMessagePath
)

$ErrorActionPreference = "Stop"

$Message = Get-Content -LiteralPath $CommitMessagePath -Raw -Encoding UTF8
$FirstLine = (($Message -split "\r?\n") | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1)

function Test-ContainsHangul {
    param([string]$Value)

    foreach ($Char in $Value.ToCharArray()) {
        $Code = [int][char]$Char
        if ($Code -ge 0xAC00 -and $Code -le 0xD7A3) {
            return $true
        }
    }

    return $false
}

if ([string]::IsNullOrWhiteSpace($FirstLine)) {
    throw "커밋 메시지를 작성해야 합니다."
}

$IsAutoMessage =
    $FirstLine -like "Merge *" -or
    $FirstLine -like "Revert *" -or
    $FirstLine -like "fixup!*" -or
    $FirstLine -like "squash!*"

if ($IsAutoMessage) {
    exit 0
}

if (-not (Test-ContainsHangul -Value $FirstLine)) {
    throw "커밋 메시지는 한국어로 작성하세요. 예: 동기화: 팀원 최신 코드 반영"
}
