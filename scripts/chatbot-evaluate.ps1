param(
    [string]$BaseUrl = "http://localhost:8080",
    [ValidateSet("smoke", "full", "community-smoke")]
    [string]$Set = "smoke",
    [double]$Lat = 37.5665,
    [double]$Lng = 126.978,
    [int]$TimeoutSec = 60,
    [switch]$ShowDetails
)

$ErrorActionPreference = "Stop"

function Convert-ToArray($Value) {
    if ($null -eq $Value) {
        return @()
    }
    if ($Value -is [System.Array]) {
        return @($Value)
    }
    return @($Value)
}

function Get-PropertyValue($Object, [string]$Name) {
    if ($null -eq $Object) {
        return $null
    }
    $property = $Object.PSObject.Properties | Where-Object { $_.Name -eq $Name } | Select-Object -First 1
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
}

function Get-ActionType($Action) {
    $value = Get-PropertyValue $Action "type"
    if ([string]::IsNullOrWhiteSpace([string]$value)) {
        $value = Get-PropertyValue $Action "actionType"
    }
    return ([string]$value).ToUpperInvariant()
}

function Get-ActionTarget($Action) {
    $value = Get-PropertyValue $Action "target"
    if ([string]::IsNullOrWhiteSpace([string]$value)) {
        $value = Get-PropertyValue $Action "screen"
    }
    return ([string]$value).ToUpperInvariant()
}

function ConvertTo-ActionText($Action) {
    if ($null -eq $Action) {
        return ""
    }
    $type = Get-ActionType $Action
    $target = Get-ActionTarget $Action
    if ([string]::IsNullOrWhiteSpace($target)) {
        return $type
    }
    return "$type $target"
}

function Test-RouteExpectation([string]$ActualRoute, $ExpectedRoutes) {
    $expected = Convert-ToArray $ExpectedRoutes
    if ($expected.Count -eq 0) {
        return @()
    }

    $actual = ([string]$ActualRoute).ToUpperInvariant()
    $tokens = New-Object System.Collections.Generic.HashSet[string]
    [void]$tokens.Add($actual)

    foreach ($part in ($actual -split "[,\s|/]+")) {
        if (-not [string]::IsNullOrWhiteSpace($part)) {
            [void]$tokens.Add($part)
        }
        foreach ($subPart in ($part -split "_")) {
            if (-not [string]::IsNullOrWhiteSpace($subPart)) {
                [void]$tokens.Add($subPart)
            }
        }
    }

    $missing = @()
    foreach ($route in $expected) {
        $expectedRoute = ([string]$route).ToUpperInvariant()
        if (-not $tokens.Contains($expectedRoute)) {
            $missing += $expectedRoute
        }
    }
    return $missing
}

function Test-ActionExpectation($ExpectedAction, $ActualActions) {
    $expectedType = ([string](Get-PropertyValue $ExpectedAction "type")).ToUpperInvariant()
    $expectedTarget = ([string](Get-PropertyValue $ExpectedAction "target")).ToUpperInvariant()
    $expectedParams = Get-PropertyValue $ExpectedAction "params"

    foreach ($action in (Convert-ToArray $ActualActions)) {
        $actualType = Get-ActionType $action
        $actualTarget = Get-ActionTarget $action
        if ($actualType -ne $expectedType) {
            continue
        }
        if (-not [string]::IsNullOrWhiteSpace($expectedTarget) -and $actualTarget -ne $expectedTarget) {
            continue
        }

        if ($null -ne $expectedParams) {
            $actualParams = Get-PropertyValue $action "params"
            $matchedParams = $true
            foreach ($param in $expectedParams.PSObject.Properties) {
                $actualValue = Get-PropertyValue $actualParams $param.Name
                if ([string]$actualValue -ne [string]$param.Value) {
                    $matchedParams = $false
                    break
                }
            }
            if (-not $matchedParams) {
                continue
            }
        }

        return $true
    }

    return $false
}

function Test-ForbiddenAction($ForbiddenAction, $ActualActions) {
    $actions = Convert-ToArray $ActualActions
    $forbidden = ([string]$ForbiddenAction).ToUpperInvariant()

    if ($forbidden -eq "*") {
        return ($actions.Count -gt 0)
    }

    foreach ($action in $actions) {
        $actionText = ConvertTo-ActionText $action
        $actionType = Get-ActionType $action
        if ($actionText -eq $forbidden -or $actionType -eq $forbidden) {
            return $true
        }
    }

    return $false
}

function Get-ToolNames($ToolResults) {
    $names = @()
    foreach ($toolResult in (Convert-ToArray $ToolResults)) {
        $tool = Get-PropertyValue $toolResult "tool"
        if ([string]::IsNullOrWhiteSpace([string]$tool)) {
            $tool = Get-PropertyValue $toolResult "name"
        }
        if (-not [string]::IsNullOrWhiteSpace([string]$tool)) {
            $names += [string]$tool
        }
    }
    return $names
}

