param(
    [ValidateSet("legacy", "current", "all")]
    [string]$Profile = "all",
    [switch]$KeepUp
)

$ErrorActionPreference = "Stop"

$script:UserAgent = "AutoStopper/1.1.2 (https://github.com/Criseda/AutoStopper)"
$script:FullApi = "https://fill.papermc.io/v3/projects/velocity"
$script:SuccessMarker = "AutoStopper plugin initialized!"

$Profiles = @{
    legacy = @{
        Version     = "3.5.1"
        Build       = 615
        Sha256      = "b4e3164df5377346854dc6cb9e6a78022b1946ff69e89676313f5f6f1c6f0fb3"
        JavaLabel   = "Java 21"
        VelocityJar = "velocity-3.5.1-615.jar"
    }
    current = @{
        Version     = "4.1.0-SNAPSHOT"
        Build       = 16
        Sha256      = "aebade8be3b15d7c3c61514a50ce857cbf78ee87bd32e8d16d2352c6ca3e472f"
        JavaLabel   = "Java 25"
        VelocityJar = "velocity-4.1.0-SNAPSHOT-16.jar"
    }
}

function Invoke-NativeChecked {
    param([string]$FilePath, [string[]]$ArgumentList)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $FilePath @ArgumentList 2>&1 | Out-Host
        if ($LASTEXITCODE -ne 0) {
            throw "'$FilePath $($ArgumentList -join ' ')' failed with exit code $LASTEXITCODE"
        }
    }
    finally { $ErrorActionPreference = $prev }
}

function Get-NativeOutput {
    param([string]$FilePath, [string[]]$ArgumentList)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $lines = @(& $FilePath @ArgumentList 2>&1 | ForEach-Object { "$_" })
        return ($lines -join [Environment]::NewLine)
    }
    finally { $ErrorActionPreference = $prev }
}

function Get-PluginJarPath {
    $repo = Split-Path -Parent $PSScriptRoot
    $jar = Get-ChildItem (Join-Path $repo "target\AutoStopper-*.jar") -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "original-*" } |
        Select-Object -First 1
    if (-not $jar) {
        throw "Shaded plugin JAR not found in $repo\target. Run 'mvn clean package' first."
    }
    return $jar.FullName
}

function Write-SmokeConfig([string]$pluginsDirectory) {
    # The plugins directory is persisted between smoke runs. Always replace
    # older generated/sample mappings so the bare proxy starts from a known,
    # valid configuration with no registered backend servers.
    $dataDirectory = Join-Path $pluginsDirectory "autostopper"
    New-Item -ItemType Directory -Path $dataDirectory -Force | Out-Null
    $config = @(
        "inactivity_timeout_seconds: 300"
        "monitored_servers: []"
        ""
    ) -join "`n"
    [IO.File]::WriteAllText(
        (Join-Path $dataDirectory "config.yml"),
        $config,
        [Text.UTF8Encoding]::new($false))
}

function Get-VelocityJar($profile, $dir) {
    $runtime = Join-Path $dir "runtime"
    New-Item -ItemType Directory -Path $runtime -Force | Out-Null
    $jarPath = Join-Path $runtime $profile.VelocityJar
    if (Test-Path -LiteralPath $jarPath) {
        $hash = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($hash -ne $profile.Sha256) {
            throw "Cached Velocity JAR $jarPath does not match pinned SHA256. Delete it and re-run."
        }
        return $jarPath
    }

    $builds = Invoke-RestMethod -Headers @{ "User-Agent" = $script:UserAgent } `
        -Uri "$script:FullApi/versions/$($profile.Version)/builds"
    $build = $builds | Where-Object { $_.id -eq $profile.Build } | Select-Object -First 1
    if (-not $build) {
        throw "Build $($profile.Build) not found for Velocity $($profile.Version) on fill.papermc.io."
    }
    $url = $build.downloads."server:default".url

    Write-Host "Downloading $($profile.VelocityJar)..."
    Invoke-WebRequest -Headers @{ "User-Agent" = $script:UserAgent } -Uri $url -OutFile $jarPath
    $hash = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hash -ne $profile.Sha256) {
        Remove-Item -LiteralPath $jarPath -Force
        throw "Downloaded $($profile.VelocityJar) SHA256 mismatch (got $hash)"
    }
    return $jarPath
}

function Invoke-SmokeProfile($name, $profile) {
    $dir = Join-Path $PSScriptRoot $name
    $composeFile = Join-Path $dir "docker-compose.yml"
    if (-not (Test-Path -LiteralPath $composeFile)) {
        throw "No compose stack at $composeFile"
    }

    $plugins = Join-Path $dir "plugins"
    New-Item -ItemType Directory -Path $plugins -Force | Out-Null
    Copy-Item -LiteralPath (Get-PluginJarPath) -Destination (Join-Path $plugins "AutoStopper.jar") -Force
    Write-SmokeConfig $plugins
    Get-VelocityJar $profile $dir | Out-Null

    Write-Host ""
    Write-Host "==> Smoke test [$name] - $($profile.JavaLabel) + Velocity $($profile.Version) (build $($profile.Build))"
    Invoke-NativeChecked -FilePath "docker" -ArgumentList @("compose", "-f", $composeFile, "up", "-d")

    try {
        $deadline = (Get-Date).AddSeconds(180)
        $passed = $false
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 3
            $state = Get-NativeOutput -FilePath "docker" -ArgumentList @("inspect", "-f", "{{.State.Status}}", "autostopper-smoke-$name")
            if ($state -notmatch "running") {
                Write-Host "Container exited early (state: $state):"
                Invoke-NativeChecked -FilePath "docker" -ArgumentList @("compose", "-f", $composeFile, "logs", "--no-color")
                throw "Velocity proxy exited before the plugin initialized for [$name]"
            }
            $logs = Get-NativeOutput -FilePath "docker" -ArgumentList @("compose", "-f", $composeFile, "logs", "--no-color")
            if ($logs -match [regex]::Escape($script:SuccessMarker)) {
                $passed = $true
                break
            }
        }

        if (-not $passed) {
            Write-Host "Timed out waiting for '$script:SuccessMarker'. Last log output:"
            Invoke-NativeChecked -FilePath "docker" -ArgumentList @("compose", "-f", $composeFile, "logs", "--no-color")
            throw "Smoke test [$name] FAILED - plugin did not initialize"
        }
        Write-Host "PASS: AutoStopper initialized on $($profile.JavaLabel) with Velocity $($profile.Version) (build $($profile.Build))"
    }
    finally {
        if (-not $KeepUp) {
            Invoke-NativeChecked -FilePath "docker" -ArgumentList @("compose", "-f", $composeFile, "down", "--remove-orphans")
        }
        else {
            Write-Host "Container left running (-KeepUp). Inspect with: docker compose -f $composeFile logs"
        }
    }
}

$targets = @(if ($Profile -eq "all") { $Profiles.Keys } else { $Profile })
$failed = @()
foreach ($name in $targets) {
    try {
        Invoke-SmokeProfile $name $Profiles[$name]
    }
    catch {
        Write-Host ""
        Write-Host "ERROR: $_" -ForegroundColor Red
        $failed += $name
    }
}

if ($failed.Count -gt 0) {
    Write-Host ""
    Write-Host "FAILED profiles: $($failed -join ', ')" -ForegroundColor Red
    exit 1
}
Write-Host ""
Write-Host "All requested smoke tests passed." -ForegroundColor Green
