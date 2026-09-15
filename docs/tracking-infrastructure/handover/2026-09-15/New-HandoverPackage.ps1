param(
    [Parameter(Mandatory = $true)][string]$Repository,
    [Parameter(Mandatory = $true)][string]$ProtectedRoot,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [string]$OriginalVisionFile
)

# Artifact generation only: no build, tests, schema, lint, diff-check, network, mutation of
# source checkouts, or cleanup. Original drafts are copied into a clearly unaccepted quarantine.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$repositoryPath = (Resolve-Path -LiteralPath $Repository).Path
$protectedRootPath = (Resolve-Path -LiteralPath $ProtectedRoot).Path
$outputPath = [IO.Path]::GetFullPath($OutputDirectory)
if (-not $outputPath.StartsWith($protectedRootPath + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Package output must be a new directory inside the explicitly named workspace.'
}
if (Test-Path -LiteralPath $outputPath) { throw 'Refusing to overwrite an existing package directory.' }

function Invoke-ReadGit([string]$Checkout, [string[]]$Arguments) {
    $gitOutput = @(& git -c core.fsmonitor=false -c "safe.directory=$Checkout" -C $Checkout @Arguments)
    if ($LASTEXITCODE -ne 0) { throw "Git artifact/read operation failed: $($Arguments[0])" }
    return $gitOutput
}
function Write-GeneratedJson([string]$Path, $Value) {
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 16), [Text.UTF8Encoding]::new($false))
}

$head = (@(Invoke-ReadGit $repositoryPath @('rev-parse', 'HEAD')))[0]
$branch = (@(Invoke-ReadGit $repositoryPath @('branch', '--show-current')))[0]
$trackedStatus = @(Invoke-ReadGit $repositoryPath @('status', '--porcelain=v1', '--untracked-files=all'))
if ($branch -ne 'dev/v10' -or $trackedStatus.Count -ne 0) {
    throw 'Generate only from the clean committed local dev/v10 checkpoint.'
}
New-Item -ItemType Directory -Path $outputPath | Out-Null
$payloadPath = Join-Path $outputPath 'payload'
New-Item -ItemType Directory -Path $payloadPath | Out-Null
$sourceArchive = Join-Path $outputPath 'source-snapshot.zip'
Invoke-ReadGit $repositoryPath @('archive', '--format=zip', "--output=$sourceArchive", $head) | Out-Null
[IO.Compression.ZipFile]::ExtractToDirectory($sourceArchive, (Join-Path $payloadPath 'source'))
$handoverRelative = 'docs/tracking-infrastructure/handover/2026-09-15'
Copy-Item -LiteralPath (Join-Path $repositoryPath "$handoverRelative/README_START_HERE.md") -Destination $payloadPath
Copy-Item -LiteralPath (Join-Path $repositoryPath "$handoverRelative/HANDOVER_PROMPT.md") -Destination $payloadPath

$refs = @(Invoke-ReadGit $repositoryPath @('for-each-ref', '--format=%(refname:short)', 'refs/heads')) |
    Where-Object { $_.StartsWith('codex/ti-') -or $_ -eq 'codex/ti-handover-assembly-2026-09-15' }
$bundlePath = Join-Path $payloadPath 'tracker-tracking-history.bundle'
Invoke-ReadGit $repositoryPath (@('bundle', 'create', $bundlePath, 'dev/v10') + $refs) | Out-Null
$inventory = New-Object Collections.Generic.List[object]
foreach ($ref in $refs) {
    $tip = (@(Invoke-ReadGit $repositoryPath @('rev-parse', $ref)))[0]
    & git -c core.fsmonitor=false -c "safe.directory=$repositoryPath" -C $repositoryPath merge-base --is-ancestor $tip $head
    $isAncestor = $LASTEXITCODE -eq 0
    if ($LASTEXITCODE -gt 1) { throw 'Ancestry inspection failed.' }
    $patchDisposition = @(Invoke-ReadGit $repositoryPath @('cherry', $head, $ref))
    $inventory.Add([ordered]@{
        branch = $ref; tip = $tip; ancestorOfCheckpoint = $isAncestor
        patchDisposition = $patchDisposition
        changedPathsSincePublishedBase = @(Invoke-ReadGit $repositoryPath @('diff', '--name-only', '0460f12a54a3550244f8e8ea5c9fb1f5e2ef27d2', $tip))
        commitsSincePublishedBase = @(Invoke-ReadGit $repositoryPath @('log', '--format=%H %s', "0460f12a54a3550244f8e8ea5c9fb1f5e2ef27d2..$tip"))
    })
}
Write-GeneratedJson (Join-Path $payloadPath 'branch-and-path-inventory.json') $inventory
Write-GeneratedJson (Join-Path $payloadPath 'local-assembly-history.json') @(
    Invoke-ReadGit $repositoryPath @('log', '--first-parent', '--format=%H %s', '0460f12a54a3550244f8e8ea5c9fb1f5e2ef27d2..HEAD')
)