function Get-PlannerSummary($AgentRun) {
    $steps = Convert-ToArray (Get-PropertyValue $AgentRun "steps")
    if ($steps.Count -eq 0) {
        return ""
    }
    $first = $steps[0]
    $tool = [string](Get-PropertyValue $first "tool")
    $message = [string](Get-PropertyValue $first "message")
    if ([string]::IsNullOrWhiteSpace($message)) {
        return $tool
    }
    if ([string]::IsNullOrWhiteSpace($tool)) {
        return $message
    }
    return "$tool - $message"
}

function Get-ResponseData($Response) {
    $data = Get-PropertyValue $Response "data"
    if ($null -eq $data) {
        return $Response
    }
    return $data
}

function Invoke-JsonPostUtf8($Uri, $BodyObject, [int]$TimeoutSeconds) {
    Add-Type -AssemblyName System.Net.Http
    $json = $BodyObject | ConvertTo-Json -Depth 10
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
    $content = [System.Net.Http.StringContent]::new(
        $json,
        [System.Text.Encoding]::UTF8,
        "application/json"
    )

    try {
        $httpResponse = $client.PostAsync($Uri, $content).GetAwaiter().GetResult()
        $bytes = $httpResponse.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $text = [System.Text.Encoding]::UTF8.GetString($bytes)
        if (-not $httpResponse.IsSuccessStatusCode) {
            throw "HTTP $([int]$httpResponse.StatusCode) $($httpResponse.ReasonPhrase): $text"
        }
        return $text | ConvertFrom-Json
    } finally {
        $content.Dispose()
        $client.Dispose()
    }
}

function Shorten-Text([string]$Text, [int]$MaxLength) {
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return ""
    }
    $singleLine = $Text.Replace("`r", " ").Replace("`n", " ").Trim()
    if ($singleLine.Length -le $MaxLength) {
        return $singleLine
    }
    return $singleLine.Substring(0, $MaxLength) + "..."
}

$caseFile = Join-Path $PSScriptRoot ("chatbot-eval-{0}.json" -f $Set)
if (-not (Test-Path -LiteralPath $caseFile)) {
    throw "Evaluation set not found: $caseFile"
}

$cases = Convert-ToArray (Get-Content -Raw -Encoding UTF8 -LiteralPath $caseFile | ConvertFrom-Json)
$threshold = switch ($Set) {
    "smoke" { 13 }
    "community-smoke" { 10 }
    default { 68 }
}
$endpoint = "{0}/chatbot/message" -f $BaseUrl.TrimEnd("/")
$runId = Get-Date -Format "yyyyMMdd-HHmmss"
$results = @()

Write-Host ("Chatbot evaluation: set={0}, cases={1}, endpoint={2}" -f $Set, $cases.Count, $endpoint)

