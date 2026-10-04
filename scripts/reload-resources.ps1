<#
.SYNOPSIS
    Rebuilds the mod's resources while a dev client/server (gradlew runClient) keeps running,
    so changes can be applied in-game without a restart.

    The dev run loads the mod straight from build/resources/main (modSource sourceSets.main),
    so after this script finishes:
      - assets (textures, models, blockstates, lang): press F3+T in-game
      - data (recipes, loot tables, tags): run /reload in-game

    Java changes are not picked up: the game has already loaded those classes, so they still
    need a restart.

.PARAMETER Datagen
    Run the data generators (runData) first, for changes to datagen providers
    (e.g. ModBlockStateProvider) that write to src/generated/resources.

.PARAMETER Watch
    Keep running and rebuild whenever a file under src/main/resources or src/generated/resources
    changes. Stop with Ctrl+C.
#>
param(
    [switch]$Datagen,
    [switch]$Watch
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

function Invoke-Rebuild {
    $tasks = @()
    if ($Datagen) { $tasks += "runData" }
    $tasks += "processResources"
    # The datagen JVM logs heavily even with --quiet, so only show the output when something fails.
    $output = & .\gradlew.bat @tasks --quiet 2>&1
    if ($LASTEXITCODE -ne 0) {
        $output | Write-Host
        Write-Host "Rebuild failed (exit $LASTEXITCODE)." -ForegroundColor Red
        return
    }
    Write-Host "[$(Get-Date -Format HH:mm:ss)] Resources rebuilt. F3+T for assets, /reload for data." -ForegroundColor Green
}

Invoke-Rebuild
if (-not $Watch) { return }

$watchDirs = @("src\main\resources", "src\generated\resources") | ForEach-Object { Join-Path $repoRoot $_ }
$watchers = foreach ($dir in $watchDirs) {
    $w = New-Object System.IO.FileSystemWatcher $dir
    $w.IncludeSubdirectories = $true
    $w.NotifyFilter = [System.IO.NotifyFilters]'FileName, LastWrite, DirectoryName'
    $w
}

Write-Host "Watching for resource changes. Ctrl+C to stop."
try {
    while ($true) {
        # Wait for the first change, then let a burst of writes (e.g. the texture generator) settle.
        $changed = $false
        foreach ($w in $watchers) {
            if (-not $w.WaitForChanged([System.IO.WatcherChangeTypes]::All, 250).TimedOut) { $changed = $true }
        }
        if (-not $changed) { continue }
        do {
            $more = $false
            foreach ($w in $watchers) {
                if (-not $w.WaitForChanged([System.IO.WatcherChangeTypes]::All, 500).TimedOut) { $more = $true }
            }
        } while ($more)
        Invoke-Rebuild
    }
} finally {
    $watchers | ForEach-Object { $_.Dispose() }
}
