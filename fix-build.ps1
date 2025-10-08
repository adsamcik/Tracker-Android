#!/usr/bin/env pwsh
# Workaround script for AGP 8.13 bundleLibCompileToJarDebug task skipping bug

Write-Host "Step 1: Building spreferences module..." -ForegroundColor Cyan
.\gradlew.bat :spreferences:assembleDebug --console=plain | Out-Null

Write-Host "Step 2: Applying bundleLibCompileToJarDebug workaround..." -ForegroundColor Cyan
$targetDir = "spreferences\build\intermediates\compile_library_classes_jar\debug\bundleLibCompileToJarDebug"
$sourceJar = "spreferences\build\intermediates\aar_main_jar\debug\syncDebugLibJars\classes.jar"
$targetJar = "$targetDir\classes.jar"

if (Test-Path $sourceJar) {
    New-Item -Path $targetDir -ItemType Directory -Force | Out-Null
    Copy-Item -Path $sourceJar -Destination $targetJar -Force
    Write-Host "✓ JAR workaround applied" -ForegroundColor Green
} else {
    Write-Host "✗ Source JAR not found: $sourceJar" -ForegroundColor Red
    exit 1
}

Write-Host "Step 3: Building app..." -ForegroundColor Cyan
.\gradlew.bat :app:assembleDebug --console=plain
