# AI Mod Full Verification Script
# Usage: .\verify-mod.ps1 [-Version "1.0.7-r18"] [-SkipBuild] [-SkipGameTest] [-SkipIntegration]
#
# This script automates the full verification workflow:
# 1. Build the mod
# 2. Run unit tests
# 3. Run GameTest (headless server)
# 4. Deploy to Minecraft client
# 5. Run integration test (start server, inject commands, check logs)
# 6. Analyze logs for issues
# 7. Generate report

param(
    [string]$Version = "1.0.7-r18",
    [switch]$SkipBuild,
    [switch]$SkipGameTest,
    [switch]$SkipIntegration,
    [switch]$KeepServerRunning
)

$ErrorActionPreference = "Continue"
$ProjectDir = "F:\MC"
$ModJar = "$ProjectDir\build\libs\aimod-$Version.jar"
$McDir = "E:\Games\Minecraft\Mods Test\.minecraft\versions\1.21.1-NeoForge"
$ModsDir = "$McDir\mods"
$LogFile = "$McDir\logs\latest.log"
$ReportFile = "$ProjectDir\verification-report.txt"

# Colors for output
function Write-Step { param($msg) Write-Host "`n[$((Get-Date).ToString('HH:mm:ss'))] $msg" -ForegroundColor Cyan }
function Write-Ok { param($msg) Write-Host "  ✓ $msg" -ForegroundColor Green }
function Write-Fail { param($msg) Write-Host "  ✗ $msg" -ForegroundColor Red }
function Write-Warn { param($msg) Write-Host "  ⚠ $msg" -ForegroundColor Yellow }

# Initialize report
$report = @()
function Add-Report { param($msg) $script:report += "[$((Get-Date).ToString('HH:mm:ss'))] $msg" }

Write-Host "========================================" -ForegroundColor White
Write-Host "  AI Mod Verification Script v1.0" -ForegroundColor White
Write-Host "  Version: $Version" -ForegroundColor White
Write-Host "========================================" -ForegroundColor White

# ===== Phase 1: Build =====
if (-not $SkipBuild) {
    Write-Step "Phase 1: Building mod..."
    Add-Report "PHASE 1: BUILD"
    
    Push-Location $ProjectDir
    $buildOutput = & .\gradlew.bat clean build --init-script init-local.gradle -x test 2>&1
    $buildExit = $LASTEXITCODE
    Pop-Location
    
    if ($buildExit -eq 0) {
        Write-Ok "Build successful"
        Add-Report "BUILD: SUCCESS"
        
        # Verify JAR exists
        if (Test-Path $ModJar) {
            $jarSize = (Get-Item $ModJar).Length
            Write-Ok "JAR created: aimod-$Version.jar ($jarSize bytes)"
            Add-Report "JAR: aimod-$Version.jar ($jarSize bytes)"
            
            # Check manifest
            $manifest = & jar xf $ModJar META-INF/MANIFEST.MF 2>&1
            if (Test-Path "META-INF\MANIFEST.MF") {
                $manifestContent = Get-Content "META-INF\MANIFEST.MF" -Raw
                Write-Host $manifestContent
                Add-Report "MANIFEST:`n$manifestContent"
                Remove-Item -Recurse -Force META-INF
            }
        } else {
            Write-Fail "JAR not found at $ModJar"
            Add-Report "BUILD: JAR NOT FOUND"
            exit 1
        }
    } else {
        Write-Fail "Build failed (exit code: $buildExit)"
        Add-Report "BUILD: FAILED (exit code: $buildExit)"
        Write-Host ($buildOutput | Select-Object -Last 20)
        exit 1
    }
} else {
    Write-Step "Phase 1: Skipping build (using existing JAR)"
    Add-Report "PHASE 1: SKIPPED"
}

# ===== Phase 2: Unit Tests =====
Write-Step "Phase 2: Running unit tests..."
Add-Report "PHASE 2: UNIT TESTS"

Push-Location $ProjectDir
$testOutput = & .\gradlew.bat test --init-script init-local.gradle 2>&1
$testExit = $LASTEXITCODE
Pop-Location

if ($testExit -eq 0) {
    Write-Ok "All unit tests passed"
    Add-Report "UNIT TESTS: ALL PASSED"
} else {
    Write-Warn "Some unit tests failed (exit code: $testExit)"
    Add-Report "UNIT TESTS: SOME FAILED (exit code: $testExit)"
    # Extract test results
    $testResults = $testOutput | Select-String -Pattern "tests completed|FAILED|PASSED" | Select-Object -Last 5
    foreach ($line in $testResults) {
        Write-Host "  $line"
        Add-Report "  $line"
    }
}

