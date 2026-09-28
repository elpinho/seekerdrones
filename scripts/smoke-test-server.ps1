<#
.SYNOPSIS
    Boots a NeoGradle dev run (e.g. runServer) headlessly, waits for a boot marker in its log,
    then force-kills the actual forked game JVM by matching its command line.

    Gradle's dev run tasks don't forward stdin to the forked game process, so piping "stop" to
    them does nothing; and killing the gradlew/daemon process tree on Windows doesn't kill the
    detached child JVM either. Matching on --launchTarget in the process list is the only
    reliable way found so far to clean these up without leaving an orphaned server/client running.

.PARAMETER Task
    The Gradle run task to execute (e.g. runServer, runClient, runGameTestServer).

.PARAMETER TimeoutSeconds
    How long to wait for the boot marker before giving up.

.PARAMETER DoneMarker
    Regex searched for in the log to confirm a successful boot. Pass -DoneMarker '' to skip
    boot detection and just run for TimeoutSeconds then stop (useful for tasks with no clear
    single-line boot marker, e.g. runClient).
#>
param(
    [string]$Task = "runServer",
    [int]$TimeoutSeconds = 180,
    [string]$DoneMarker = "Done \(",
    # Extra Gradle arguments, e.g. -GradleArgs '-PwithMekanism' to boot with Mekanism.
    [string[]]$GradleArgs = @()
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$logDir = Join-Path $repoRoot "build\smoke-test-logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$logFile = Join-Path $logDir "$Task.log"
Remove-Item -Force -ErrorAction SilentlyContinue $logFile

function Get-GameProcesses {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains($repoRoot) -and $_.CommandLine.Contains("--launchTarget") }
}

function Stop-GameProcesses($gradleProcess) {
    foreach ($p in (Get-GameProcesses)) {
        Write-Host "Stopping game process PID $($p.ProcessId)..."
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    }
    if ($gradleProcess -and -not $gradleProcess.HasExited) {
        Stop-Process -Id $gradleProcess.Id -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "Starting 'gradlew $Task' (log: $logFile)..."
$gradle = Start-Process -FilePath "$repoRoot\gradlew.bat" -ArgumentList (@($Task, "--console=plain") + $GradleArgs) `
    -RedirectStandardOutput $logFile -RedirectStandardError "$logFile.err" -PassThru -WindowStyle Hidden

$booted = $false
try {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Path $logFile) {
            $content = Get-Content $logFile -Raw -ErrorAction SilentlyContinue
            if ($DoneMarker -and $content -match $DoneMarker) {
                $booted = $true
                break
            }
            if ($content -match "FAILURE: Build failed") {
                break
            }
        }
        if ($gradle.HasExited) {
            break
        }
        Start-Sleep -Seconds 2
    }

    if (-not $DoneMarker) {
        $booted = -not $gradle.HasExited
    }

    if ($booted) {
        Write-Host "Boot confirmed."
    } else {
        Write-Host "Did not confirm a successful boot within $TimeoutSeconds s. Log tail:"
        Get-Content $logFile -Tail 40 -ErrorAction SilentlyContinue
    }
}
finally {
    Stop-GameProcesses $gradle
    Start-Sleep -Seconds 1
    $stillRunning = Get-GameProcesses
    if ($stillRunning) {
        Write-Warning "Some game processes did not stop: $($stillRunning.ProcessId -join ', ')"
    } else {
        Write-Host "Cleanup confirmed: no leftover game processes."
    }
}

if (-not $booted) { exit 1 }
