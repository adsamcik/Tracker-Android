#requires -Version 5.1
<#!
.SYNOPSIS
Collects reproducible V10 import benchmark evidence from an explicitly named Android device.

.DESCRIPTION
This collector intentionally does not enable PBF import or run a parser itself. The caller supplies
the synchronous workload command that drives an already-safe coordinator or test fixture. The
collector records device identity, baseline/final memory and storage, command timing, cancellation,
exit, and residue fields in a JSON evidence file.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9._:-]+$')]
    [string]$DeviceSerial,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$')]
    [string]$PackageName,

    [Parameter(Mandatory = $true)]
    [string]$DatabasePath,

    [string]$WalPath,

    [Parameter(Mandatory = $true)]
    [string]$SnapshotPath,

    [Parameter(Mandatory = $true)]
    [string]$TempPath,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$WorkloadId,

    [string]$WorkloadCommand,

    [string]$CancellationCommand,

    [ValidateRange(0, 86400)]
    [int]$CancelAfterSeconds = 0,

    [ValidateRange(1, 86400)]
    [int]$WorkloadTimeoutSeconds = 900,

    [ValidateRange(1, 3600)]
    [int]$CancellationCommandTimeoutSeconds = 60,

    [string]$WorkloadWorkingDirectory = ".",

    [string[]]$FixturePath = @(),

    [Parameter(Mandatory = $true)]
    [string]$OutputPath,

    [ValidateSet('RunAs', 'Shell')]
    [string]$StorageAccessMode = 'RunAs',

    [string]$AdbPath = 'adb',

    [switch]$AllowEmulator,

    [switch]$AllowNonZeroWorkloadExit,

    [switch]$OverwriteOutput
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-UtcTimestamp {
    return [DateTime]::UtcNow.ToString('o')
}

function Get-Sha256Text {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Text
    )

    $hasher = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
        $hash = $hasher.ComputeHash($bytes)
        return ([BitConverter]::ToString($hash) -replace '-', '').ToLowerInvariant()
    }
    finally {
        $hasher.Dispose()
    }
}

function Get-NullableInt64FromRegex {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Text,

        [Parameter(Mandatory = $true)]
        [string]$Pattern
    )

    $match = [regex]::Match($Text, $Pattern)
    if ($match.Success) {
        return [Int64]$match.Groups['value'].Value
    }

    return $null
}

function Assert-RemotePath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    if ([string]::IsNullOrWhiteSpace($Path) -or $Path -eq '/' -or -not $Path.StartsWith('/')) {
        throw "$Label must be a non-root absolute Android path."
    }

    # The generated storage commands never interpolate untrusted shell characters.
    if ($Path -notmatch '^/[A-Za-z0-9._/@:+,=\-]+$' -or $Path -match '(^|/)\.\.(/|$)') {
        throw "$Label contains an unsupported Android path character or parent traversal: $Path"
    }
}

function Resolve-OutputFilePath {
    param([string]$Path)

    $resolved = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path)
    $directory = Split-Path -Parent $resolved
    if ([string]::IsNullOrWhiteSpace($directory)) {
        $directory = (Get-Location).Path
        $resolved = Join-Path $directory $resolved
    }

    if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }

    if ((Test-Path -LiteralPath $resolved) -and -not $OverwriteOutput) {
        throw "Refusing to overwrite existing evidence file: $resolved. Use -OverwriteOutput only when replacement is intentional."
    }

    return $resolved
}

function Invoke-AdbResult {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $outputLines = & $script:AdbExecutable -s $script:TargetSerial @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    $output = ($outputLines | Out-String).Trim()

    return [ordered]@{
        exit_code = [int]$exitCode
        output    = $output
    }
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $result = Invoke-AdbResult -Arguments $Arguments
    if ($result.exit_code -ne 0) {
        throw "adb command failed (exit $($result.exit_code)): $($result.output)"
    }

    return $result.output
}

function Invoke-StorageShell {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Command
    )

    $arguments = @('shell')
    if ($script:StorageMode -eq 'RunAs') {
        $arguments += @('run-as', $script:TargetPackage)
    }
    $arguments += @('sh', '-c', $Command)

    return Invoke-AdbResult -Arguments $arguments
}

