# Setup script for photo-geotagger virtual environment (PowerShell)

Write-Host "Setting up photo-geotagger virtual environment..." -ForegroundColor Green

# Create virtual environment if it doesn't exist
if (-Not (Test-Path "venv")) {
    Write-Host "Creating virtual environment..." -ForegroundColor Yellow
    python -m venv venv
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Failed to create virtual environment. Ensure Python 3.8+ is installed." -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "Virtual environment already exists." -ForegroundColor Cyan
}

# Activate virtual environment
Write-Host "Activating virtual environment..." -ForegroundColor Yellow
& .\venv\Scripts\Activate.ps1

# Upgrade pip
Write-Host "Upgrading pip..." -ForegroundColor Yellow
python -m pip install --upgrade pip

# Install requirements
Write-Host "Installing dependencies from requirements.txt..." -ForegroundColor Yellow
pip install -r requirements.txt

if ($LASTEXITCODE -eq 0) {
    Write-Host "`nSetup complete!" -ForegroundColor Green
    Write-Host "`nTo activate the virtual environment in the future, run:" -ForegroundColor Cyan
    Write-Host "  .\venv\Scripts\Activate.ps1" -ForegroundColor White
    Write-Host "`nTo run tests:" -ForegroundColor Cyan
    Write-Host "  python run_tests.py" -ForegroundColor White
    Write-Host "`nTo deactivate:" -ForegroundColor Cyan
    Write-Host "  deactivate" -ForegroundColor White
} else {
    Write-Host "`nFailed to install dependencies." -ForegroundColor Red
    exit 1
}
