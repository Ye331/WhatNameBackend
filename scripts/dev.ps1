param(
    [ValidateSet('setup','test','data','init','recover')][string]$Action = 'setup',
    [ValidateRange(1024,65535)][int]$Port = 3308,
    [switch]$FullImport,
    [switch]$Integration,
    [string]$Python = 'python',
    [string]$SuperUser = 'superadmin',
    [string]$SuperName = '系统管理员',
    [string]$SuperPhone = ''
)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$secretDirectory = Join-Path $projectDirectory 'config/local-secrets'
$encoding = New-Object System.Text.UTF8Encoding($false)
function New-DemoSecret {
    $bytes = New-Object byte[] 24
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}
function Use-Java21 {
    $candidates = @($env:JAVA_HOME)
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) {
        $candidates += Split-Path -Parent (Split-Path -Parent $javaCommand.Source)
    }
    $ideaJdks = Join-Path $env:USERPROFILE '.jdks'
    if (Test-Path -LiteralPath $ideaJdks) {
        $candidates += Get-ChildItem -LiteralPath $ideaJdks -Directory |
            Sort-Object LastWriteTime -Descending | ForEach-Object FullName
    }
    foreach ($candidate in ($candidates | Where-Object { $_ } | Select-Object -Unique)) {
        $release = Join-Path $candidate 'release'
        $executable = Join-Path $candidate 'bin/java.exe'
        if ((Test-Path -LiteralPath $release) -and (Test-Path -LiteralPath $executable) -and
            (Select-String -LiteralPath $release -Pattern '^JAVA_VERSION="21(?:\.|["+])' -Quiet)) {
            $env:JAVA_HOME = $candidate
            $env:Path = (Join-Path $candidate 'bin') + ';' + $env:Path
            Write-Host "已选择 Java 21：$candidate"
            return
        }
    }
    throw '未找到 Java 21。请将 JAVA_HOME 设置为 JDK 21 的目录（不要包含 bin）。'
}
Push-Location $projectDirectory
try {
    if ($Action -in @('init','recover','test')) { Use-Java21 }
    if ($Action -eq 'setup') {
        New-Item -ItemType Directory -Path $secretDirectory -Force | Out-Null
        foreach ($file in @('mysql_root_password.txt','mysql_app_password.txt')) {
            $path = Join-Path $secretDirectory $file
            if (-not (Test-Path -LiteralPath $path)) { [IO.File]::WriteAllText($path, (New-DemoSecret), $encoding) }
        }
        $configuration = Join-Path $projectDirectory 'config/application-coursework.yml'
        if (-not (Test-Path -LiteralPath $configuration)) {
            $template = [IO.File]::ReadAllText((Join-Path $projectDirectory 'config/application-coursework.example.yml'))
            $mysqlPassword = [IO.File]::ReadAllText((Join-Path $secretDirectory 'mysql_app_password.txt')).Trim()
            $template = $template.Replace('__MYSQL_APP_PASSWORD__', $mysqlPassword).Replace(':3308/', ":${Port}/")
            foreach ($placeholder in @('__HR_KEY__','__OA_KEY__','__OA_APP_KEY__','__HR_APP_KEY__')) { $template = $template.Replace($placeholder, (New-DemoSecret)) }
            [IO.File]::WriteAllText($configuration, $template, $encoding)
        }
        $environmentPath = Join-Path $projectDirectory '.env'
        $lines = if (Test-Path -LiteralPath $environmentPath) { [IO.File]::ReadAllLines($environmentPath) } else { @() }
        if (-not ($lines | Where-Object { $_ -match '^COURSEWORK_MYSQL_PORT=' })) {
            [IO.File]::AppendAllText($environmentPath, "`nCOURSEWORK_MYSQL_PORT=$Port`n", $encoding)
        }
        Write-Host '新版配置已就绪；旧配置和旧数据库保持原状。'
        Write-Host '下一步：docker compose up -d --wait；再执行 init。'
        exit 0
    }
    if ($Action -eq 'data' -or $Action -eq 'init' -or ($Action -eq 'test' -and $FullImport)) {
        & $Python (Join-Path $PSScriptRoot 'prepare-data.py')
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        if ($Action -eq 'data') { exit 0 }
    }
    if ($Action -eq 'test') {
        & $Python -m unittest discover -s scripts -p 'test_*.py' -v
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        $profile = if ($Integration) { '-Pcoverage,integration-tests' } else { '-Pcoverage' }
        $arguments = @($profile,'verify')
        if ($FullImport) { $arguments += '-Dcoursework.fullImport=true' }
        & (Join-Path $projectDirectory 'mvnw.cmd') @arguments
        exit $LASTEXITCODE
    }
    if ($Action -eq 'init' -and -not $SuperPhone) { throw '请用 -SuperPhone 指定初始化管理账号手机号' }
    if (-not $env:IAM_INIT_SUPER_PASSWORD) {
        $secure = Read-Host '初始化或恢复密码（8–64位，包含字母和数字）' -AsSecureString
        $env:IAM_INIT_SUPER_PASSWORD = [System.Net.NetworkCredential]::new('', $secure).Password
    }
    try {
        $mode = if ($Action -eq 'init') { 'import' } else { 'recover' }
        $parameters = "--iam.init.mode=$mode --iam.init.super-username=$SuperUser"
        if ($Action -eq 'init') { $parameters += " --iam.init.data=target/prepared-data.json --iam.init.super-name=$SuperName --iam.init.super-phone=$SuperPhone" }
        & (Join-Path $projectDirectory 'mvnw.cmd') spring-boot:run "-Dspring-boot.run.arguments=$parameters"
        $result = $LASTEXITCODE
    } finally { Remove-Item Env:IAM_INIT_SUPER_PASSWORD -ErrorAction SilentlyContinue }
    exit $result
} finally { Pop-Location }
