#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateSet('Unit','AMySql','ACM5')][string[]]$Suite = @('Unit'),
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenSettings,
    [string]$OutputDirectory,
    [string]$LockPath,
    [ValidateRange(0,60)][int]$LockWaitSeconds = 0
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$workspace = Split-Path $repo -Parent
if (-not $LockPath) { $LockPath = Join-Path $workspace 'tmp/maven-build.lock' }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $workspace ('tmp/task13-' + [guid]::NewGuid().ToString('N')) }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$LockPath = [IO.Path]::GetFullPath($LockPath)
. (Join-Path $PSScriptRoot 'Test-Reports.ps1')
$catalog = @{
    Unit = [ordered]@{
        'com.whatname.iam.service.auth.PasswordHasherTest'=21
        'com.whatname.iam.service.auth.PasswordPolicyTest'=20
        'com.whatname.iam.common.security.JwtTokenServiceTest'=4
        'com.whatname.iam.common.security.SensitiveDataCodecTest'=3
        'com.whatname.iam.service.auth.NamePinyinTest'=2
    }
    AMySql = [ordered]@{
        'com.whatname.iam.service.auth.M1MySqlIntegrationTest'=37
        'com.whatname.iam.service.hr.HrBusinessIntegrationTest'=21
        'com.whatname.iam.service.department.DepartmentBusinessIntegrationTest'=20
        'com.whatname.iam.mapper.department.DepartmentMapperTest'=3
    }
    ACM5 = [ordered]@{ 'com.whatname.iam.service.auth.Task12M5MySqlIntegrationTest'=17 }
}
$environmentNames = @('JAVA_HOME','Path','TASK05_DB_TEST','TASK07_DB_TEST','TASK12_DB_TEST',
    'DEPARTMENT_DB_TEST','DEPARTMENT_DB_URL','DEPARTMENT_DB_USERNAME','DEPARTMENT_DB_PASSWORD')