# ===== Phase 3: GameTest =====
if (-not $SkipGameTest) {
    Write-Step "Phase 3: Running GameTest server..."
    Add-Report "PHASE 3: GAMETEST"
    
    # Deploy JAR first
    if (Test-Path $ModJar) {
        Remove-Item "$ModsDir\aimod-*.jar" -ErrorAction SilentlyContinue
        Copy-Item $ModJar $ModsDir
        Write-Ok "Deployed JAR to mods folder"
    }
    
    # Run GameTest server
    Push-Location $ProjectDir
    $gameTestOutput = & .\gradlew.bat runGameTestServer --init-script init-local.gradle 2>&1
    $gameTestExit = $LASTEXITCODE
    Pop-Location
    
    if ($gameTestExit -eq 0) {
        Write-Ok "GameTest server completed successfully"
        Add-Report "GAMETEST: SUCCESS"
    } else {
        Write-Warn "GameTest server exited with code $gameTestExit"
        Add-Report "GAMETEST: EXIT CODE $gameTestExit"
    }
    
    # Extract test results from output
    $gameTestResults = $gameTestOutput | Select-String -Pattern "Test Passed|Test Failed|GameTest" | Select-Object -Last 10
    foreach ($line in $gameTestResults) {
        Write-Host "  $line"
        Add-Report "  $line"
    }
} else {
    Write-Step "Phase 3: Skipping GameTest"
    Add-Report "PHASE 3: SKIPPED"
}

# ===== Phase 4: Integration Test =====
if (-not $SkipIntegration) {
    Write-Step "Phase 4: Running integration test..."
    Add-Report "PHASE 4: INTEGRATION TEST"
    
    # Deploy JAR
    if (Test-Path $ModJar) {
        Remove-Item "$ModsDir\aimod-*.jar" -ErrorAction SilentlyContinue
        Copy-Item $ModJar $ModsDir
        Write-Ok "Deployed JAR to mods folder"
    }
    
    # Clear old log
    $logDir = Split-Path $LogFile
    if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }
    "" | Set-Content $LogFile
    
    # Start log monitoring job
    $logJob = Start-Job -ScriptBlock {
        param($logPath)
        Get-Content $logPath -Wait -Tail 0 2>$null | ForEach-Object { $_ }
    } -ArgumentList $LogFile
    
    # Start Minecraft server (headless)
    Write-Host "  Starting Minecraft server (headless)..."
    $serverProcess = Start-Process -FilePath "java" -ArgumentList @(
        "-Xmx2G",
        "-Xms1G",
        "-Dfml.queryResult=confirm",
        "-jar", "$McDir\libraries\net\neoforged\forge\1.21.1-49.0.30\forge-1.21.1-49.0.30-shim.jar",
        "--nogui",
        "--world", "$McDir\saves\gametest"
    ) -PassThru -NoNewWindow -RedirectStandardOutput "$ProjectDir\server-stdout.txt" -RedirectStandardError "$ProjectDir\server-stderr.txt"
    
    # Wait for server to start
    Write-Host "  Waiting for server to start..."
    Start-Sleep -Seconds 30
    
    # Check if server is running
    if ($serverProcess -and -not $serverProcess.HasExited) {
        Write-Ok "Server started (PID: $($serverProcess.Id))"
        Add-Report "SERVER: Started (PID: $($serverProcess.Id))"
        
        # Send test commands via stdin
        $commands = @(
            "say AI Mod Integration Test Starting...",
            "/ai_bot spawn",
            "say Waiting 5 seconds...",
            "/ai_bot status",
            "/ai_bot goto 0 64 0",
            "say Test complete"
        )
        
        foreach ($cmd in $commands) {
            Write-Host "  Sending: $cmd"
            $serverProcess.StandardInput.WriteLine($cmd)
            Start-Sleep -Seconds 2
        }
        
        # Wait for commands to execute
        Start-Sleep -Seconds 10
        
        # Stop server
        Write-Host "  Stopping server..."
        $serverProcess.StandardInput.WriteLine("stop")
        $serverProcess.WaitForExit(30000)
        
        if ($serverProcess.HasExited) {
            Write-Ok "Server stopped gracefully"
            Add-Report "SERVER: Stopped gracefully"
        } else {
            Write-Warn "Server did not stop gracefully, forcing..."
            $serverProcess.Kill()
            Add-Report "SERVER: Force killed"
        }
    } else {
        Write-Fail "Server failed to start"
        Add-Report "SERVER: FAILED TO START"
        
        # Show error output
        if (Test-Path "$ProjectDir\server-stderr.txt") {
            $stderr = Get-Content "$ProjectDir\server-stderr.txt" -Tail 20
            foreach ($line in $stderr) {
                Write-Host "  $line"
            }
        }
    }
    
    # Stop log monitoring
    Stop-Job $logJob -ErrorAction SilentlyContinue
    Remove-Job $logJob -ErrorAction SilentlyContinue
    
} else {
    Write-Step "Phase 4: Skipping integration test"
    Add-Report "PHASE 4: SKIPPED"
}

