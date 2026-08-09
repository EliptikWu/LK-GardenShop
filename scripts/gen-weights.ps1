# Generates weights.yml from the crop pack's Mythic items file.
#
# -Source defaults to growGardenItems.yml beside this repo's root, which is where the
# From Seed to Sky pack's items file lands. Another pack: pass -Source yourself.
#
# The pack encodes each drop's weight as a lore line:
#     - "§r&f&lWeight: &r1.<random.05to40>kg"
# MythicMobs does NOT zero-pad <random.AAtoBB>, so a roll of 5 renders "1.5kg"
# where the author clearly meant "1.05kg". We read the AA/BB pair as the
# 2-decimal fraction it was written as, which recovers the intended range.

param(
    [string]$Source = (Join-Path $PSScriptRoot "..\growGardenItems.yml"),
    [string]$Target = (Join-Path $PSScriptRoot "..\gardenshop-bukkit\src\main\resources\weights.yml")
)

$lines = Get-Content -LiteralPath $Source -Encoding UTF8
$entries = [System.Collections.Generic.List[object]]::new()
$currentKey = $null

foreach ($line in $lines) {
    if ($line -match '^(growGarden[A-Za-z]*Drop[A-Za-z]*):\s*$') {
        $currentKey = $Matches[1]
        continue
    }
    if ($null -ne $currentKey -and $line -match 'Weight:\s*&r(\d+)\.<random\.(\d+)to(\d+)>kg') {
        $whole = [int]$Matches[1]
        $fracMin = $Matches[2]
        $fracMax = $Matches[3]
        if ($fracMin.Length -ne 2 -or $fracMax.Length -ne 2) {
            Write-Warning "$currentKey has a non-2-digit fraction ($fracMin..$fracMax) - review manually"
        }
        $min = $whole + ([double]$fracMin / [Math]::Pow(10, $fracMin.Length))
        $max = $whole + ([double]$fracMax / [Math]::Pow(10, $fracMax.Length))
        $entries.Add([pscustomobject]@{ Key = $currentKey; Min = $min; Max = $max })
        $currentKey = $null
    }
}

$inv = [System.Globalization.CultureInfo]::InvariantCulture
$sb = [System.Text.StringBuilder]::new()
[void]$sb.AppendLine("# ============================================================================")
[void]$sb.AppendLine("#  weights.yml - authoritative weight range (kg) per Mythic drop type")
[void]$sb.AppendLine("# ============================================================================")
[void]$sb.AppendLine("#")
[void]$sb.AppendLine("#  GENERATED from growGardenItems.yml - regenerate with scripts/gen-weights.ps1")
[void]$sb.AppendLine("#  after adding crops to the pack. Safe to hand-edit afterwards.")
[void]$sb.AppendLine("#")
[void]$sb.AppendLine("#  The plugin rolls the weight from this range and stores it in the item's")
[void]$sb.AppendLine("#  PersistentDataContainer, then rewrites the lore with 2 decimals. The pack's")
[void]$sb.AppendLine("#  own <random.AAtoBB> lore is NOT trusted: MythicMobs does not zero-pad it, so")
[void]$sb.AppendLine("#  '1.<random.00to30>' can render as '1.9kg' when 1.09kg was meant. The ranges")
[void]$sb.AppendLine("#  below are the author's INTENDED values, recovered from those pairs.")
[void]$sb.AppendLine("#")
[void]$sb.AppendLine("#  Any drop type missing here falls back to its crop's base range in crops.yml.")
[void]$sb.AppendLine("# ============================================================================")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("weights:")

foreach ($e in $entries) {
    $minS = $e.Min.ToString("0.00", $inv)
    $maxS = $e.Max.ToString("0.00", $inv)
    [void]$sb.AppendLine(("  {0}: {{ min: {1}, max: {2} }}" -f $e.Key, $minS, $maxS))
}

$dir = Split-Path -Parent $Target
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }

# LF, not the CRLF that AppendLine produces on Windows.
#
# This file is committed, and .gitattributes stores it as LF -- so a CRLF working copy makes
# the jar built here differ, by exactly this one entry, from the jar anyone else builds from
# the same commit. That silently breaks the point of publishing a SHA-256.
$content = $sb.ToString() -replace "`r`n", "`n"
[System.IO.File]::WriteAllText($Target, $content, (New-Object System.Text.UTF8Encoding($false)))

Write-Output "Wrote $($entries.Count) weight ranges to $Target"
