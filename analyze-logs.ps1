# AI Mod Log Analyzer
# Usage: .\analyze-logs.ps1 [-LogPath "path\to\latest.log"] [-Follow] [-ExportReport]
#
# Analyzes Minecraft logs for AI Mod issues and generates a report.

param(
    [string]$LogPath = "E:\Games\Minecraft\Mods Test\.minecraft\versions\1.21.1-NeoForge\logs\latest.log",
    [switch]$Follow,
    [switch]$ExportReport
)

$ErrorActionPreference = "Continue"

function Write-Section { param($msg) Write-Host "`n=== $msg ===" -ForegroundColor Cyan }
function Write-Item { param($msg) Write-Host "  $msg" -ForegroundColor White }

Write-Host "========================================" -ForegroundColor White
Write-Host "  AI Mod Log Analyzer" -ForegroundColor White
Write-Host "========================================" -ForegroundColor White

# Check if log file exists
if (-not (Test-Path $LogPath)) {
    Write-Host "Error: Log file not found at $LogPath" -ForegroundColor Red
    exit 1
}

Write-Host "`nLog file: $LogPath" -ForegroundColor Gray
Write-Host "File size: $((Get-Item $LogPath).Length) bytes" -ForegroundColor Gray
Write-Host "Last modified: $((Get-Item $LogPath).LastWriteTime)" -ForegroundColor Gray

# Read log content
$logContent = Get-Content $LogPath -Raw

# ===== Statistics =====
Write-Section "Statistics"

$totalLines = ($logContent -split "`n").Count
$errorCount = ($logContent | Select-String -Pattern "\[ERROR\]" -AllMatches).Matches.Count
$warnCount = ($logContent | Select-String -Pattern "\[WARN\]" -AllMatches).Matches.Count
$infoCount = ($logContent | Select-String -Pattern "\[INFO\]" -AllMatches).Matches.Count
$aimodCount = ($logContent | Select-String -Pattern "AIMOD-DEV" -AllMatches).Matches.Count
$chainCount = ($logContent | Select-String -Pattern "CHAIN_" -AllMatches).Matches.Count
$unstuckCount = ($logContent | Select-String -Pattern "UNSTUCK_" -AllMatches).Matches.Count

Write-Item "Total lines: $totalLines"
Write-Item "INFO: $infoCount"
Write-Item "WARN: $warnCount"
Write-Item "ERROR: $errorCount"
Write-Item "AIMOD-DEV: $aimodCount"
Write-Item "Chain events: $chainCount"
Write-Item "Unstuck events: $unstuckCount"

# ===== Error Analysis =====
Write-Section "Error Analysis"

$errors = $logContent | Select-String -Pattern "\[ERROR\]" | Select-Object -Last 20
if ($errors) {
    foreach ($error in $errors) {
        Write-Item $error.Line
    }
} else {
    Write-Item "No errors found"
}

# ===== AIMOD-DEV Analysis =====
Write-Section "AIMOD-DEV Analysis"

$aimodLogs = $logContent | Select-String -Pattern "AIMOD-DEV" | Select-Object -Last 30
if ($aimodLogs) {
    # Group by tag
    $tags = @{}
    foreach ($log in $aimodLogs) {
        if ($log.Line -match "\[AIMOD-DEV\]\s*\[([^\]]+)\]") {
            $tag = $Matches[1]
            if (-not $tags.ContainsKey($tag)) { $tags[$tag] = 0 }
            $tags[$tag]++
        }
    }
    
    Write-Item "Tag distribution:"
    foreach ($tag in $tags.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 15) {
        Write-Item "  $($tag.Key): $($tag.Value)"
    }
    
    Write-Item "`nRecent AIMOD-DEV logs:"
    foreach ($log in $aimodLogs | Select-Object -Last 10) {
        Write-Item "  $($log.Line)"
    }
} else {
    Write-Item "No AIMOD-DEV logs found"
}

# ===== Chain Analysis =====
Write-Section "Chain Analysis"

$chainLogs = $logContent | Select-String -Pattern "CHAIN_" | Select-Object -Last 20
if ($chainLogs) {
    # Count by chain type
    $chainTypes = @{}
    foreach ($log in $chainLogs) {
        if ($log.Line -match "chain=(\w+)") {
            $chain = $Matches[1]
            if (-not $chainTypes.ContainsKey($chain)) { $chainTypes[$chain] = 0 }
            $chainTypes[$chain]++
        }
    }
    
    Write-Item "Chain type distribution:"
    foreach ($chain in $chainTypes.GetEnumerator() | Sort-Object Value -Descending) {
        Write-Item "  $($chain.Key): $($chain.Value)"
    }
    
    Write-Item "`nRecent chain events:"
    foreach ($log in $chainLogs | Select-Object -Last 5) {
        Write-Item "  $($log.Line)"
    }
} else {
    Write-Item "No chain events found"
}