function Get-DeviceProperty {
    param([Parameter(Mandatory = $true)][string]$Name)
    return (Invoke-Adb -Arguments @('shell', 'getprop', $Name)).Trim()
}

function Get-DeviceMetadata {
    $state = (Invoke-Adb -Arguments @('get-state')).Trim()
    if ($state -ne 'device') {
        throw "Android serial $script:TargetSerial is not in the usable 'device' state (reported '$state')."
    }

    $manufacturer = Get-DeviceProperty -Name 'ro.product.manufacturer'
    $model = Get-DeviceProperty -Name 'ro.product.model'
    $fingerprint = Get-DeviceProperty -Name 'ro.build.fingerprint'
    $sdk = Get-DeviceProperty -Name 'ro.build.version.sdk'
    $abi = Get-DeviceProperty -Name 'ro.product.cpu.abi'
    $abiList = Get-DeviceProperty -Name 'ro.product.cpu.abilist'
    $device = Get-DeviceProperty -Name 'ro.product.device'
    $hardware = Get-DeviceProperty -Name 'ro.hardware'
    $kernelQemu = Get-DeviceProperty -Name 'ro.kernel.qemu'
    $bootQemu = Get-DeviceProperty -Name 'ro.boot.qemu'
    $memInfo = Invoke-Adb -Arguments @('shell', 'cat', '/proc/meminfo')

    $emulatorSignals = New-Object System.Collections.Generic.List[string]
    if ($script:TargetSerial -match '(?i)^emulator-') { [void]$emulatorSignals.Add('serial') }
    if ($kernelQemu -eq '1') { [void]$emulatorSignals.Add('ro.kernel.qemu') }
    if ($bootQemu -eq '1') { [void]$emulatorSignals.Add('ro.boot.qemu') }
    if (("$fingerprint $model $device" -match '(?i)(generic|emulator|sdk_gphone|android sdk)')) { [void]$emulatorSignals.Add('build identity') }
    if ($hardware -match '(?i)(goldfish|ranchu)') { [void]$emulatorSignals.Add('hardware') }

    if ($emulatorSignals.Count -gt 0 -and -not $AllowEmulator) {
        $signalText = $emulatorSignals -join ', '
        throw "Android serial $script:TargetSerial appears to be an emulator ($signalText). This collector requires a physical device by default; -AllowEmulator is an explicit non-release override."
    }

    return [ordered]@{
        serial               = $script:TargetSerial
        manufacturer         = $manufacturer
        model                = $model
        api_level            = (Get-NullableInt64FromRegex -Text $sdk -Pattern '(?<value>\d+)')
        primary_abi          = $abi
        abi_list             = $abiList
        build_fingerprint    = $fingerprint
        product_device       = $device
        hardware             = $hardware
        memory_total_kb      = (Get-NullableInt64FromRegex -Text $memInfo -Pattern '(?m)^MemTotal:\s*(?<value>\d+)\s*kB')
        memory_available_kb  = (Get-NullableInt64FromRegex -Text $memInfo -Pattern '(?m)^MemAvailable:\s*(?<value>\d+)\s*kB')
        emulator_detected    = ($emulatorSignals.Count -gt 0)
        emulator_signals     = @($emulatorSignals.ToArray())
        emulator_override    = [bool]$AllowEmulator
    }
}

function Get-MemorySnapshot {
    $raw = Invoke-Adb -Arguments @('shell', 'dumpsys', 'meminfo', $script:TargetPackage)
    $notRunning = $raw -match '(?i)(No process found|No process with pid|Unable to find.*process)'
    $javaMatch = [regex]::Match($raw, '(?m)^\s*Java Heap:\s*(?<value>\d+)')
    $nativeMatch = [regex]::Match($raw, '(?m)^\s*Native Heap:\s*(?<value>\d+)')
    $totalPss = $null

    if ($javaMatch.Success) {
        $summaryTail = $raw.Substring($javaMatch.Index)
        $totalPss = Get-NullableInt64FromRegex -Text $summaryTail -Pattern '(?m)^\s*TOTAL(?: PSS)?:\s*(?<value>\d+)'
    }

    return [ordered]@{
        captured_utc       = Get-UtcTimestamp
        source              = 'adb shell dumpsys meminfo <package> (App Summary PSS)'
        process_running     = (-not $notRunning -and $javaMatch.Success)
        java_heap_pss_kb   = $(if ($javaMatch.Success) { [Int64]$javaMatch.Groups['value'].Value } else { $null })
        native_heap_pss_kb = $(if ($nativeMatch.Success) { [Int64]$nativeMatch.Groups['value'].Value } else { $null })
        total_pss_kb       = $totalPss
        raw_sha256         = Get-Sha256Text -Text $raw
        raw_length         = $raw.Length
    }
}