# ===== Phase 5: Log Analysis =====
Write-Step "Phase 5: Analyzing logs..."
Add-Report "PHASE 5: LOG ANALYSIS"

if (Test-Path $LogFile) {
    $logContent = Get-Content $LogFile -Raw
    
    # Count errors and warnings
    $errors = ($logContent | Select-String -Pattern "\[ERROR\]" -AllMatches).Matches.Count
    $warnings = ($logContent | Select-String -Pattern "\[WARN\]" -AllMatches).Matches.Count
    $aimodLogs = ($logContent | Select-String -Pattern "AIMOD-DEV" -AllMatches).Matches.Count
    
    Write-Host "  Errors: $errors"
    Write-Host "  Warnings: $warnings"
    Write-Host "  AIMOD-DEV logs: $aimodLogs"
    Add-Report "LOGS: Errors=$errors, Warnings=$warnings, AIMOD-DEV=$aimodLogs"
    
    # Extract AIMOD-DEV logs
    $aimodLines = $logContent | Select-String -Pattern "AIMOD-DEV" | Select-Object -Last 20
    if ($aimodLines) {
        Write-Host "`n  Recent AIMOD-DEV logs:" -ForegroundColor Yellow
        foreach ($line in $aimodLines) {
            Write-Host "    $line"
            Add-Report "  AIMOD: $line"
        }
    }
    
    # Extract errors
    $errorLines = $logContent | Select-String -Pattern "\[ERROR\]" | Select-Object -Last 10
    if ($errorLines) {
        Write-Host "`n  Recent errors:" -ForegroundColor Red
        foreach ($line in $errorLines) {
            Write-Host "    $line"
            Add-Report "  ERROR: $line"
        }
    }
} else {
    Write-Warn "Log file not found at $LogFile"
    Add-Report "LOGS: FILE NOT FOUND"
}

# ===== Phase 6: Generate Report =====
Write-Step "Phase 6: Generating report..."
Add-Report "PHASE 6: REPORT"

$reportContent = @"
========================================
AI Mod Verification Report
Version: $Version
Date: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
========================================

$($report -join "`n")

========================================
Summary
========================================
Build: $(if ($SkipBuild) {"SKIPPED"} else {"SUCCESS"})
Unit Tests: $(if ($testExit -eq 0) {"PASSED"} else {"FAILED"})
GameTest: $(if ($SkipGameTest) {"SKIPPED"} else {"COMPLETED"})
Integration: $(if ($SkipIntegration) {"SKIPPED"} else {"COMPLETED"})
Log Errors: $errors
Log Warnings: $warnings
========================================
"@

$reportContent | Set-Content $ReportFile
Write-Ok "Report saved to $ReportFile"
Write-Host "`nReport content:" -ForegroundColor White
Write-Host $reportContent

# ===== Summary =====
Write-Step "Verification Complete!"
Write-Host "`nSummary:" -ForegroundColor White
Write-Host "  Build: $(if ($SkipBuild) {"SKIPPED"} else {"SUCCESS"})" -ForegroundColor $(if ($SkipBuild) {"Yellow"} else {"Green"})
Write-Host "  Unit Tests: $(if ($testExit -eq 0) {"PASSED"} else {"FAILED"})" -ForegroundColor $(if ($testExit -eq 0) {"Green"} else {"Red"})
Write-Host "  GameTest: $(if ($SkipGameTest) {"SKIPPED"} else {"COMPLETED"})" -ForegroundColor $(if ($SkipGameTest) {"Yellow"} else {"Green"})
Write-Host "  Integration: $(if ($SkipIntegration) {"SKIPPED"} else {"COMPLETED"})" -ForegroundColor $(if ($SkipIntegration) {"Yellow"} else {"Green"})
Write-Host "  Log Errors: $errors" -ForegroundColor $(if ($errors -eq 0) {"Green"} else {"Red"})
Write-Host "  Log Warnings: $warnings" -ForegroundColor $(if ($warnings -eq 0) {"Green"} else {"Yellow"})
Write-Host "`nReport: $ReportFile" -ForegroundColor Cyan
Write-Host "JAR: $ModJar" -ForegroundColor Cyan