# ===== Unstuck Analysis =====
Write-Section "Unstuck Analysis"

$unstuckLogs = $logContent | Select-String -Pattern "UNSTUCK_" | Select-Object -Last 20
if ($unstuckLogs) {
    # Count by strategy
    $strategies = @{}
    foreach ($log in $unstuckLogs) {
        if ($log.Line -match "strategy=(\w+)") {
            $strategy = $Matches[1]
            if (-not $strategies.ContainsKey($strategy)) { $strategies[$strategy] = 0 }
            $strategies[$strategy]++
        }
    }
    
    Write-Item "Strategy distribution:"
    foreach ($strategy in $strategies.GetEnumerator() | Sort-Object Value -Descending) {
        Write-Item "  $($strategy.Key): $($strategy.Value)"
    }
    
    Write-Item "`nRecent unstuck events:"
    foreach ($log in $unstuckLogs | Select-Object -Last 5) {
        Write-Item "  $($log.Line)"
    }
} else {
    Write-Item "No unstuck events found"
}

# ===== Task Analysis =====
Write-Section "Task Analysis"

$taskLogs = $logContent | Select-String -Pattern "TASK_" | Select-Object -Last 20
if ($taskLogs) {
    Write-Item "Recent task events:"
    foreach ($log in $taskLogs | Select-Object -Last 10) {
        Write-Item "  $($log.Line)"
    }
} else {
    Write-Item "No task events found"
}

# ===== LLM Analysis =====
Write-Section "LLM Analysis"

$llmLogs = $logContent | Select-String -Pattern "LLM_" | Select-Object -Last 20
if ($llmLogs) {
    # Count by type
    $llmTypes = @{}
    foreach ($log in $llmLogs) {
        if ($log.Line -match "\[LLM_([^\]]+)\]") {
            $type = $Matches[1]
            if (-not $llmTypes.ContainsKey($type)) { $llmTypes[$type] = 0 }
            $llmTypes[$type]++
        }
    }
    
    Write-Item "LLM event distribution:"
    foreach ($type in $llmTypes.GetEnumerator() | Sort-Object Value -Descending) {
        Write-Item "  $($type.Key): $($type.Value)"
    }
    
    Write-Item "`nRecent LLM events:"
    foreach ($log in $llmLogs | Select-Object -Last 5) {
        Write-Item "  $($log.Line)"
    }
} else {
    Write-Item "No LLM events found"
}

# ===== Issues Summary =====
Write-Section "Issues Summary"

$issues = @()

# Check for excessive unstuck events
if ($unstuckCount -gt 50) {
    $issues += "HIGH: Excessive unstuck events ($unstuckCount) - bot may be stuck in a loop"
}

# Check for chain spam
if ($chainCount -gt 100) {
    $issues += "MEDIUM: High chain event count ($chainCount) - possible chain spam"
}

# Check for errors
if ($errorCount -gt 0) {
    $issues += "HIGH: $errorCount errors found in log"
}

# Check for AIMOD-DEV errors
$aimodErrors = $logContent | Select-String -Pattern "AIMOD-DEV.*ERROR|ERROR.*AIMOD-DEV" | Measure-Object
if ($aimodErrors.Count -gt 0) {
    $issues += "HIGH: $($aimodErrors.Count) AIMOD-DEV errors found"
}

if ($issues.Count -eq 0) {
    Write-Item "No major issues detected"
} else {
    foreach ($issue in $issues) {
        Write-Item $issue
    }
}

# ===== Export Report =====
if ($ExportReport) {
    $reportPath = "F:\MC\log-analysis-$(Get-Date -Format 'yyyyMMdd-HHmmss').txt"
    $report = @"
AI Mod Log Analysis Report
Generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
Log file: $LogPath

Statistics:
- Total lines: $totalLines
- INFO: $infoCount
- WARN: $warnCount
- ERROR: $errorCount
- AIMOD-DEV: $aimodCount
- Chain events: $chainCount
- Unstuck events: $unstuckCount

Issues:
$($issues -join "`n")
"@
    $report | Set-Content $reportPath
    Write-Host "`nReport exported to: $reportPath" -ForegroundColor Green
}

# ===== Follow Mode =====
if ($Follow) {
    Write-Section "Following log (Ctrl+C to stop)"
    Get-Content $LogPath -Wait -Tail 0 | ForEach-Object {
        $line = $_
        if ($line -match "AIMOD-DEV|ERROR|WARN|CHAIN_|UNSTUCK_") {
            Write-Host $line -ForegroundColor $(
                if ($line -match "ERROR") { "Red" }
                elseif ($line -match "WARN") { "Yellow" }
                elseif ($line -match "AIMOD-DEV") { "Cyan" }
                elseif ($line -match "CHAIN_") { "Magenta" }
                elseif ($line -match "UNSTUCK_") { "DarkYellow" }
                else { "White" }
            )
        }
    }
}