function Get-AppCpuSnapshot {
    $clockResult = Invoke-AdbResult -Arguments @('shell', 'getconf', 'CLK_TCK')
    $clockTicks = $null
    if ($clockResult.exit_code -eq 0) {
        $clockTicks = Get-NullableInt64FromRegex -Text $clockResult.output -Pattern '(?<value>\d+)'
    }

    $pidResult = Invoke-AdbResult -Arguments @('shell', 'pidof', $script:TargetPackage)
    $processes = New-Object System.Collections.Generic.List[object]
    $diagnostics = New-Object System.Collections.Generic.List[string]
    if ($pidResult.exit_code -eq 0) {
        $pids = @($pidResult.output -split '\s+' | Where-Object { $_ -match '^\d+$' })
        foreach ($pid in $pids) {
            $statResult = Invoke-AdbResult -Arguments @('shell', 'cat', "/proc/$pid/stat")
            if ($statResult.exit_code -ne 0) {
                [void]$diagnostics.Add("Unable to read /proc/$pid/stat: $($statResult.output)")
                continue
            }

            $closingParen = $statResult.output.LastIndexOf(')')
            if ($closingParen -lt 0) {
                [void]$diagnostics.Add("Unexpected /proc/$pid/stat format.")
                continue
            }

            $fields = @($statResult.output.Substring($closingParen + 1).Trim() -split '\s+')
            # Fields are numbered from 3 after the command name. utime and stime are 14 and 15.
            if ($fields.Count -lt 13) {
                [void]$diagnostics.Add("Incomplete /proc/$pid/stat fields.")
                continue
            }

            [void]$processes.Add([ordered]@{
                pid            = [Int64]$pid
                user_jiffies   = [Int64]$fields[11]
                system_jiffies = [Int64]$fields[12]
            })
        }
    }
    elseif (-not [string]::IsNullOrWhiteSpace($pidResult.output)) {
        [void]$diagnostics.Add("pidof failed: $($pidResult.output)")
    }

    return [ordered]@{
        captured_utc         = Get-UtcTimestamp
        source               = 'adb shell pidof + /proc/<pid>/stat'
        clock_ticks_per_sec  = $clockTicks
        processes            = @($processes.ToArray())
        diagnostics          = @($diagnostics.ToArray())
    }
}

function Get-AppCpuDelta {
    param(
        [Parameter(Mandatory = $true)]$Baseline,
        [Parameter(Mandatory = $true)]$Final
    )

    $baselinePids = @($Baseline.processes | ForEach-Object { [string]$_.pid } | Sort-Object)
    $finalPids = @($Final.processes | ForEach-Object { [string]$_.pid } | Sort-Object)
    if ($baselinePids.Count -eq 0 -or $finalPids.Count -eq 0) {
        return [ordered]@{ comparable = $false; reason = 'The app process was absent at baseline or final capture.' }
    }
    if (($baselinePids -join ',') -ne ($finalPids -join ',')) {
        return [ordered]@{ comparable = $false; reason = 'The app process set changed during the workload.' }
    }
    if ($Baseline.clock_ticks_per_sec -eq $null -or $Baseline.clock_ticks_per_sec -le 0 -or $Baseline.clock_ticks_per_sec -ne $Final.clock_ticks_per_sec) {
        return [ordered]@{ comparable = $false; reason = 'CLK_TCK was unavailable or changed between captures.' }
    }

    $baselineByPid = @{}
    foreach ($process in $Baseline.processes) { $baselineByPid[[string]$process.pid] = $process }
    $userJiffies = [Int64]0
    $systemJiffies = [Int64]0
    foreach ($process in $Final.processes) {
        $before = $baselineByPid[[string]$process.pid]
        $userJiffies += ([Int64]$process.user_jiffies - [Int64]$before.user_jiffies)
        $systemJiffies += ([Int64]$process.system_jiffies - [Int64]$before.system_jiffies)
    }

    $ticks = [double]$Baseline.clock_ticks_per_sec
    return [ordered]@{
        comparable     = $true
        user_jiffies   = $userJiffies
        system_jiffies = $systemJiffies
        total_jiffies  = ($userJiffies + $systemJiffies)
        user_ms        = [Math]::Round(($userJiffies * 1000.0) / $ticks, 3)
        system_ms      = [Math]::Round(($systemJiffies * 1000.0) / $ticks, 3)
        total_ms       = [Math]::Round((($userJiffies + $systemJiffies) * 1000.0) / $ticks, 3)
    }
}

