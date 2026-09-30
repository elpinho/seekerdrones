<#
.SYNOPSIS
    TPS profiling for drones (ROADMAP M3: "dozens of drones don't noticeably hurt TPS").

    Boots the dev server (runServer) on a throwaway flat world with RCON enabled, then runs a set of
    scenarios through RCON. For each scenario it lets the server settle, reads MSPT with /tick query,
    records 10 s with vanilla /perf, and pulls the per-entity-type time out of the profiling.txt in the
    /perf zip. The report is written to build/profiling/.

    runs/server/server.properties is backed up and restored afterwards, and the game JVM is killed the
    same way as in smoke-test-server.ps1 (see CLAUDE.md).

.PARAMETER Drones
    Drones per scenario.

.PARAMETER Targets
    Husks in the scenarios that have targets.

.PARAMETER SettleSeconds
    Wall-clock time to let a scenario settle before measuring.

.PARAMETER Scenarios
    Scenario names to run (default: all). See $allScenarios below.
#>
param(
    [int]$Drones = 100,
    [int]$Targets = 50,
    [int]$SettleSeconds = 10,
    [string[]]$Scenarios = @(),
    [int]$BootTimeoutSeconds = 240
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$serverDir = Join-Path $repoRoot "runs\server"
$propsFile = Join-Path $serverDir "server.properties"
$propsBackup = "$propsFile.profiling-backup"
$worldName = "profiling"
$rconPort = 25575
$rconPassword = "seekerdrones-profiling"

$outDir = Join-Path $repoRoot "build\profiling"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logFile = Join-Path $outDir "runServer-$stamp.log"
$reportFile = Join-Path $outDir "report-$stamp.md"

# --- Game process handling (same approach as smoke-test-server.ps1) ---

# Every run gets a unique tag, passed to Gradle as -PdevRunTag and put on the game JVM's command line as
# -Dseekerdrones.devRunTag (see build.gradle). Only the JVM carrying this run's tag is killed, so other
# dev runs of this repo (e.g. a client being playtested) are left alone.
$devRunTag = [guid]::NewGuid().ToString("N")

function Get-GameProcesses {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains("-Dseekerdrones.devRunTag=$devRunTag") }
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

# --- RCON ---

$script:rcon = $null
$script:rconId = 10

function Read-Exact($stream, [int]$count) {
    $buffer = New-Object byte[] $count
    $offset = 0
    while ($offset -lt $count) {
        $read = $stream.Read($buffer, $offset, $count - $offset)
        if ($read -le 0) { throw "RCON connection closed" }
        $offset += $read
    }
    return ,$buffer
}

function Send-RconPacket([int]$id, [int]$type, [string]$body) {
    $payload = [Text.Encoding]::UTF8.GetBytes($body)
    $ms = New-Object IO.MemoryStream
    $writer = New-Object IO.BinaryWriter($ms)
    $writer.Write([int](10 + $payload.Length))
    $writer.Write($id)
    $writer.Write($type)
    $writer.Write($payload)
    $writer.Write([byte]0)
    $writer.Write([byte]0)
    $bytes = $ms.ToArray()
    $script:rcon.Stream.Write($bytes, 0, $bytes.Length)
    $script:rcon.Stream.Flush()
}

function Read-RconPacket {
    $length = [BitConverter]::ToInt32((Read-Exact $script:rcon.Stream 4), 0)
    $data = Read-Exact $script:rcon.Stream $length
    return [pscustomobject]@{
        Id   = [BitConverter]::ToInt32($data, 0)
        Body = [Text.Encoding]::UTF8.GetString($data, 8, $length - 10)
    }
}

function Connect-Rcon {
    $deadline = (Get-Date).AddSeconds(30)
    while ($true) {
        try {
            $client = New-Object Net.Sockets.TcpClient
            $client.Connect("127.0.0.1", $rconPort)
            break
        } catch {
            if ((Get-Date) -gt $deadline) { throw "Could not connect to RCON on port $rconPort" }
            Start-Sleep -Seconds 1
        }
    }
    $stream = $client.GetStream()
    $stream.ReadTimeout = 120000
    $script:rcon = [pscustomobject]@{ Client = $client; Stream = $stream }
    Send-RconPacket 1 3 $rconPassword
    if ((Read-RconPacket).Id -eq -1) { throw "RCON authentication failed" }
}

# Minecraft's RCON handler drops the connection if two packets arrive in one read, so commands are
# strictly one at a time. Responses over 4096 bytes come as several packets, which are drained here.
function Invoke-Rcon([string]$command) {
    $script:rconId++
    Send-RconPacket $script:rconId 2 $command
    $packet = Read-RconPacket
    $body = $packet.Body
    while ([Text.Encoding]::UTF8.GetByteCount($packet.Body) -ge 4096) {
        Start-Sleep -Milliseconds 100
        if (-not $script:rcon.Stream.DataAvailable) { break }
        $packet = Read-RconPacket
        $body += $packet.Body
    }
    return $body
}

# --- World layout ---
# Default flat world: grass at y=-61, so mobs stand at y=-60. All work happens inside a forceloaded
# 128x128 area around the origin (forced chunks keep entities ticking with no player online).

$groundY = -60

function Get-Grid([int]$count, [double]$spacing, [double]$y) {
    $side = [Math]::Ceiling([Math]::Sqrt($count))
    $half = ($side - 1) * $spacing / 2
    $points = @()
    for ($i = 0; $i -lt $count; $i++) {
        $x = ($i % $side) * $spacing - $half + 0.5
        $z = [Math]::Floor($i / $side) * $spacing - $half + 0.5
        $points += , @($x, $y, $z)
    }
    return $points
}

function Summon-Grid([string]$type, [int]$count, [double]$spacing, [double]$y) {
    foreach ($p in (Get-Grid $count $spacing $y)) {
        $x = [string]::Format([Globalization.CultureInfo]::InvariantCulture, "{0} {1} {2}", $p[0], $p[1], $p[2])
        Invoke-Rcon "summon $type $x" | Out-Null
    }
}

function Reset-Area {
    Invoke-Rcon "kill @e[type=seekerdrones:drone]" | Out-Null
    Invoke-Rcon "kill @e[type=minecraft:husk]" | Out-Null
    Invoke-Rcon "kill @e[type=minecraft:item]" | Out-Null
    # Clear any structures from earlier scenarios (fill is capped at 32768 blocks per call).
    Invoke-Rcon "fill -24 $groundY -24 23 $($groundY + 7) 23 air" | Out-Null
}

function Set-DroneTargets {
    Invoke-Rcon "seekerdrones config target add entity minecraft:husk @e[type=seekerdrones:drone]" | Out-Null
}

function Set-DronePatrol {
    Invoke-Rcon "seekerdrones upgrade set patrol 1 @e[type=seekerdrones:drone]" | Out-Null
}

# Five 7-high walls across the middle of the area.
function Build-Walls {
    foreach ($x in @(-12, -6, 0, 6, 12)) {
        Invoke-Rcon "fill $x $groundY -13 $x $($groundY + 6) 12 stone" | Out-Null
    }
}

# A walled 32x32 pen so the husks can't wander out of the drones' pursuit range.
function Build-Pen([int]$height) {
    Invoke-Rcon "fill -17 $groundY -17 16 $($groundY + $height - 1) 16 stone hollow" | Out-Null
    Invoke-Rcon "fill -16 $groundY -16 15 $($groundY + $height - 1) 15 air" | Out-Null
}


# Husks in a sealed stone box: drones above it find candidates on every scan but never see one.
function Setup-SealedHusks {
    Invoke-Rcon "fill -17 $groundY -17 16 $($groundY + 3) 16 stone hollow" | Out-Null
    Summon-Grid "minecraft:husk" $Targets 4 ($groundY + 1)
}

function Setup-OpenHusks {
    Build-Pen 2
    Summon-Grid "minecraft:husk" $Targets 4 $groundY
}

# The pen split by 7-high walls, so the straight line to the follow position is often blocked.
function Setup-ObstacleHusks {
    Build-Pen 2
    Build-Walls
    Summon-Grid "minecraft:husk" $Targets 4 $groundY
}

# --- Scenarios ---
# Scenarios with husks have a husk-only baseline, so the MSPT delta is the drones' cost alone.

$allScenarios = [ordered]@{
    "empty" = @{
        Description = "Nothing spawned."
        Setup = { }
    }
    "idle" = @{
        Description = "$Drones drones with no targets configured, hovering."
        Baseline = "empty"
        Setup = {
            Summon-Grid "seekerdrones:drone" $Drones 3 ($groundY + 4)
        }
    }
    "scan-no-candidates" = @{
        Description = "$Drones drones targeting husks, none nearby. Staggered AABB scans that find nothing."
        Baseline = "empty"
        Setup = {
            Summon-Grid "seekerdrones:drone" $Drones 3 ($groundY + 4)
            Set-DroneTargets
        }
    }
    "husks-sealed" = @{
        Description = "$Targets husks in a sealed stone box, no drones."
        Setup = { Setup-SealedHusks }
    }
    "scan-blocked" = @{
        Description = "$Drones drones over a sealed stone box holding $Targets husks. Worst-case scans: every scan finds candidates and spends its full raycast budget without a hit."
        Baseline = "husks-sealed"
        Setup = {
            Setup-SealedHusks
            Summon-Grid "seekerdrones:drone" $Drones 3 ($groundY + 7)
            Set-DroneTargets
        }
    }
    "husks-open" = @{
        Description = "$Targets husks in an open pen, no drones."
        Setup = { Setup-OpenHusks }
    }
    "follow-open" = @{
        Description = "$Drones drones chasing/following $Targets husks in an open pen (direct flight)."
        Baseline = "husks-open"
        Setup = {
            Setup-OpenHusks
            Summon-Grid "seekerdrones:drone" $Drones 3 ($groundY + 3)
            Set-DroneTargets
        }
    }
    "husks-obstacles" = @{
        Description = "$Targets husks in a pen split by walls, no drones."
        Setup = { Setup-ObstacleHusks }
    }
    "follow-obstacles" = @{
        Description = "$Drones drones following $Targets husks in a pen split by 7-high walls, so drones often need paths."
        Baseline = "husks-obstacles"
        Setup = {
            Setup-ObstacleHusks
            Summon-Grid "seekerdrones:drone" $Drones 3 ($groundY + 3)
            Set-DroneTargets
        }
    }
    "patrol-open" = @{
        Description = "$Drones drones patrolling (1 Patrol upgrade, 16-block radius) over open ground, targeting husks with none nearby."
        Baseline = "empty"
        Setup = {
            Summon-Grid "seekerdrones:drone" $Drones 3 ($groundY + 3)
            Set-DroneTargets
            Set-DronePatrol
        }
    }
    "charging" = @{
        Description = "$Drones drones set to low energy next to 25 Charging Stations (8 blocks apart) (stocked far beyond capacity via NBT), so they queue, switch to free stations, dock and charge."
        Baseline = "empty"
        Setup = {
            foreach ($p in (Get-Grid 25 8 0)) {
                Invoke-Rcon "setblock $([Math]::Floor($p[0])) $groundY $([Math]::Floor($p[2])) seekerdrones:charging_station{Energy:2000000000}" | Out-Null
            }
            Summon-Grid "seekerdrones:drone" $Drones 3 ($groundY + 4)
            Invoke-Rcon "seekerdrones energy set 700 @e[type=seekerdrones:drone]" | Out-Null
        }
    }
    "patrol-obstacles" = @{
        Description = "$Drones drones patrolling (1 Patrol upgrade, 16-block radius) through 7-high walls, so waypoints often need paths or get skipped."
        Baseline = "empty"
        Setup = {
            Build-Walls
            Summon-Grid "seekerdrones:drone" $Drones 3 ($groundY + 3)
            Set-DroneTargets
            Set-DronePatrol
        }
    }
}

if ($Scenarios.Count -eq 0) { $Scenarios = @($allScenarios.Keys) }
foreach ($name in $Scenarios) {
    if (-not $allScenarios.Contains($name)) { throw "Unknown scenario '$name'. Known: $($allScenarios.Keys -join ', ')" }
}

# --- Measuring ---

$perfDir = Join-Path $serverDir "debug\profiling"

function Get-Count([string]$selector) {
    $text = Invoke-Rcon "execute if entity $selector"
    if ($text -match "count: (\d+)") { return [int]$Matches[1] }
    return 0
}

# /tick query averages the last 100 ticks, printed with 0.1 ms resolution. Several samples a few
# seconds apart are averaged to smooth out the rounding.
function Get-Mspt([int]$samples = 5) {
    $values = @()
    for ($i = 0; $i -lt $samples; $i++) {
        if ($i -gt 0) { Start-Sleep -Seconds 2 }
        $text = Invoke-Rcon "tick query"
        if ($text -match "Average time per tick: ([\d.]+)ms") { $values += [double]$Matches[1] }
    }
    if ($values.Count -eq 0) { return $null }
    return ($values | Measure-Object -Average).Average
}

# Runs /perf for its fixed 10 s and returns the profiling.txt from the zip it writes.
function Invoke-Perf {
    $before = @()
    if (Test-Path $perfDir) { $before = @(Get-ChildItem $perfDir -Filter *.zip | ForEach-Object FullName) }
    Invoke-Rcon "perf start" | Out-Null
    $deadline = (Get-Date).AddSeconds(40)
    $zip = $null
    while (-not $zip -and (Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 1
        if (Test-Path $perfDir) {
            $zip = Get-ChildItem $perfDir -Filter *.zip | Where-Object { $before -notcontains $_.FullName } | Select-Object -First 1
        }
    }
    if (-not $zip) { throw "/perf did not produce a report" }
    Start-Sleep -Seconds 1  # let the zip finish writing
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($zip.FullName)
    try {
        $entry = $archive.Entries | Where-Object { $_.FullName -like "*profiling.txt" } | Select-Object -First 1
        $reader = New-Object IO.StreamReader($entry.Open())
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally {
        $archive.Dispose()
    }
}

# Parses a vanilla profile dump into nodes with their time in ms per tick. The dump's global
# percentages only have two decimals of a mostly idle 50 ms tick, so each node's time is chained
# from its parent's time and its (more precise) percentage of the parent instead.
function Parse-Profile([string]$text) {
    $spanMs = 0.0; $ticks = 1.0
    if ($text -match "Time span: (\d+) ms") { $spanMs = [double]$Matches[1] }
    if ($text -match "Tick span: (\d+) ticks") { $ticks = [double]$Matches[1] }
    $nodes = New-Object Collections.Generic.List[object]
    $parentMs = @{}
    foreach ($line in ($text -split "`n")) {
        if ($line -match "^\[(\d+)\] (?:\|   )*([^#].*?)\((\d+)/(\d+)\) - ([\d.]+)%/([\d.]+)%") {
            $depth = [int]$Matches[1]
            $ms = if ($depth -eq 0) { [double]$Matches[6] / 100 * $spanMs / $ticks } else { $parentMs[$depth - 1] * [double]$Matches[5] / 100 }
            $parentMs[$depth] = $ms
            $nodes.Add([pscustomobject]@{ Depth = $depth; Name = $Matches[2]; Calls = [long]$Matches[3]; Ms = $ms })
        }
    }
    return $nodes
}

# The time of every section with this name (an entity type appears once per level), and the subtree
# of its first occurrence in the main level.
function Get-Section($nodes, [string]$name) {
    $total = 0.0
    $subtree = @()
    for ($i = 0; $i -lt $nodes.Count; $i++) {
        if ($nodes[$i].Name -ne $name) { continue }
        $total += $nodes[$i].Ms
        if ($subtree.Count -eq 0) {
            $depth = $nodes[$i].Depth
            for ($j = $i + 1; $j -lt $nodes.Count -and $nodes[$j].Depth -gt $depth; $j++) {
                $subtree += [pscustomobject]@{ Depth = $nodes[$j].Depth - $depth - 1; Name = $nodes[$j].Name; Calls = $nodes[$j].Calls; Ms = $nodes[$j].Ms }
            }
        }
    }
    return [pscustomobject]@{ Ms = $total; Subtree = $subtree }
}

# --- Run ---

Write-Host "Preparing server.properties and a fresh '$worldName' world..."
Copy-Item $propsFile $propsBackup -Force
$props = Get-Content $propsFile | Where-Object {
    $_ -notmatch "^(enable-rcon|rcon\.password|rcon\.port|level-name|level-type|generate-structures|spawn-monsters|spawn-animals|spawn-npcs|broadcast-rcon-to-ops)="
}
$props += "enable-rcon=true", "rcon.password=$rconPassword", "rcon.port=$rconPort", "level-name=$worldName",
    "level-type=minecraft\:flat", "generate-structures=false", "spawn-monsters=false", "spawn-animals=false",
    "spawn-npcs=false", "broadcast-rcon-to-ops=false"
Set-Content -Path $propsFile -Value $props
Remove-Item -Recurse -Force -ErrorAction SilentlyContinue (Join-Path $serverDir $worldName)

$gradle = $null
$results = [ordered]@{}
try {
    Write-Host "Starting 'gradlew runServer' (log: $logFile)..."
    $gradle = Start-Process -FilePath "$repoRoot\gradlew.bat" -ArgumentList @("runServer", "--console=plain", "-PdevRunTag=$devRunTag") `
        -RedirectStandardOutput $logFile -RedirectStandardError "$logFile.err" -PassThru -WindowStyle Hidden

    $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
    $booted = $false
    while ((Get-Date) -lt $deadline -and -not $gradle.HasExited) {
        $content = Get-Content $logFile -Raw -ErrorAction SilentlyContinue
        if ($content -match "Done \(") { $booted = $true; break }
        if ($content -match "FAILURE: Build failed") { break }
        Start-Sleep -Seconds 2
    }
    if (-not $booted) {
        Get-Content $logFile -Tail 40 -ErrorAction SilentlyContinue
        throw "Server did not boot within $BootTimeoutSeconds s"
    }
    Write-Host "Server booted. Connecting to RCON..."
    Connect-Rcon

    foreach ($cmd in @(
        "forceload add -64 -64 63 63",
        "gamerule doMobSpawning false", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false",
        "gamerule randomTickSpeed 0", "time set noon", "weather clear", "difficulty easy")) {
        Invoke-Rcon $cmd | Out-Null
    }
    Start-Sleep -Seconds 5  # let the forced chunks load

    foreach ($name in $Scenarios) {
        $scenario = $allScenarios[$name]
        Write-Host "Scenario '$name': setting up..."
        Reset-Area
        & $scenario.Setup
        Start-Sleep -Seconds $SettleSeconds

        $mspt = Get-Mspt
        $droneCount = Get-Count "@e[type=seekerdrones:drone]"
        $states = "chasing {0}, following {1}, returning {2}, charging {3}" -f (Get-Count '@e[type=seekerdrones:drone,nbt={State:"chasing"}]'),
            (Get-Count '@e[type=seekerdrones:drone,nbt={State:"following"}]'),
            (Get-Count '@e[type=seekerdrones:drone,nbt={State:"returning"}]'),
            (Get-Count '@e[type=seekerdrones:drone,nbt={State:"charging"}]')
        Write-Host "Scenario '$name': recording /perf (10 s)..."
        $profileText = Invoke-Perf
        Set-Content -Path (Join-Path $outDir "profile-$stamp-$name.txt") -Value $profileText
        $nodes = Parse-Profile $profileText
        $tickNode = $nodes | Where-Object { $_.Depth -eq 0 -and $_.Name -eq "tick" } | Select-Object -First 1

        $results[$name] = [pscustomobject]@{
            Name = $name; Scenario = $scenario; Mspt = $mspt; DroneCount = $droneCount; States = $states
            TickMs = if ($tickNode) { $tickNode.Ms } else { 0.0 }
            Drone = Get-Section $nodes "seekerdrones:drone"
            Husk = Get-Section $nodes "minecraft:husk"
        }
        Write-Host ("  MSPT {0:N2} ms, drones {1}, {2}" -f $mspt, $droneCount, $states)
    }
}
finally {
    if ($script:rcon) { $script:rcon.Client.Close() }
    Stop-GameProcesses $gradle
    Start-Sleep -Seconds 1
    Copy-Item $propsBackup $propsFile -Force
    Remove-Item $propsBackup -Force
    $stillRunning = Get-GameProcesses
    if ($stillRunning) {
        Write-Warning "Some game processes did not stop: $($stillRunning.ProcessId -join ', ')"
    } else {
        Write-Host "Cleanup confirmed: no leftover game processes; server.properties restored."
    }
}

# --- Report ---

$report = New-Object Text.StringBuilder
function Add-Report([string]$line = "") { [void]$report.AppendLine($line) }
$inv = [Globalization.CultureInfo]::InvariantCulture
function Fmt([double]$value, [string]$format = "0.000") { return $value.ToString($format, $inv) }

Add-Report "# Drone TPS profile ($stamp)"
Add-Report
Add-Report "$Drones drones, $Targets husks per scenario."
Add-Report
Add-Report "- **MSPT** is the real server tick time with the profiler off (/tick query, average of 5 samples). **Drone cost** is the MSPT increase over the scenario's baseline without drones."
Add-Report "- **Profiled** columns come from vanilla /perf (10 s). The profiler's own push/pop overhead inflates them a lot, so use them to compare sections, not as absolute cost."
Add-Report
Add-Report "| Scenario | Drones | States | MSPT | Drone cost (MSPT) | Per drone | Profiled tick | Profiled drones | Profiled husks |"
Add-Report "|---|---|---|---|---|---|---|---|---|"
foreach ($r in $results.Values) {
    $cost = "-"; $perDrone = "-"
    $baseline = $r.Scenario.Baseline
    if ($baseline -and $results.Contains($baseline)) {
        $delta = $r.Mspt - $results[$baseline].Mspt
        $cost = (Fmt $delta "0.00") + " ms"
        if ($r.DroneCount -gt 0) { $perDrone = (Fmt ($delta * 1000 / $r.DroneCount) "0.0") + " us" }
    }
    Add-Report ("| {0} | {1} | {2} | {3} ms | {4} | {5} | {6} ms | {7} ms | {8} ms |" -f $r.Name, $r.DroneCount, $r.States,
        (Fmt $r.Mspt "0.00"), $cost, $perDrone, (Fmt $r.TickMs "0.00"), (Fmt $r.Drone.Ms), (Fmt $r.Husk.Ms))
}
Add-Report
foreach ($r in $results.Values) {
    Add-Report "## $($r.Name)"
    Add-Report
    Add-Report $r.Scenario.Description
    Add-Report
    if ($r.Drone.Ms -gt 0) {
        # Sections under 1% of the drones' total are left out.
        Add-Report "Profiled drone tick sections (ms/tick for all drones, calls over the 10 s):"
        Add-Report
        Add-Report '```'
        foreach ($node in $r.Drone.Subtree) {
            if ($node.Ms -lt $r.Drone.Ms * 0.01) { continue }
            Add-Report ("{0}{1,-24} {2,8} ms {3,8} calls" -f ("  " * $node.Depth), $node.Name, (Fmt $node.Ms), $node.Calls)
        }
        Add-Report '```'
        Add-Report
    }
}

Set-Content -Path $reportFile -Value $report.ToString() -Encoding UTF8
Write-Host ""
Write-Host $report.ToString()
Write-Host "Report: $reportFile"
