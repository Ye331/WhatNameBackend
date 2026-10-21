param([ValidateRange(1024, 65535)][int]$Port = 3307)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$secretDirectory = Join-Path $projectDirectory 'config/local-secrets'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
New-Item -ItemType Directory -Path $secretDirectory -Force | Out-Null

function New-LocalPassword {
    $bytes = New-Object byte[] 24
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}

foreach ($fileName in @('mysql_root_password.txt', 'mysql_app_password.txt')) {
    $secretPath = Join-Path $secretDirectory $fileName
    if (-not (Test-Path -LiteralPath $secretPath)) {
        [System.IO.File]::WriteAllText($secretPath, (New-LocalPassword), $utf8NoBom)
    }
}

$envPath = Join-Path $projectDirectory '.env'
if (-not (Test-Path -LiteralPath $envPath)) {
    [System.IO.File]::WriteAllText($envPath, "MYSQL_PORT=$Port`n", $utf8NoBom)
} else {
    $portLine = Get-Content -LiteralPath $envPath | Where-Object { $_ -match '^MYSQL_PORT=\d+$' } | Select-Object -First 1
    if (-not $portLine) { throw 'Existing .env must contain MYSQL_PORT. Nothing was overwritten.' }
    $Port = [int]($portLine.Split('=')[1])
}

$localConfiguration = Join-Path $projectDirectory 'config/application-local.yml'
if (-not (Test-Path -LiteralPath $localConfiguration)) {
    $appPassword = [System.IO.File]::ReadAllText((Join-Path $secretDirectory 'mysql_app_password.txt')).Trim()
    $content = @"
# Personal local configuration. Do not commit.
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:${Port}/unified_permission?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=Asia/Taipei&forceConnectionTimeZoneToSession=true
    username: iam_dev
    password: "$appPassword"
"@
    [System.IO.File]::WriteAllText($localConfiguration, "$content`n", $utf8NoBom)
} else {
    Write-Host 'Existing application-local.yml kept. Check its connection settings if necessary.'
}

Write-Host "Local files ready. MySQL connection: 127.0.0.1:$Port / unified_permission / iam_dev"
Write-Host 'Password is in config/application-local.yml. It is not printed here.'
Write-Host 'Next: docker compose up -d --wait'
