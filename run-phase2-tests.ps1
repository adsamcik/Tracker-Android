# Phase 2 Test Automation Runner
# Execute this script once Hilt infrastructure issue is resolved

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Phase 2: Precision Upgrade Tests" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Test 1: Receiver Unit Tests
Write-Host "[1/3] Running PrecisionUpgradeReceiverTest..." -ForegroundColor Yellow
$receiverTests = ".\gradlew.bat :app:testDebugUnitTest --tests `"PrecisionUpgradeReceiverTest`" --console=plain"
Invoke-Expression $receiverTests

if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ Receiver tests PASSED" -ForegroundColor Green
} else {
    Write-Host "❌ Receiver tests FAILED" -ForegroundColor Red
    exit 1
}

Write-Host ""

# Test 2: Flow Integration Tests
Write-Host "[2/3] Running PrecisionUpgradeFlowTest..." -ForegroundColor Yellow
$flowTests = ".\gradlew.bat :app:testDebugUnitTest --tests `"PrecisionUpgradeFlowTest`" --console=plain"
Invoke-Expression $flowTests

if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ Flow tests PASSED" -ForegroundColor Green
} else {
    Write-Host "❌ Flow tests FAILED" -ForegroundColor Red
    exit 1
}

Write-Host ""

# Test 3: All Phase 2 Tests (Combined)
Write-Host "[3/3] Running all Phase 2 tests..." -ForegroundColor Yellow
$allTests = ".\gradlew.bat :app:testDebugUnitTest --tests `"*PrecisionUpgrade*`" --console=plain"
Invoke-Expression $allTests

if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ All Phase 2 tests PASSED" -ForegroundColor Green
} else {
    Write-Host "❌ Some Phase 2 tests FAILED" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "✅ Phase 2 Test Suite Complete" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Summary:" -ForegroundColor White
Write-Host "  - PrecisionUpgradeReceiverTest: 11 tests" -ForegroundColor White
Write-Host "  - PrecisionUpgradeFlowTest: 7 tests" -ForegroundColor White
Write-Host "  - Total: 18 automated tests" -ForegroundColor White
Write-Host ""
Write-Host "Next steps:" -ForegroundColor Yellow
Write-Host "  1. Review test report (build/reports/tests/)" -ForegroundColor White
Write-Host "  2. Perform manual UI testing (see PHASE2_TEST_AUTOMATION.md)" -ForegroundColor White
Write-Host "  3. Ship Phase 2 🚀" -ForegroundColor White
Write-Host ""
