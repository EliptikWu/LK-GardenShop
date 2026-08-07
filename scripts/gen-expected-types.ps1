# Regenerates gardenshop-core/src/test/resources/expected-drop-types.txt from the
# Mythic pack. MythicTypeComposerTest compares its derived matrix against this
# file, so run this after adding a crop or changing a mutation suffix in the pack
# — then fix crops.yml until the test passes again.

param(
    [string]$Source = "c:\LK-GardenShop\growGardenItems.yml",
    [string]$Target = "c:\LK-GardenShop\gardenshop-core\src\test\resources\expected-drop-types.txt"
)

$names = Get-Content -LiteralPath $Source -Encoding UTF8 |
    Where-Object { $_ -match '^(growGarden[A-Za-z]*Drop[A-Za-z]*):\s*$' } |
    ForEach-Object { $Matches[1] }

$duplicates = $names | Group-Object | Where-Object { $_.Count -gt 1 }
if ($duplicates) {
    Write-Warning "Duplicate type names in the pack: $($duplicates.Name -join ', ')"
}

$header = @(
    "# Every harvest drop type declared in growGardenItems.yml, extracted verbatim.",
    "# MythicTypeComposerTest asserts the composer derives exactly this set: if the",
    "# pack gains a crop or renames a mutation suffix, that test fails loudly instead",
    "# of the plugin silently treating the new drops as unsellable.",
    "# Regenerate with scripts/gen-expected-types.ps1"
)

$dir = Split-Path -Parent $Target
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
[System.IO.File]::WriteAllLines($Target, ($header + $names), (New-Object System.Text.UTF8Encoding($false)))

Write-Output "Wrote $($names.Count) type names to $Target"
