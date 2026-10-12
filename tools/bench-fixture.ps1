# Snapshot shader inputs outside the measured game interval. Hash all packs,
# including directory packs, so partial pack-name selection cannot hide a change.
function Get-BenchmarkShaderFixture([string]$RunDirectory) {
    $vitrail = [ordered]@{}
    foreach ($name in @('pack.txt', 'options.txt', 'amortise-shadow', 'graphics-api.txt', 'box-shadow-cull', 'soft-shadow-compare')) {
        $file = Join-Path $RunDirectory "vitrail\$name"
        $vitrail[$name] = if (Test-Path -LiteralPath $file -PathType Leaf) {
            (Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLower()
        } else { $null }
    }
    $directory = Join-Path $RunDirectory 'vitrail'
    if (Test-Path -LiteralPath $directory) {
        foreach ($file in Get-ChildItem -LiteralPath $directory -File | Sort-Object Name) {
            $vitrail[$file.Name] = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLower()
        }
    }
    $packs = [ordered]@{}
    $directory = Join-Path $RunDirectory 'shaderpacks'
    if (Test-Path -LiteralPath $directory) {
        $prefix = (Resolve-Path -LiteralPath $directory).Path.TrimEnd('\') + '\'
        foreach ($file in Get-ChildItem -LiteralPath $directory -File -Recurse | Sort-Object FullName) {
            $packs[$file.FullName.Substring($prefix.Length).Replace('\', '/')] =
                (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLower()
        }
    }
    $iris = Join-Path $RunDirectory 'config\iris.properties'
    $irisHash = if (Test-Path -LiteralPath $iris -PathType Leaf) {
        (Get-FileHash -Algorithm SHA256 -LiteralPath $iris).Hash.ToLower()
    } else { $null }
    return [ordered]@{ vitrail = $vitrail; shaderpacks = $packs; irisProperties = $irisHash }
}
