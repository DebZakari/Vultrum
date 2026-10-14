#requires -Version 7.0
<# Runs one correctness suite or a default/off/off/default static series in the pinned tester fixture. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet('Smoke', 'Robust', 'Static', 'Visual', 'Flight')] [string]$Suite,
    [Parameter(Mandatory)] [ValidatePattern('^[A-Za-z0-9._-]+$')] [string]$Label,
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root
$fixture = Join-Path $root 'run-compat-tester'
$setup = Join-Path $PSScriptRoot 'tester_setup.py'
& $Python $setup --verify
if ($LASTEXITCODE -ne 0) { throw 'Tester fixture verification failed' }
if (git status --porcelain --untracked-files=no) { throw 'Commit or discard tracked edits before collecting tester evidence' }
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    throw 'Set JAVA_HOME to a JDK 25 installation'
}
$javaVersion = (& (Join-Path $env:JAVA_HOME 'bin/java.exe') --version 2>&1) -join "`n"
if ($javaVersion -notmatch '(?m)^(?:openjdk|java) 25(?:[. ]|$)') { throw 'The tester profile requires JDK 25' }
foreach ($name in @('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'VK_INSTANCE_LAYERS')) {
    if ([Environment]::GetEnvironmentVariable($name)) { throw "Clear $name for the pinned tester profile" }
}
$profile = Get-Content -LiteralPath (Join-Path $root 'bench/tester-fixture.json') -Raw | ConvertFrom-Json
$original = @{}
foreach ($name in @($profile.files.PSObject.Properties.Name) + @('config/vultrum.properties')) {
    $original[$name] = [IO.File]::ReadAllBytes((Join-Path $fixture $name))
}
$logDir = Join-Path $root ("bench-runs/{0}-{1}-controller" -f (Get-Date -Format 'yyyyMMdd-HHmmss'), $Label)
New-Item -ItemType Directory -Path $logDir | Out-Null

function Set-Arm([string]$Arm) {
    & $Python $setup --settings $Arm
    if ($LASTEXITCODE -ne 0) { throw "Cannot select $Arm settings" }
}

function Run-Case([string]$Name, [string]$Scenario) {
    $runLabel = "$Label-$Name"
    if (@(Get-ChildItem -LiteralPath (Join-Path $root 'bench-runs') -Directory -Filter "*-$runLabel").Count) {
        throw "The label $runLabel already has evidence; use a new label"
    }
    # File redirection avoids retaining a Gradle daemon's inherited pipe.
    & (Join-Path $PSHOME 'pwsh.exe') -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'bench.ps1') `
        -Label $runLabel -Compat run-compat-tester -Scenario $Scenario *> (Join-Path $logDir "$Name.log")
    if ($LASTEXITCODE -ne 0) { throw "Run $runLabel failed; see $logDir/$Name.log" }
    $runs = @(Get-ChildItem -LiteralPath (Join-Path $root 'bench-runs') -Directory -Filter "*-$runLabel")
    if ($runs.Count -ne 1) { throw "Expected one evidence directory for $runLabel" }
    $results = Get-Content -LiteralPath (Join-Path $runs[0].FullName 'results.json') -Raw | ConvertFrom-Json
    $meta = Get-Content -LiteralPath (Join-Path $runs[0].FullName 'meta.json') -Raw | ConvertFrom-Json
    if ($results.status -ne 'complete' -or $meta.gradleExitCode -ne 0 -or $meta.dirty) {
        throw "Run $runLabel is incomplete or dirty; preserve its evidence and stop"
    }
    Write-Output "Completed: $($runs[0].FullName)"
    # The evidence has the settings the game recorded; put the launch files back before the next arm.
    foreach ($name in $original.Keys) {
        if ($name -ne 'config/vultrum.properties') {
            [IO.File]::WriteAllBytes((Join-Path $fixture $name), $original[$name])
        }
    }
}

try {
    Set-Arm default
    switch ($Suite) {
        Smoke { Run-Case smoke 'bench/scenarios/smoke.json' }
        Robust { Run-Case robust 'bench/scenarios/robust.json' }
        Visual { Run-Case visual 'bench/scenarios/visual.json' }
        Flight { Run-Case flight 'bench/scenarios/traversal.json' }
        Static {
            Run-Case warm 'bench/scenarios/tester-static.json'
            Run-Case on-1 'bench/scenarios/tester-static.json'
            Set-Arm off
            Run-Case off-1 'bench/scenarios/tester-static.json'
            Run-Case off-2 'bench/scenarios/tester-static.json'
            Set-Arm default
            Run-Case on-2 'bench/scenarios/tester-static.json'
        }
    }
} finally {
    foreach ($name in $original.Keys) {
        [IO.File]::WriteAllBytes((Join-Path $fixture $name), $original[$name])
    }
    Write-Output 'Original tester settings restored'
}
