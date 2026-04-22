param(
    [string]$ProxyUrl = "http://127.0.0.1:8080/",
    [string]$DirectUrl = "http://127.0.0.1:9001/",
    [int]$Threads = 8,
    [int]$Connections = 10000,
    [string]$Duration = "30s",
    [double]$TargetOverheadMs = 2.0
)

Set-StrictMode -Version Latest
$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Stop"

function Convert-ToMilliseconds {
    param(
        [double]$Value,
        [string]$Unit
    )

    switch ($Unit.ToLowerInvariant()) {
        "us" { return $Value / 1000.0 }
        "ms" { return $Value }
        "s"  { return $Value * 1000.0 }
        default { throw "Unsupported latency unit: $Unit" }
    }
}

function Parse-WrkMetric {
    param(
        [string]$Output,
        [string]$Pattern,
        [string]$Name
    )

    $match = [regex]::Match($Output, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)
    if (-not $match.Success) {
        throw "Unable to parse '$Name' from wrk output."
    }
    return $match
}

function Invoke-Wrk {
    param(
        [string]$Url,
        [int]$Threads,
        [int]$Connections,
        [string]$Duration
    )

    $output = & wrk -t $Threads -c $Connections -d $Duration --latency $Url 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        throw "wrk failed for $Url`n$output"
    }
    return $output
}

function Parse-WrkResult {
    param([string]$Output)

    $latencyMatch = Parse-WrkMetric -Output $Output -Pattern "Latency\s+([0-9]+(?:\.[0-9]+)?)\s*(us|ms|s)" -Name "Latency"
    $rpsMatch = Parse-WrkMetric -Output $Output -Pattern "Requests/sec:\s+([0-9]+(?:\.[0-9]+)?)" -Name "Requests/sec"

    $latencyValue = [double]::Parse($latencyMatch.Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)
    $latencyMs = Convert-ToMilliseconds -Value $latencyValue -Unit $latencyMatch.Groups[2].Value
    $rps = [double]::Parse($rpsMatch.Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)

    return [pscustomobject]@{
        AvgLatencyMs = $latencyMs
        RequestsPerSecond = $rps
    }
}

try {
    if (-not (Get-Command wrk -ErrorAction SilentlyContinue)) {
        throw "wrk is not installed or not available on PATH."
    }

    Write-Host "Running direct backend benchmark: $DirectUrl"
    $directOutput = Invoke-Wrk -Url $DirectUrl -Threads $Threads -Connections $Connections -Duration $Duration
    $direct = Parse-WrkResult -Output $directOutput

    Write-Host "Running NetSentinel benchmark: $ProxyUrl"
    $proxyOutput = Invoke-Wrk -Url $ProxyUrl -Threads $Threads -Connections $Connections -Duration $Duration
    $proxy = Parse-WrkResult -Output $proxyOutput

    $overheadMs = [Math]::Round($proxy.AvgLatencyMs - $direct.AvgLatencyMs, 4)
    $meetsTarget = $overheadMs -lt $TargetOverheadMs

    Write-Host ""
    Write-Host "=== Benchmark Summary ==="
    Write-Host ("Direct latency avg:      {0:N4} ms" -f $direct.AvgLatencyMs)
    Write-Host ("Proxy latency avg:       {0:N4} ms" -f $proxy.AvgLatencyMs)
    Write-Host ("Latency overhead:        {0:N4} ms" -f $overheadMs)
    Write-Host ("Direct requests/sec:     {0:N2}" -f $direct.RequestsPerSecond)
    Write-Host ("Proxy requests/sec:      {0:N2}" -f $proxy.RequestsPerSecond)
    Write-Host ("Target overhead (< {0}): {1}" -f $TargetOverheadMs, $(if ($meetsTarget) { "PASS" } else { "FAIL" }))

    if (-not $meetsTarget) {
        exit 1
    }
}
finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
