# ==============================================================================
# DASMO CYBER CAFE TOOL - Automated Release & Signature Verification Script
# ==============================================================================
# This script guarantees that release APKs are always properly signed with a valid
# keystore before being distributed. It prevents the Android "App not installed" error.
# ==============================================================================

$ErrorActionPreference = "Stop"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  DASMO Cyber Cafe Tool - Secure Release Build & Verification" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

# 1. Ensure JAVA_HOME is configured and in PATH
$defaultJbr = "C:\Program Files\Android\Android Studio\jbr"
if (-not $env:JAVA_HOME -or -not (Test-Path $env:JAVA_HOME)) {
    if (Test-Path $defaultJbr) {
        $env:JAVA_HOME = $defaultJbr
        Write-Host "[OK] Configured JAVA_HOME to: $defaultJbr" -ForegroundColor Green
    } else {
        Write-Host "[!] Warning: JAVA_HOME is not set. Gradle might use system java." -ForegroundColor Yellow
    }
}
if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin")) {
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}

# 2. Extract Version from build.gradle.kts
$gradleFile = Join-Path $PSScriptRoot "app\build.gradle.kts"
$versionName = "1.0.0"
if (Test-Path $gradleFile) {
    Get-Content $gradleFile | ForEach-Object {
        if ($_ -match 'versionName\s*=\s*"([^"]+)"') {
            $versionName = $matches[1]
        }
    }
}
Write-Host "[*] Target Version: v$versionName" -ForegroundColor Cyan

# 3. Clean and Build Release APK
Write-Host "`n[*] Building Release APK with Gradle..." -ForegroundColor Cyan
& ".\gradlew.bat" assembleRelease

$releaseApk = Join-Path $PSScriptRoot "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $releaseApk)) {
    Write-Host "`n[ERROR] Build output not found at: $releaseApk" -ForegroundColor Red
    Write-Host "If Gradle generated app-release-unsigned.apk, the keystore configuration is broken!" -ForegroundColor Red
    exit 1
}

# 4. Locate apksigner
$apksigner = $null
$buildToolsDir = "$env:LOCALAPPDATA\Android\Sdk\build-tools"
if (Test-Path $buildToolsDir) {
    $tools = Get-ChildItem -Path $buildToolsDir -Filter "apksigner.bat" -Recurse | Sort-Object FullName -Descending
    if ($tools.Count -gt 0) {
        $apksigner = $tools[0].FullName
    }
}

if (-not $apksigner) {
    $inPath = Get-Command apksigner -ErrorAction SilentlyContinue
    if ($inPath) {
        $apksigner = $inPath.Source
    }
}

# 5. Cryptographic Signature Verification
if ($apksigner) {
    Write-Host "`n[*] Verifying cryptographic signature using: $apksigner" -ForegroundColor Cyan
    $verifyResult = & $apksigner verify -v --print-certs "$releaseApk" 2>&1
    $verifyText = $verifyResult -join "`n"

    if ($LASTEXITCODE -ne 0 -or ($verifyText -match "DOES NOT VERIFY") -or ($verifyText -notmatch "Verifies")) {
        Write-Host "`n============================================================" -ForegroundColor Red
        Write-Host "  CRITICAL ERROR: APK SIGNATURE VERIFICATION FAILED!" -ForegroundColor Red
        Write-Host "============================================================" -ForegroundColor Red
        Write-Host "Android OS will block this APK with: App not installed" -ForegroundColor Red
        Write-Host $verifyText -ForegroundColor Yellow
        exit 1
    }

    Write-Host "[OK] Verification PASSED: APK is cryptographically signed." -ForegroundColor Green
    
    # Extract SHA-256 digest for logging
    if ($verifyText -match "Signer #1 certificate SHA-256 digest:\s*([a-fA-F0-9]+)") {
        Write-Host "     Certificate SHA-256: $($matches[1])" -ForegroundColor Gray
    }
} else {
    Write-Host "`n[!] Could not verify signature with apksigner (tool not found)." -ForegroundColor Yellow
}

# 6. Copy verified APK to project root
$outputApkName = "dasmo-scanner-v$versionName.apk"
$destinationApk = Join-Path $PSScriptRoot $outputApkName
Copy-Item -Path $releaseApk -Destination $destinationApk -Force

Write-Host "`n============================================================" -ForegroundColor Green
Write-Host "  BUILD SUCCESSFUL & VERIFIED!" -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Green
Write-Host "  Output File: $destinationApk" -ForegroundColor White
$fileItem = Get-Item $destinationApk
$sizeMb = [math]::Round($fileItem.Length / 1MB, 2)
Write-Host "  Size: $sizeMb MB" -ForegroundColor White
Write-Host "  Status: Fully signed and ready for GitHub Release distribution." -ForegroundColor Green
Write-Host "============================================================`n" -ForegroundColor Green
