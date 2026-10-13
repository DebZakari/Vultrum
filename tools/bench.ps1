<#
.SYNOPSIS
Runs the scripted benchmark in the dev client and collects results under bench-runs/.

.EXAMPLE
powershell -File tools/bench.ps1 -Label sodium
powershell -File tools/bench.ps1 -Label sodium-complementary -Shaders
powershell -File tools/bench.ps1 -Label full-mix -Full   # shaders plus a full mod set, in run-full
powershell -File tools/bench.ps1 -Label sodium-stats -Stats   # pipeline statistics, not for timing
powershell -File tools/bench.ps1 -Label compat -Compat run-compat -Scenario bench/scenarios/compat.json   # a mod-compatibility set
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidatePattern('^[A-Za-z0-9._-]+$')] [string]$Label,
    [string]$Scenario = 'bench/scenarios/default.json',
    [switch]$Shaders,
    # The shaders setup plus a full mod set (run-full/mods). Implies -Shaders.
    [switch]$Full,
    # A mod-compatibility run dir (run-compat or run-compat-*), set up by hand like run-full. Implies -Shaders.
    [ValidatePattern('^run-compat[A-Za-z0-9-]*$')] [string]$Compat,
    # Pipeline-statistics queries around terrain draws. They cost GPU time, so keep stats runs apart from timing runs.
    [switch]$Stats,
    # A Flight Recorder recording (flight.jfr) of the run. It costs CPU time, so keep JFR runs apart from timing runs.
    [switch]$Jfr,
    [switch]$Force,
    [string]$Instance = "$env:APPDATA\PrismLauncher\instances\26.3\minecraft"
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root

# A second game on the GPU skews every number.
$others = @(Get-CimInstance Win32_Process -Filter "Name like 'java%.exe'" |
    Where-Object { $_.CommandLine -match 'net\.fabricmc\.loader|net\.minecraft\.client|knot' })
if ($others.Count -gt 0 -and -not $Force) {
    $others | ForEach-Object { Write-Host "Running: PID $($_.ProcessId) $($_.Name)" }
    throw 'Another Minecraft JVM is running. Close it, or pass -Force to benchmark anyway.'
}

if ($Full -or $Compat) { $Shaders = [switch]$true }
$runDir = if ($Compat) { $Compat } elseif ($Full) { 'run-full' } elseif ($Shaders) { 'run-shaders' } else { 'run' }
$task = if ($Compat) { 'runClientCompat' } elseif ($Full) { 'runClientFull' } elseif ($Shaders) { 'runClientShaders' } else { 'runClient' }
New-Item -ItemType Directory -Force -Path (Join-Path $runDir 'config') | Out-Null

# First run only: start from a real game's settings (read-only copy from the Prism instance).
foreach ($file in @('options.txt', 'config\sodium-options.json')) {
    $target = Join-Path $runDir $file
    $source = Join-Path $Instance $file
    if (-not (Test-Path -LiteralPath $target) -and (Test-Path -LiteralPath $source)) {
        Copy-Item -LiteralPath $source -Destination $target
        Write-Host "Seeded $target from $source"
    }
}