$quarantinePath = Join-Path $payloadPath 'QUARANTINE_DO_NOT_APPLY'
New-Item -ItemType Directory -Path $quarantinePath | Out-Null
Copy-Item -LiteralPath (Join-Path $repositoryPath "$handoverRelative/QUARANTINE.md") -Destination $quarantinePath
$draftCheckouts = @(
    [ordered]@{ name = 'protected-root'; path = $protectedRootPath },
    [ordered]@{ name = 'frozen-steps-portable-import'; path = (Join-Path $protectedRootPath '.worktrees/ti-steps-portable-import') },
    [ordered]@{ name = 'frozen-steps-qualified-awards'; path = (Join-Path $protectedRootPath '.worktrees/ti-steps-qualified-awards') }
)
$draftInventory = New-Object Collections.Generic.List[object]
foreach ($draft in $draftCheckouts) {
    $draftPath = (Resolve-Path -LiteralPath $draft.path).Path
    $draftOutput = Join-Path $quarantinePath $draft.name
    New-Item -ItemType Directory -Path $draftOutput | Out-Null
    $draftHead = (@(Invoke-ReadGit $draftPath @('rev-parse', 'HEAD')))[0]
    $draftStatus = @(Invoke-ReadGit $draftPath @('status', '--porcelain=v1', '--untracked-files=all'))
    $trackedPaths = @(Invoke-ReadGit $draftPath @('diff', '--name-only', 'HEAD'))
    $untrackedPaths = @(Invoke-ReadGit $draftPath @('ls-files', '--others', '--exclude-standard'))
    Invoke-ReadGit $draftPath @('diff', '--binary', "--output=$(Join-Path $draftOutput 'unaccepted-tracked.patch')", 'HEAD') | Out-Null
    $fileReceipts = New-Object Collections.Generic.List[object]
    foreach ($relativePath in @($trackedPaths + $untrackedPaths | Select-Object -Unique)) {
        if ($relativePath -match '(^|/)(build|\.gradle|\.kotlin|logs|captures)(/|$)' -or
            $relativePath -match '(?i)(local\.properties|\.jks$|\.keystore$|credential|secret)') {
            throw "Private/generated unexpected draft path requires manual classification: $relativePath"
        }
        $originalPath = [IO.Path]::GetFullPath((Join-Path $draftPath $relativePath))
        if (-not $originalPath.StartsWith($draftPath + [IO.Path]::DirectorySeparatorChar,
                [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe draft path.' }
        if (Test-Path -LiteralPath $originalPath -PathType Leaf) {
            $copyPath = Join-Path $draftOutput "files/$relativePath"
            New-Item -ItemType Directory -Path (Split-Path -Parent $copyPath) -Force | Out-Null
            Copy-Item -LiteralPath $originalPath -Destination $copyPath
            $fileReceipts.Add([ordered]@{
                path = $relativePath; sha256 = (Get-FileHash -LiteralPath $originalPath -Algorithm SHA256).Hash
                untracked = $relativePath -in $untrackedPaths
            })
        }
    }
    $draftInventory.Add([ordered]@{ name = $draft.name; originalHead = $draftHead; status = $draftStatus; files = $fileReceipts })
}
Write-GeneratedJson (Join-Path $payloadPath 'quarantine-inventory.json') $draftInventory

if ($OriginalVisionFile) {
    Copy-Item -LiteralPath $OriginalVisionFile -Destination (Join-Path $payloadPath 'USER_VISION_ORIGINAL.txt')
}
$receipt = [ordered]@{
    date = '2026-09-15'; localBranch = $branch; checkpointHead = $head
    publishedBase = '0460f12a54a3550244f8e8ea5c9fb1f5e2ef27d2'
    phase = 'IMPLEMENTATION_ONLY'; evidence = 'IMPLEMENTED_UNVALIDATED; static inspection/review only'
    pushedDuringHandover = $false; sourceRefCount = $refs.Count
    sourceSnapshotSha256 = (Get-FileHash -LiteralPath $sourceArchive -Algorithm SHA256).Hash
    historyBundleSha256 = (Get-FileHash -LiteralPath $bundlePath -Algorithm SHA256).Hash
    sourceFiles = @(Invoke-ReadGit $repositoryPath @('ls-tree', '-r', '--name-only', $head))
    outOfScope = 'Other unrelated worktrees, caches, private local.properties/SDK/signing configuration, logs and credentials'
}
Write-GeneratedJson (Join-Path $payloadPath 'CHECKPOINT_RECEIPT.json') $receipt
$zipPath = Join-Path $outputPath 'tracker-tracking-handover-2026-09-15.zip'
[IO.Compression.ZipFile]::CreateFromDirectory($payloadPath, $zipPath, [IO.Compression.CompressionLevel]::Optimal, $false)
$zipReceipt = [ordered]@{ path = $zipPath; checkpointHead = $head; sha256 = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash; bytes = (Get-Item -LiteralPath $zipPath).Length }
Write-GeneratedJson (Join-Path $outputPath 'ZIP_RECEIPT.json') $zipReceipt
$zipReceipt | ConvertTo-Json