function Get-RemotePathMeasurement {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$RemotePath
    )

    $exists = Invoke-StorageShell -Command "test -e $RemotePath"
    if ($exists.exit_code -eq 1) {
        return [ordered]@{
            label                = $Label
            path                 = $RemotePath
            exists               = $false
            kind                 = 'missing'
            logical_size_bytes   = $null
            allocated_size_kib   = $null
        }
    }
    if ($exists.exit_code -ne 0) {
        throw "Cannot inspect $Label at $RemotePath using $script:StorageMode: $($exists.output)"
    }

    $directory = Invoke-StorageShell -Command "test -d $RemotePath"
    if ($directory.exit_code -ne 0 -and $directory.exit_code -ne 1) {
        throw "Cannot determine whether $Label is a directory: $($directory.output)"
    }

    $isDirectory = ($directory.exit_code -eq 0)
    $logicalBytes = $null
    if (-not $isDirectory) {
        $byteCount = Invoke-StorageShell -Command "wc -c < $RemotePath"
        if ($byteCount.exit_code -ne 0) {
            throw "Cannot measure $Label byte length: $($byteCount.output)"
        }
        $logicalBytes = Get-NullableInt64FromRegex -Text $byteCount.output -Pattern '(?<value>\d+)'
    }

    $allocated = Invoke-StorageShell -Command "du -sk $RemotePath"
    if ($allocated.exit_code -ne 0) {
        throw "Cannot measure $Label allocated storage: $($allocated.output)"
    }

    return [ordered]@{
        label                = $Label
        path                 = $RemotePath
        exists               = $true
        kind                 = $(if ($isDirectory) { 'directory' } else { 'file' })
        logical_size_bytes   = $logicalBytes
        allocated_size_kib   = (Get-NullableInt64FromRegex -Text $allocated.output -Pattern '^\s*(?<value>\d+)')
    }
}

function Get-StorageSnapshot {
    return [ordered]@{
        captured_utc       = Get-UtcTimestamp
        access_mode        = $script:StorageMode
        database           = Get-RemotePathMeasurement -Label 'database' -RemotePath $script:DatabaseRemotePath
        wal                = Get-RemotePathMeasurement -Label 'wal' -RemotePath $script:WalRemotePath
        private_snapshot   = Get-RemotePathMeasurement -Label 'private_snapshot' -RemotePath $script:SnapshotRemotePath
        temporary_storage  = Get-RemotePathMeasurement -Label 'temporary_storage' -RemotePath $script:TempRemotePath
    }
}

function Get-MeasurementDelta {
    param($Before, $After)

    $logicalDelta = $null
    if ($Before.logical_size_bytes -ne $null -and $After.logical_size_bytes -ne $null) {
        $logicalDelta = [Int64]$After.logical_size_bytes - [Int64]$Before.logical_size_bytes
    }

    $allocatedDelta = $null
    if ($Before.allocated_size_kib -ne $null -and $After.allocated_size_kib -ne $null) {
        $allocatedDelta = [Int64]$After.allocated_size_kib - [Int64]$Before.allocated_size_kib
    }

    return [ordered]@{
        logical_size_bytes_delta = $logicalDelta
        allocated_size_kib_delta = $allocatedDelta
    }
}

function Get-StorageDelta {
    param(
        [Parameter(Mandatory = $true)]$Baseline,
        [Parameter(Mandatory = $true)]$Final
    )

    return [ordered]@{
        database          = Get-MeasurementDelta -Before $Baseline.database -After $Final.database
        wal               = Get-MeasurementDelta -Before $Baseline.wal -After $Final.wal
        private_snapshot  = Get-MeasurementDelta -Before $Baseline.private_snapshot -After $Final.private_snapshot
        temporary_storage = Get-MeasurementDelta -Before $Baseline.temporary_storage -After $Final.temporary_storage
    }
}

