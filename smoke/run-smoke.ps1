param(
    [ValidateSet("legacy", "stable", "preview", "all")]
    [string]$Profile = "all"
)

$ErrorActionPreference = "Stop"
$repository = Split-Path -Parent $PSScriptRoot
$wrapper = Join-Path $repository "mvnw.cmd"

Push-Location $repository
try {
    & $wrapper verify -Psystem-tests "-Dvelocity.system.profiles=$Profile"
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}
finally {
    Pop-Location
}
