param(
    [string]$Serial = 'emulator-5556',
    [string]$ExpectedAvd = 'Tracker_API30_Validation',
    [string]$Package = 'com.adsamcik.tracker.debug',
    [string]$Apk = 'app/build/outputs/apk/debug/app-debug.apk',
    [string]$SchemaJson = 'core/base/schemas/com.adsamcik.tracker.shared.base.database.AppDatabase/26.json'
)

$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$python = (Get-Command python -ErrorAction Stop).Source
$validator = Join-Path $PSScriptRoot 'full_v26_matrix.py'
$apkPath = (Resolve-Path (Join-Path $workspace $Apk)).Path
$schemaPath = (Resolve-Path (Join-Path $workspace $SchemaJson)).Path
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$output = Join-Path $workspace "build\device-validation\api30-full-v26-matrix-$stamp"
New-Item -ItemType Directory -Path $output | Out-Null

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$AdbArgs)
    & $adb -s $Serial @AdbArgs
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed ($LASTEXITCODE): $($AdbArgs -join ' ')"
    }
}

function Pull-AppFile {
    param([string]$RelativePath, [string]$Destination)
    $remote = "/data/local/tmp/tracker-full-matrix-$([Guid]::NewGuid().ToString('N'))"
    Invoke-Adb shell "touch '$remote' && chmod 666 '$remote'"
    try {
        Invoke-Adb shell "run-as $Package sh -c 'cat $RelativePath > $remote'"
        Invoke-Adb pull $remote $Destination | Out-Null
    } finally {
        & $adb -s $Serial shell "rm -f '$remote'" | Out-Null
    }
}

function Pull-DatabaseFamily {
    param([string]$DatabaseName, [string]$DestinationDirectory)
    New-Item -ItemType Directory -Force -Path $DestinationDirectory | Out-Null
    foreach ($suffix in @('', '-wal', '-shm')) {
        $relative = "databases/$DatabaseName$suffix"
        & $adb -s $Serial shell "run-as $Package test -f $relative"
        if ($LASTEXITCODE -eq 0) {
            Pull-AppFile $relative (Join-Path $DestinationDirectory "$DatabaseName$suffix")
        }
    }
}

$device = (& $adb devices -l | Select-String "^$([regex]::Escape($Serial))\s+device").Line
if (-not $device) { throw "Required device $Serial is not connected" }
$actualAvd = ((& $adb -s $Serial emu avd name) | Select-Object -First 1).Trim()
$isEmulator = ((& $adb -s $Serial shell getprop ro.kernel.qemu) | Select-Object -First 1).Trim()
if ($actualAvd -ne $ExpectedAvd -or $isEmulator -ne '1') {
    throw "Refusing destructive validation on $Serial (AVD=$actualAvd, qemu=$isEmulator)"
}
$productionPackages = (& $adb -s $Serial shell pm list packages com.adsamcik.tracker) |
    Where-Object { $_.Trim() -eq 'package:com.adsamcik.tracker' }
if ($productionPackages) { throw 'Production Tracker package is present; refusing validation' }

$fixture = Join-Path $output 'main_database'
$manifest = Join-Path $output 'fixture-manifest.json'
& $python $validator build --schema $schemaPath --output $fixture --manifest $manifest
if ($LASTEXITCODE -ne 0) { throw 'Fixture build failed' }

Invoke-Adb install '-r' '-t' $apkPath | Out-Null
Invoke-Adb shell pm clear $Package | Out-Null
Invoke-Adb push $fixture /data/local/tmp/tracker-full-v26.db | Out-Null
Invoke-Adb shell "run-as $Package mkdir -p databases"
Invoke-Adb shell "run-as $Package cp /data/local/tmp/tracker-full-v26.db databases/main_database"
Invoke-Adb shell "run-as $Package chmod 600 databases/main_database"
Invoke-Adb shell "rm -f /data/local/tmp/tracker-full-v26.db"

$beforeLaunchListing = Invoke-Adb shell "run-as $Package ls -la databases"
$beforeLaunchListing | Set-Content -LiteralPath (Join-Path $output 'device-before-first-launch.txt')
if ($beforeLaunchListing -match 'main_database_v27') {
    throw 'Active v27 database existed before first launch'
}

$startedAt = Get-Date
Invoke-Adb shell am start '-W' '-n' "$Package/com.adsamcik.tracker.app.activity.MainActivityCompose" |
    Set-Content -LiteralPath (Join-Path $output 'first-launch.txt')

$complete = $false
for ($attempt = 0; $attempt -lt 90; $attempt++) {
    $state = & $adb -s $Serial shell "run-as $Package cat shared_prefs/legacy_database_v27.xml 2>/dev/null"
    if ($LASTEXITCODE -eq 0 -and ($state -join "`n") -match '>COMPLETE<') {
        $complete = $true
        break
    }
    Start-Sleep -Seconds 1
}
if (-not $complete) { throw 'Legacy import did not reach COMPLETE within 90 seconds' }
$importElapsed = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 3)

Invoke-Adb shell am force-stop $Package
$beforeDirectory = Join-Path $output 'before-current-write'
Pull-DatabaseFamily main_database $beforeDirectory
Pull-DatabaseFamily main_database_v27 $beforeDirectory
Pull-AppFile 'shared_prefs/legacy_database_v27.xml' (Join-Path $beforeDirectory 'legacy_database_v27.xml')

Invoke-Adb shell am start '-W' '-n' "$Package/com.adsamcik.tracker.app.activity.MainActivityCompose" | Out-Null
Start-Sleep -Seconds 2
Invoke-Adb shell am broadcast '-a' "$Package.SEED_SESSIONS" '-p' $Package '--ei' count 1 '--ef' distance_km 1.0 '--es' profile walk |
    Set-Content -LiteralPath (Join-Path $output 'post-import-seed-broadcast.txt')
Start-Sleep -Seconds 5
Invoke-Adb shell am force-stop $Package

$afterDirectory = Join-Path $output 'after-current-write'
Pull-DatabaseFamily main_database $afterDirectory
Pull-DatabaseFamily main_database_v27 $afterDirectory

$report = Join-Path $output 'verification-report.json'
& $python $validator verify `
    --manifest $manifest `
    --source-after (Join-Path $afterDirectory 'main_database') `
    --target-before (Join-Path $beforeDirectory 'main_database_v27') `
    --target-after (Join-Path $afterDirectory 'main_database_v27') `
    --preferences (Join-Path $beforeDirectory 'legacy_database_v27.xml') `
    --report $report
if ($LASTEXITCODE -ne 0) { throw 'Full-matrix verification failed' }

$summary = [ordered]@{
    result = 'PASS'
    serial = $Serial
    avd = $actualAvd
    api = ((& $adb -s $Serial shell getprop ro.build.version.sdk) | Select-Object -First 1).Trim()
    apk_sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $apkPath).Hash
    import_elapsed_seconds = $importElapsed
    output_directory = $output
}
$summary | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'run-summary.json')
$summary | ConvertTo-Json