foreach ($case in $cases) {
    $id = [string](Get-PropertyValue $case "id")
    $message = [string](Get-PropertyValue $case "message")
    $sessionId = "eval-{0}-{1}" -f $id, ([guid]::NewGuid().ToString("N").Substring(0, 8))
    $body = @{
        message = $message
        session_id = $sessionId
        context = @{
            lat = $Lat
            lng = $Lng
        }
    }

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $response = $null
    $requestError = $null
    try {
        $response = Invoke-JsonPostUtf8 `
            -Uri $endpoint `
            -BodyObject $body `
            -TimeoutSeconds $TimeoutSec
    } catch {
        $requestError = $_.Exception.Message
    } finally {
        $stopwatch.Stop()
    }

    $reasons = @()
    $data = Get-ResponseData $response
    $agentRun = Get-PropertyValue $data "agentRun"
    $route = [string](Get-PropertyValue $agentRun "route")
    $plannerSummary = Get-PlannerSummary $agentRun
    $actions = Convert-ToArray (Get-PropertyValue $data "actions")
    $singleAction = Get-PropertyValue $data "action"
    if ($actions.Count -eq 0 -and $null -ne $singleAction) {
        $actions = @($singleAction)
    }
    $toolResults = Convert-ToArray (Get-PropertyValue $data "toolResults")
    $tools = Get-ToolNames $toolResults
    $reply = [string](Get-PropertyValue $data "reply")

    if ($null -ne $requestError) {
        $reasons += "request failed: $requestError"
    } else {
        $missingRoutes = Test-RouteExpectation $route (Get-PropertyValue $case "expectedRoutes")
        foreach ($missingRoute in $missingRoutes) {
            $reasons += "missing route: $missingRoute"
        }

        foreach ($expectedAction in (Convert-ToArray (Get-PropertyValue $case "expectedActions"))) {
            if (-not (Test-ActionExpectation $expectedAction $actions)) {
                $reasons += "missing action: $(ConvertTo-ActionText $expectedAction)"
            }
        }

        foreach ($expectedTool in (Convert-ToArray (Get-PropertyValue $case "expectedTools"))) {
            if ($tools -notcontains [string]$expectedTool) {
                $reasons += "missing tool: $expectedTool"
            }
        }

        foreach ($forbiddenAction in (Convert-ToArray (Get-PropertyValue $case "forbiddenActions"))) {
            if (Test-ForbiddenAction $forbiddenAction $actions) {
                $reasons += "forbidden action used: $forbiddenAction"
            }
        }

        foreach ($forbiddenTool in (Convert-ToArray (Get-PropertyValue $case "forbiddenTools"))) {
            if ($tools -contains [string]$forbiddenTool) {
                $reasons += "forbidden tool used: $forbiddenTool"
            }
        }

        foreach ($phrase in (Convert-ToArray (Get-PropertyValue $case "requiredReplyIncludes"))) {
            if (-not $reply.Contains([string]$phrase)) {
                $reasons += "missing reply phrase: $phrase"
            }
        }

        foreach ($phrase in (Convert-ToArray (Get-PropertyValue $case "forbiddenReplyIncludes"))) {
            if ($reply.Contains([string]$phrase)) {
                $reasons += "forbidden reply phrase: $phrase"
            }
        }
    }

    $passed = ($reasons.Count -eq 0)
    $status = if ($passed) { "PASS" } else { "FAIL" }
    Write-Host ("[{0}] {1} {2}" -f $status, $id, $message)
    $actualActionText = ($actions | ForEach-Object { ConvertTo-ActionText $_ }) -join ", "
    if ($ShowDetails) {
        Write-Host ("  actual: route {0} / actions [{1}] / tools [{2}] / latency {3}ms" -f $route, $actualActionText, ($tools -join ","), [math]::Round($stopwatch.Elapsed.TotalMilliseconds))
        Write-Host ("  planner: {0}" -f $plannerSummary)
        Write-Host ("  reply: {0}" -f (Shorten-Text $reply 180))
    }
    if (-not $passed) {
        $expectedRouteText = (Convert-ToArray (Get-PropertyValue $case "expectedRoutes")) -join ","
        $expectedActionText = ((Convert-ToArray (Get-PropertyValue $case "expectedActions")) | ForEach-Object { ConvertTo-ActionText $_ }) -join ", "
        $expectedToolText = (Convert-ToArray (Get-PropertyValue $case "expectedTools")) -join ","
        Write-Host ("  expected: route {0} / action {1} / tool {2}" -f $expectedRouteText, $expectedActionText, $expectedToolText)
        Write-Host ("  actual: route {0} / actions [{1}] / tools [{2}]" -f $route, $actualActionText, ($tools -join ","))
        Write-Host ("  reasons: {0}" -f ($reasons -join "; "))
    }

    $results += [pscustomobject]@{
        id = $id
        message = $message
        passed = $passed
        reasons = $reasons
        latencyMs = [math]::Round($stopwatch.Elapsed.TotalMilliseconds)
        expected = [pscustomobject]@{
            routes = Convert-ToArray (Get-PropertyValue $case "expectedRoutes")
            actions = Convert-ToArray (Get-PropertyValue $case "expectedActions")
            tools = Convert-ToArray (Get-PropertyValue $case "expectedTools")
            forbiddenActions = Convert-ToArray (Get-PropertyValue $case "forbiddenActions")
            forbiddenTools = Convert-ToArray (Get-PropertyValue $case "forbiddenTools")
        }
        actual = [pscustomobject]@{
            route = $route
            planner = $plannerSummary
            actions = $actions
            tools = $tools
            reply = $reply
            rawResponse = $response
            error = $requestError
        }
    }
}

$passedCount = ($results | Where-Object { $_.passed }).Count
$failedCount = $cases.Count - $passedCount
$passedThreshold = ($passedCount -ge $threshold)
$summary = [pscustomobject]@{
    set = $Set
    baseUrl = $BaseUrl
    endpoint = $endpoint
    runId = $runId
    total = $cases.Count
    passed = $passedCount
    failed = $failedCount
    threshold = $threshold
    passedThreshold = $passedThreshold
    generatedAt = (Get-Date).ToString("o")
    results = $results
}

$outputDir = Join-Path (Split-Path -Parent $PSScriptRoot) "REPORT\chatbot-eval"
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$outputFile = Join-Path $outputDir ("{0}-{1}-result.json" -f $runId, $Set)
$summary | ConvertTo-Json -Depth 100 | Set-Content -Encoding UTF8 -LiteralPath $outputFile

Write-Host ""
Write-Host ("total: {0}" -f $summary.total)
Write-Host ("passed: {0}" -f $summary.passed)
Write-Host ("failed: {0}" -f $summary.failed)
Write-Host ("threshold: {0}" -f $summary.threshold)
Write-Host ("result: {0}" -f $(if ($passedThreshold) { "PASS" } else { "FAIL" }))
Write-Host ("saved: {0}" -f $outputFile)

if (-not $passedThreshold) {
    exit 1
}