$scenarioPath = (Resolve-Path -LiteralPath $Scenario).Path
$out = Join-Path $root ("bench-runs\{0}-{1}" -f (Get-Date -Format 'yyyyMMdd-HHmmss'), $Label)
New-Item -ItemType Directory -Force -Path $out | Out-Null

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot' }
# GPU clock, temperature, power and throttle reasons once a second. GPU-bound numbers drift with power
# and thermal throttling: at the 350 W cap and 84 C one session ran 12% slower than the next.
# One nvidia-smi call per sample: its own -l loop buffers the file until exit, and killing it loses the data.
$smi = Get-Command nvidia-smi -ErrorAction SilentlyContinue
$telemetry = if ($smi) {
    Start-Job -ArgumentList $smi.Source, (Join-Path $out 'gpu.csv') -ScriptBlock {
        param($exe, $csv)
        while ($true) {
            & $exe '--query-gpu=timestamp,clocks.gr,clocks.mem,temperature.gpu,power.draw,utilization.gpu,clocks_throttle_reasons.active' `
                '--format=csv,noheader,nounits' | Out-File -Append -Encoding ascii -FilePath $csv
            Start-Sleep -Milliseconds 1000
        }
    }
}

$gradleArgs = @($task, "-Pbench=$scenarioPath", "-Pbench.out=$out", "-Pbench.label=$Label", '--console=plain')
if ($Compat) { $gradleArgs += "-Pcompat.dir=$Compat" }
if ($Stats) { $gradleArgs += '-Pbench.stats=true' }
if ($Jfr) { $gradleArgs += "-Pbench.jfr=$(Join-Path $out 'flight.jfr')" }
# A scenario's pack-off and pack-on write vitrail/pack.txt, and the game puts it back as it exits. A killed game can't,
# and the next shader run would start without a pack, so this puts it back too.
$packFile = Join-Path $root (Join-Path $runDir 'vitrail\pack.txt')
# Assigned directly: the output of an if statement would unroll the byte array.
$packBytes = $null
if (Test-Path -LiteralPath $packFile) { $packBytes = [IO.File]::ReadAllBytes($packFile) }
. (Join-Path $PSScriptRoot 'bench-fixture.ps1')
$shaderFixtureBefore = Get-BenchmarkShaderFixture (Join-Path $root $runDir)
$cullValidationBefore = Get-BenchmarkCullValidation (Join-Path $root $runDir)
$started = Get-Date
try {
    & .\gradlew.bat @gradleArgs
    $exitCode = $LASTEXITCODE
} finally {
    if ($telemetry) { Stop-Job $telemetry; Remove-Job $telemetry }
    if ($null -ne $packBytes) { [IO.File]::WriteAllBytes($packFile, $packBytes) }
    elseif (Test-Path -LiteralPath $packFile) { Remove-Item -LiteralPath $packFile }
}

$finished = Get-Date
foreach ($file in @('options.txt', 'config\sodium-options.json', 'config\vultrum.properties', 'logs\latest.log')) {
    $source = Join-Path $runDir $file
    if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination (Join-Path $out (Split-Path $file -Leaf)) }
}
$sha = (git rev-parse --short HEAD).Trim()
$dirty = [bool](git status --porcelain --untracked-files=no)
# The run dir's own jars (Vitrail in run-shaders, the full mod set in run-full), so two builds under one file name can be told apart.
$mods = [ordered]@{}
$modsDir = Join-Path $runDir 'mods'
if (Test-Path -LiteralPath $modsDir) {
    foreach ($jar in Get-ChildItem -LiteralPath $modsDir -Filter *.jar | Sort-Object Name) {
        $mods[$jar.Name] = (Get-FileHash -Algorithm SHA256 -LiteralPath $jar.FullName).Hash.Substring(0, 12).ToLower()
    }
}
$cullValidationAfter = Get-BenchmarkCullValidation (Join-Path $root $runDir)
$meta = [ordered]@{
    label = $Label; scenario = $Scenario; shaders = [bool]$Shaders; full = [bool]$Full; compat = $Compat; stats = [bool]$Stats; jfr = [bool]$Jfr; git = $sha; dirty = $dirty
    started = $started.ToString('o'); finished = $finished.ToString('o'); gradleExitCode = $exitCode; mods = $mods
    shaderFixture = [ordered]@{ version = 1; before = $shaderFixtureBefore; after = (Get-BenchmarkShaderFixture (Join-Path $root $runDir)) }
    cullValidation = [ordered]@{ before = $cullValidationBefore; after = $cullValidationAfter }
    diagnosticOnly = $null -ne $cullValidationBefore.fault -or $null -ne $cullValidationAfter.fault -or $cullValidationBefore.portable -or $cullValidationAfter.portable
} | ConvertTo-Json -Depth 10
# Windows PowerShell's -Encoding UTF8 writes a byte order mark, which breaks Python's json.load.
[IO.File]::WriteAllText((Join-Path $out 'meta.json'), $meta, (New-Object Text.UTF8Encoding $false))

if (-not (Test-Path -LiteralPath (Join-Path $out 'results.json'))) { throw "No results.json in $out (gradle exit $exitCode); see latest.log there." }
Write-Host "Results: $out"
