<#
.SYNOPSIS
Renders the Modrinth page diagrams from docs/assets/modrinth/src into PNG files.

.DESCRIPTION
The SVG and HTML files in docs/assets/modrinth/src are the editable sources. Modrinth serves PNG
reliably, so the committed images in docs/assets/modrinth are rendered with headless Microsoft Edge
or Google Chrome. Re-run this script after editing a source and commit both.
#>
[CmdletBinding()]
param(
    [string]$Browser
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$source = Join-Path $root 'docs/assets/modrinth/src'
$output = Join-Path $root 'docs/assets/modrinth'

if (-not $Browser) {
    $candidates = @(
        "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
        "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe",
        "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
        "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe"
    )
    $Browser = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $Browser) {
    throw 'No Edge or Chrome installation found; pass -Browser <path>.'
}

# name, width, height of each rendered image.
$images = @(
    @{ Name = 'lifecycle'; Extension = 'svg'; Width = 1400; Height = 620 },
    @{ Name = 'topology'; Extension = 'svg'; Width = 1400; Height = 640 },
    @{ Name = 'in-game'; Extension = 'html'; Width = 1400; Height = 560 }
)

foreach ($image in $images) {
    $input = Join-Path $source "$($image.Name).$($image.Extension)"
    $target = Join-Path $output "$($image.Name).png"
    $uri = ([System.Uri](Resolve-Path $input).Path).AbsoluteUri
    & $Browser --headless --disable-gpu --hide-scrollbars --force-device-scale-factor=1 `
        "--window-size=$($image.Width),$($image.Height)" "--screenshot=$target" $uri | Out-Null
    if (-not (Test-Path $target)) {
        throw "Rendering $($image.Name) did not produce $target"
    }
    Write-Host "Rendered $target"
}
