# Shared report validation, also callable for diagnosing a failed run. No Maven/DB access.
function Read-VerifiedTestReports {
    param([string]$ReportDirectory, [string]$Suffix, [System.Collections.IDictionary]$Expected,
          [datetime]$StartedUtc, [datetime]$FinishedUtc, [string]$Destination, [string]$Secret)
    $cases = @{}
    $rows = @()
    $problems = @()
    $actualFiles = @(Get-ChildItem -LiteralPath $ReportDirectory -Filter "TEST-*-$Suffix.xml" -ErrorAction SilentlyContinue)
    if ($actualFiles.Count -ne $Expected.Count) { $problems += 'Unexpected/missing report file count for this invocation' }
    foreach ($class in $Expected.Keys) {
        $path = Join-Path $ReportDirectory "TEST-$class-$Suffix.xml"
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            $problems += "Missing report: $class"
            continue
        }
        $file = Get-Item -LiteralPath $path
        if ($file.LastWriteTimeUtc -lt $StartedUtc.AddSeconds(-2) -or $file.LastWriteTimeUtc -gt $FinishedUtc.AddSeconds(2)) {
            $problems += "Report outside invocation time window: $class"
        }
        # Do not publish machine properties or arbitrary application stdout in XML evidence.
        $safe = Protect-TestOutput ([IO.File]::ReadAllText($path)) $Secret
        $document = New-Object System.Xml.XmlDocument
        $document.XmlResolver = $null
        $document.LoadXml($safe)
        $suite = $document.DocumentElement
        foreach ($node in @($suite.SelectNodes('properties|system-out|system-err|testcase/system-out|testcase/system-err'))) {
            $null = $node.ParentNode.RemoveChild($node)
        }
        $classCases = @($suite.SelectNodes('testcase'))
        $failures = @($suite.SelectNodes('testcase/failure')).Count
        $errors = @($suite.SelectNodes('testcase/error')).Count
        $skipped = @($suite.SelectNodes('testcase/skipped')).Count
        foreach ($case in $classCases) {
            $caseClass = [string]$case.classname
            if ($caseClass.EndsWith("($Suffix)")) { $caseClass = $caseClass.Substring(0, $caseClass.Length - $Suffix.Length - 2) }
            if ($caseClass -ne $class) { $problems += "Unexpected testcase class: $class" }
            $key = "$caseClass#$($case.name)"
            if ($cases.ContainsKey($key)) { $problems += "Duplicate testcase: $class" }
            $cases[$key] = $true
        }
        $row = [ordered]@{ class=$class; tests=[int]$suite.tests; failures=[int]$suite.failures;
            errors=[int]$suite.errors; skipped=[int]$suite.skipped; uniqueCases=$classCases.Count;
            rawLastWriteUtc=$file.LastWriteTimeUtc.ToString('o'); reportSha256=$null }
        if ($row.tests -ne $Expected[$class] -or $classCases.Count -ne $row.tests -or $row.tests -eq 0) {
            $problems += "Unexpected/zero test count: $class (expected $($Expected[$class]), actual $($row.tests))"
        }
        if ($row.failures -ne $failures -or $row.errors -ne $errors -or $row.skipped -ne $skipped) {
            $problems += "Inconsistent XML counters: $class"
        }
        if ($failures -or $errors -or $skipped) { $problems += "Failure/error/skip: $class" }
        $target = Join-Path $Destination $file.Name
        $document.Save($target)
        $row.reportSha256 = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
        $rows += [pscustomobject]$row
    }
    [pscustomobject]@{ reports=$rows; uniqueCases=@($cases.Keys | Sort-Object); problems=$problems }
}

function Protect-TestOutput {
    param([string]$Text, [string]$Secret)
    if ($Secret) {
        $Text = $Text.Replace($Secret, '[REDACTED_DB_PASSWORD]')
        $Text = $Text.Replace([Security.SecurityElement]::Escape($Secret), '[REDACTED_DB_PASSWORD]')
    }
    $Text = [regex]::Replace($Text, 'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', '[REDACTED_JWT]')
    $Text = [regex]::Replace($Text, '(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----', '[REDACTED_PRIVATE_KEY]')
    return $Text
}