$saved = @{}
foreach ($name in $environmentNames) { $saved[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
$lock = $null
$pushed = $false
$createdOutput = $false
$code = 1
$result = [ordered]@{ status='FAILED'; startedUtc=[datetime]::UtcNow.ToString('o'); finishedUtc=$null;
    head=$null; branch=$null; sourceManifest=@(); selectedSuites=$Suite; runs=@(); uniqueCases=@();
    tests=0; failures=0; errors=0; skipped=0; problems=@() }
try {
    if ($Suite.Count -eq 0) { throw 'At least one explicit suite is required' }
    if (@($Suite | Select-Object -Unique).Count -ne $Suite.Count) { throw 'Duplicate suites are not allowed' }
    if (Test-Path -LiteralPath $OutputDirectory) { throw 'Output directory already exists; choose a new immutable run directory' }
    # Prevent accidental evidence creation in tracked source/config directories.
    if ($OutputDirectory.StartsWith($repo + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Choose an output directory outside the repository'
    }
    $expectedSharedLock = Join-Path $workspace 'tmp/maven-build.lock'
    if ($repo -ieq 'D:\Desktop\OOAD\WhatNameBackend' -and $LockPath -ine [IO.Path]::GetFullPath($expectedSharedLock)) {
        throw 'This workspace must use its shared tmp/maven-build.lock'
    }
    if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'Supply -JavaHome pointing to JDK 21' }
    $release = Get-Content -LiteralPath (Join-Path $JavaHome 'release') -Raw
    if ($release -notmatch '(?m)^JAVA_VERSION="21(?:\.|"|-)') { throw 'JDK 21 is required' }
    if ($MavenSettings) { $MavenSettings = (Resolve-Path -LiteralPath $MavenSettings).Path }
    foreach ($suiteName in $Suite) {
        foreach ($class in $catalog[$suiteName].Keys) {
            $source = Join-Path $repo ('src/test/java/' + $class.Replace('.','/') + '.java')
            if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Expected test class is missing: $class" }
        }
    }
    $usesDatabase = ($Suite -contains 'AMySql') -or ($Suite -contains 'ACM5')
    if ($usesDatabase) {
        foreach ($name in @('A_TEST_DB_URL','A_TEST_DB_USERNAME','A_TEST_DB_PASSWORD')) {
            if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name,'Process'))) { throw "Missing private environment variable $name" }
        }
        # Never fall back to a discovered or remote database. Credentials belong in env only.
        if ($env:A_TEST_DB_URL -notmatch '^jdbc:mysql://(127\.0\.0\.1|localhost):[0-9]+/[A-Za-z0-9_]+(?:\?[^\r\n]*)?$' -or
            $env:A_TEST_DB_URL -match '(?i)(password|user|socketFactory|autoDeserialize|allowLoadLocalInfile)') {
            throw 'A_TEST_DB_URL must explicitly identify a loopback MySQL database without credentials/unsafe options'
        }
        $ports = if ($Suite -contains 'AMySql') { @(18085,18086,18087,18088) } else { @() }
        if ($Suite -contains 'ACM5') { $ports += @(18089,18090) }
        $listeners = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
        if (@($listeners | Where-Object { $_.Port -in $ports }).Count) { throw 'A required test HTTP port is occupied (18085-18090); no process will be stopped' }
    }
    $null = New-Item -ItemType Directory -Force -Path (Split-Path $LockPath -Parent)
    $deadline = [datetime]::UtcNow.AddSeconds($LockWaitSeconds)
    do {
        try { $lock = [IO.File]::Open($LockPath,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None) }
        catch [IO.IOException] {
            if ([datetime]::UtcNow -ge $deadline) { throw 'Shared Maven build lock is occupied; retry later' }
            Start-Sleep -Milliseconds 250
        }
    } until ($lock)
    # CreateDirectory would silently reuse a racing output directory. Use an atomic directory reservation.
    $parent = Split-Path $OutputDirectory -Parent
    $null = New-Item -ItemType Directory -Force -Path $parent
    $null = New-Item -ItemType Directory -Path $OutputDirectory -ErrorAction Stop
    $createdOutput = $true
    Push-Location -LiteralPath $repo
    $pushed = $true
    $env:JAVA_HOME = [IO.Path]::GetFullPath($JavaHome)
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    foreach ($name in @('TASK05_DB_TEST','TASK07_DB_TEST','TASK12_DB_TEST','DEPARTMENT_DB_TEST')) { [Environment]::SetEnvironmentVariable($name,'false','Process') }
    if ($usesDatabase) {
        $env:DEPARTMENT_DB_URL=$env:A_TEST_DB_URL
        $env:DEPARTMENT_DB_USERNAME=$env:A_TEST_DB_USERNAME
        $env:DEPARTMENT_DB_PASSWORD=$env:A_TEST_DB_PASSWORD
    }
    $result.head = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE) { throw 'Cannot resolve source HEAD' }
    $result.branch = (& git branch --show-current).Trim()
    # Hash only public source/build inputs, including this uncommitted entry point; never local config.
    $inputs = @(& git ls-files -- src pom.xml mvnw mvnw.cmd .mvn)
    $inputs += @(Get-ChildItem -LiteralPath $PSScriptRoot -File | ForEach-Object { 'scripts/test/' + $_.Name })
    foreach ($inputPath in @($inputs | Sort-Object -Unique)) {
        if (-not (Test-Path -LiteralPath (Join-Path $repo $inputPath) -PathType Leaf)) { continue } # Explicitly removed inputs are absent from this run.
        $hash = (Get-FileHash -LiteralPath (Join-Path $repo $inputPath) -Algorithm SHA256).Hash.ToLowerInvariant()
        $result.sourceManifest += [pscustomobject]@{path=$inputPath;sha256=$hash}
    }
    # git ls-files omits new production/test files until committed.
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $repo 'src') -Recurse -File) {
        $relative = $file.FullName.Substring($repo.Length+1).Replace('\','/')
        if ($relative -notin $result.sourceManifest.path) {
            $result.sourceManifest += [pscustomobject]@{path=$relative;sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
        }
    }
    foreach ($suiteName in $Suite) {
        foreach ($name in @('TASK05_DB_TEST','TASK07_DB_TEST','TASK12_DB_TEST','DEPARTMENT_DB_TEST')) { [Environment]::SetEnvironmentVariable($name,'false','Process') }
        if ($suiteName -eq 'AMySql') { $env:TASK05_DB_TEST='true'; $env:TASK07_DB_TEST='true'; $env:DEPARTMENT_DB_TEST='true' }
        if ($suiteName -eq 'ACM5') { $env:TASK12_DB_TEST='true' }
        $suffix = 'task13-' + [guid]::NewGuid().ToString('N')
        $expected = $catalog[$suiteName]
        $destination = Join-Path $OutputDirectory $suiteName
        $null = New-Item -ItemType Directory -Path $destination
        $log = Join-Path $destination 'maven.log'
        $arguments = @('-B','-ntp','-DskipTests=false','-Dmaven.test.skip=false',
            '-DfailIfNoTests=true','-Dsurefire.failIfNoSpecifiedTests=true',
            "-Dsurefire.reportNameSuffix=$suffix",('-Dtest=' + ($expected.Keys -join ',')),
            '-DargLine=-Xmx768m','test')
        if ($MavenSettings) { $arguments = @('-s',$MavenSettings) + $arguments }
        $start = [datetime]::UtcNow
        $insidePrivateKey = $false
        # Native stderr on Windows PowerShell is an ErrorRecord; stream/redact before persistence.
        $ErrorActionPreference = 'Continue'
        & .\mvnw.cmd @arguments 2>&1 | ForEach-Object {
            $line = Protect-TestOutput ([string]$_) $env:A_TEST_DB_PASSWORD
            if ([string]$_ -match '-----BEGIN [^-]*PRIVATE KEY-----') { $insidePrivateKey=$true }
            if ($insidePrivateKey) { $line='[REDACTED_PRIVATE_KEY]' }
            if ([string]$_ -match '-----END [^-]*PRIVATE KEY-----') { $insidePrivateKey=$false }
            Add-Content -LiteralPath $log -Value $line -Encoding UTF8 -ErrorAction Stop
        }
        $mavenExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        $finish = [datetime]::UtcNow
        $run = [pscustomobject]@{suite=$suiteName; selectedClasses=@($expected.Keys); mavenExitCode=$mavenExit;
            startedUtc=$start.ToString('o'); finishedUtc=$finish.ToString('o'); reportSuffix=$suffix;
            reports=@(); uniqueCases=@(); problems=@()}
        $result.runs += $run
        $checked = Read-VerifiedTestReports -ReportDirectory (Join-Path $repo 'target/surefire-reports') -Suffix $suffix -Expected $expected -StartedUtc $start -FinishedUtc $finish -Destination $destination -Secret $env:A_TEST_DB_PASSWORD
        $run.reports=$checked.reports; $run.uniqueCases=$checked.uniqueCases; $run.problems=$checked.problems
        $result.problems += @($checked.problems)
        if ($mavenExit -ne 0) { $result.problems += "Maven failed for $suiteName (exit $mavenExit)" }
        Write-Host "$suiteName : Maven exit $mavenExit; actual unique testcases $($checked.uniqueCases.Count); report issues $($checked.problems.Count)"
        if ($mavenExit -ne 0 -or $checked.problems.Count) { throw "Suite $suiteName failed; see immutable summary and redacted log" }
    }
    foreach ($inputRecord in $result.sourceManifest) {
        if ((Get-FileHash -LiteralPath (Join-Path $repo $inputRecord.path) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $inputRecord.sha256) {
            throw 'Source/build input changed during invocation; rerun on a stable working tree'
        }
    }
    $result.status='PASSED'
    $code=0
} catch {
    $result.problems += (Protect-TestOutput $_.Exception.Message $env:A_TEST_DB_PASSWORD)
    Write-Host ('Self-test failed: ' + $result.problems[-1])
} finally {
    $ErrorActionPreference='Stop'
    $result.finishedUtc=[datetime]::UtcNow.ToString('o')
    $result.uniqueCases = @($result.runs | ForEach-Object { $_.uniqueCases } | Sort-Object -Unique)
    foreach ($report in @($result.runs | ForEach-Object { $_.reports })) {
        $result.tests += $report.tests; $result.failures += $report.failures
        $result.errors += $report.errors; $result.skipped += $report.skipped
    }
    try {
        if ($createdOutput) {
            $result | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'summary.json') -Encoding UTF8
            Write-Host "Evidence: $OutputDirectory"
        }
    } finally {
        if ($pushed) { Pop-Location }
        foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
        if ($lock) { $lock.Dispose() }
    }
}
exit $code