function Get-ResidueSummary {
    param([Parameter(Mandatory = $true)]$FinalStorage)

    $remaining = New-Object System.Collections.Generic.List[object]
    foreach ($measurement in @($FinalStorage.private_snapshot, $FinalStorage.temporary_storage)) {
        if ($measurement.exists) {
            [void]$remaining.Add([ordered]@{
                label              = $measurement.label
                path               = $measurement.path
                kind               = $measurement.kind
                logical_size_bytes = $measurement.logical_size_bytes
                allocated_size_kib = $measurement.allocated_size_kib
            })
        }
    }

    return [ordered]@{
        database_exists_after          = [bool]$FinalStorage.database.exists
        wal_exists_after               = [bool]$FinalStorage.wal.exists
        private_snapshot_exists_after  = [bool]$FinalStorage.private_snapshot.exists
        temporary_storage_exists_after = [bool]$FinalStorage.temporary_storage.exists
        remaining_ephemeral_paths      = @($remaining.ToArray())
    }
}

function Get-FixtureHashes {
    param([string[]]$Paths)

    $hashes = New-Object System.Collections.Generic.List[object]
    foreach ($path in @($Paths)) {
        $item = Get-Item -LiteralPath $path -ErrorAction Stop
        if (-not $item.PSIsContainer) {
            [void]$hashes.Add([ordered]@{
                path       = $item.FullName
                kind       = 'file'
                file_count = 1
                bytes      = [Int64]$item.Length
                sha256     = ((Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash).ToLowerInvariant()
            })
            continue
        }

        $files = @(Get-ChildItem -LiteralPath $item.FullName -Recurse -File | Sort-Object FullName)
        $manifest = New-Object System.Collections.Generic.List[string]
        $totalBytes = [Int64]0
        $root = $item.FullName.TrimEnd([char[]]@('\', '/'))
        foreach ($file in $files) {
            $relative = $file.FullName.Substring($root.Length).TrimStart([char[]]@('\', '/'))
            $sha = ((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash).ToLowerInvariant()
            [void]$manifest.Add("$relative|$($file.Length)|$sha")
            $totalBytes += [Int64]$file.Length
        }

        [void]$hashes.Add([ordered]@{
            path       = $item.FullName
            kind       = 'directory_manifest'
            file_count = $files.Count
            bytes      = $totalBytes
            sha256     = Get-Sha256Text -Text ([string]::Join("`n", [string[]]$manifest.ToArray()))
        })
    }

    return @($hashes.ToArray())
}

function Start-HostCommand {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory
    )

    return Start-Process -FilePath $env:ComSpec -ArgumentList @('/d', '/s', '/c', $Command) -PassThru -NoNewWindow -WorkingDirectory $WorkingDirectory
}

function Invoke-Workload {
    if ([string]::IsNullOrWhiteSpace($WorkloadCommand)) {
        return [ordered]@{
            command_provided          = $false
            command                   = $null
            command_sha256            = $null
            started_utc               = $null
            finished_utc              = $null
            wall_ms                   = $null
            host_command_cpu_ms       = $null
            exit_code                 = $null
            timed_out                 = $false
            cancellation              = [ordered]@{ requested = $false; command = $null; command_sha256 = $null; requested_at_wall_ms = $null; exit_code = $null; timed_out = $false }
        }
    }

    $startedUtc = Get-UtcTimestamp
    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    $process = Start-HostCommand -Command $WorkloadCommand -WorkingDirectory $script:WorkloadDirectory
    $timedOut = $false
    $cancellationIssued = $false
    $cancellation = [ordered]@{
        requested            = $false
        command              = $CancellationCommand
        command_sha256       = $(if ([string]::IsNullOrWhiteSpace($CancellationCommand)) { $null } else { Get-Sha256Text -Text $CancellationCommand })
        requested_at_wall_ms = $null
        exit_code            = $null
        timed_out            = $false
    }

    while (-not $process.HasExited) {
        $elapsedSeconds = $stopwatch.Elapsed.TotalSeconds
        if (-not $cancellationIssued -and -not [string]::IsNullOrWhiteSpace($CancellationCommand) -and $elapsedSeconds -ge $CancelAfterSeconds) {
            $cancellationIssued = $true
            $cancellation.requested = $true
            $cancellation.requested_at_wall_ms = [Math]::Round($stopwatch.Elapsed.TotalMilliseconds, 3)
            $cancelProcess = Start-HostCommand -Command $CancellationCommand -WorkingDirectory $script:WorkloadDirectory
            if (-not $cancelProcess.WaitForExit($CancellationCommandTimeoutSeconds * 1000)) {
                Stop-Process -Id $cancelProcess.Id -Force
                $cancelProcess.WaitForExit()
                $cancellation.timed_out = $true
            }
            $cancelProcess.Refresh()
            if (-not $cancellation.timed_out) {
                $cancellation.exit_code = [int]$cancelProcess.ExitCode
            }
        }

        if ($elapsedSeconds -ge $WorkloadTimeoutSeconds) {
            $timedOut = $true
            Stop-Process -Id $process.Id -Force
            $process.WaitForExit()
            break
        }

        Start-Sleep -Milliseconds 100
    }

    $stopwatch.Stop()
    $process.Refresh()
    $exitCode = $null
    if (-not $timedOut) {
        $exitCode = [int]$process.ExitCode
    }

    return [ordered]@{
        command_provided    = $true
        command             = $WorkloadCommand
        command_sha256      = Get-Sha256Text -Text $WorkloadCommand
        started_utc         = $startedUtc
        finished_utc        = Get-UtcTimestamp
        wall_ms             = [Math]::Round($stopwatch.Elapsed.TotalMilliseconds, 3)
        host_command_cpu_ms = [Math]::Round($process.TotalProcessorTime.TotalMilliseconds, 3)
        exit_code           = $exitCode
        timed_out           = $timedOut
        cancellation        = $cancellation
    }
}

function Get-RepositoryMetadata {
    $repositoryRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    $head = $null
    $dirty = $null
    $statusHash = $null
    try {
        $headOutput = & git -C $repositoryRoot rev-parse HEAD 2>$null
        if ($LASTEXITCODE -eq 0) {
            $head = ($headOutput | Out-String).Trim()
            $statusOutput = & git -C $repositoryRoot status --porcelain 2>$null
            if ($LASTEXITCODE -eq 0) {
                $statusText = ($statusOutput | Out-String).TrimEnd()
                $dirty = -not [string]::IsNullOrWhiteSpace($statusText)
                $statusHash = Get-Sha256Text -Text $statusText
            }
        }
    }
    catch {
        # Git metadata is helpful evidence, but not required for device collection.
    }

    return [ordered]@{
        git_head          = $head
        working_tree_dirty = $dirty
        status_sha256     = $statusHash
        collector_sha256  = ((Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash).ToLowerInvariant()
    }
}

if ([string]::IsNullOrWhiteSpace($WalPath)) {
    $WalPath = "$DatabasePath-wal"
}
if ($CancelAfterSeconds -gt 0 -and [string]::IsNullOrWhiteSpace($CancellationCommand)) {
    throw '-CancelAfterSeconds requires -CancellationCommand.'
}
if (-not [string]::IsNullOrWhiteSpace($CancellationCommand) -and $CancelAfterSeconds -eq 0) {
    throw '-CancellationCommand requires a positive -CancelAfterSeconds.'
}

Assert-RemotePath -Path $DatabasePath -Label 'DatabasePath'
Assert-RemotePath -Path $WalPath -Label 'WalPath'
Assert-RemotePath -Path $SnapshotPath -Label 'SnapshotPath'
Assert-RemotePath -Path $TempPath -Label 'TempPath'

$workloadDirectoryItem = Get-Item -LiteralPath $WorkloadWorkingDirectory -ErrorAction Stop
if (-not $workloadDirectoryItem.PSIsContainer) {
    throw "WorkloadWorkingDirectory is not a directory: $WorkloadWorkingDirectory"
}
$resolvedOutputPath = Resolve-OutputFilePath -Path $OutputPath
$adbCommand = Get-Command -Name $AdbPath -ErrorAction Stop

$script:AdbExecutable = $adbCommand.Source
$script:TargetSerial = $DeviceSerial
$script:TargetPackage = $PackageName
$script:StorageMode = $StorageAccessMode
$script:DatabaseRemotePath = $DatabasePath
$script:WalRemotePath = $WalPath
$script:SnapshotRemotePath = $SnapshotPath
$script:TempRemotePath = $TempPath
$script:WorkloadDirectory = $workloadDirectoryItem.FullName

$device = Get-DeviceMetadata
$fixtures = Get-FixtureHashes -Paths $FixturePath
$baselineMemory = $null
$baselineCpu = $null
$baselineStorage = $null
$finalMemory = $null
$finalCpu = $null
$finalStorage = $null
$workload = $null
$collectorErrors = New-Object System.Collections.Generic.List[string]

try {
    $baselineMemory = Get-MemorySnapshot
    $baselineCpu = Get-AppCpuSnapshot
    $baselineStorage = Get-StorageSnapshot
}
catch {
    [void]$collectorErrors.Add("Baseline capture failed: $($_.Exception.Message)")
}

if ($collectorErrors.Count -eq 0) {
    try {
        $workload = Invoke-Workload
    }
    catch {
        [void]$collectorErrors.Add("Workload launch or monitoring failed: $($_.Exception.Message)")
    }
}

try {
    $finalMemory = Get-MemorySnapshot
    $finalCpu = Get-AppCpuSnapshot
    $finalStorage = Get-StorageSnapshot
}
catch {
    [void]$collectorErrors.Add("Final capture failed: $($_.Exception.Message)")
}

$storageDelta = $null
$residue = $null
if ($baselineStorage -ne $null -and $finalStorage -ne $null) {
    $storageDelta = Get-StorageDelta -Baseline $baselineStorage -Final $finalStorage
    $residue = Get-ResidueSummary -FinalStorage $finalStorage
}

$appCpuDelta = $null
if ($baselineCpu -ne $null -and $finalCpu -ne $null) {
    $appCpuDelta = Get-AppCpuDelta -Baseline $baselineCpu -Final $finalCpu
}

$resultStatus = 'completed'
if ($collectorErrors.Count -gt 0) {
    $resultStatus = 'collector_error'
}
elseif ($workload -ne $null -and $workload.timed_out) {
    $resultStatus = 'timed_out'
}
elseif ($workload -ne $null -and $workload.command_provided -and $workload.exit_code -ne 0) {
    $resultStatus = 'nonzero_exit'
}

$evidence = [ordered]@{
    schema_version = 1
    collected_utc  = Get-UtcTimestamp
    purpose        = 'V10 low-end physical-device import evidence; collection only, not PBF re-enablement.'
    repository     = Get-RepositoryMetadata
    device         = $device
    package        = $PackageName
    workload       = [ordered]@{
        id             = $WorkloadId
        fixture_hashes = $fixtures
        execution      = $workload
    }
    memory_kb      = [ordered]@{
        baseline = $baselineMemory
        final    = $finalMemory
    }
    app_cpu        = [ordered]@{
        baseline = $baselineCpu
        final    = $finalCpu
        delta    = $appCpuDelta
    }
    storage        = [ordered]@{
        baseline = $baselineStorage
        final    = $finalStorage
        delta    = $storageDelta
    }
    residue        = $residue
    result         = [ordered]@{
        status                          = $resultStatus
        allow_nonzero_workload_exit     = [bool]$AllowNonZeroWorkloadExit
        collector_errors                = @($collectorErrors.ToArray())
    }
}

$json = $evidence | ConvertTo-Json -Depth 16
[System.IO.File]::WriteAllText($resolvedOutputPath, $json + [Environment]::NewLine, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "V10 benchmark evidence written to $resolvedOutputPath"

if ($collectorErrors.Count -gt 0) {
    Write-Error ($collectorErrors -join [Environment]::NewLine)
    exit 1
}
if ($workload -ne $null -and $workload.timed_out) {
    Write-Error "Workload timed out after $WorkloadTimeoutSeconds seconds. The command shell was stopped; inspect residue and the device before re-running."
    exit 1
}
if ($workload -ne $null -and $workload.command_provided -and $workload.exit_code -ne 0 -and -not $AllowNonZeroWorkloadExit) {
    Write-Error "Workload exited with $($workload.exit_code). The evidence file was retained for diagnosis. Use -AllowNonZeroWorkloadExit only when that exit is an explicitly expected scenario."
    exit 1
}
