$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$buildScript = Join-Path $PSScriptRoot 'Build-HarmonyPackage.ps1'
$evidenceSchema = Join-Path $projectRoot 'evidence.schema.json'
$powerShell = (Get-Process -Id $PID).Path
$testEnvironmentNames = @(
    'AURA_TEST_HNP_ARGS',
    'AURA_TEST_HNP_FILES',
    'AURA_TEST_HNP_JSON',
    'AURA_TEST_HVIGOR_ARGS',
    'AURA_TEST_HVIGOR_PROJECT',
    'AURA_TEST_SIGN_ARGS',
    'AURA_TEST_VERIFY_ARGS',
    'AURA_TEST_INJECT_HVIGOR_FAILURE'
)

function Assert-Condition([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw $Message
    }
}

function Write-Utf8NoBom([string]$Path, [string]$Content) {
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $utf8)
}

function Remove-ValidatedTemporaryDirectory([string]$Path) {
    $resolved = [System.IO.Path]::GetFullPath($Path)
    $temporaryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    Assert-Condition ($resolved.StartsWith($temporaryRoot, [System.StringComparison]::OrdinalIgnoreCase)) `
        "Refusing to remove non-temporary path: $resolved"
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

function New-TestJar([string]$Path, [string]$ImplementationVersion) {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::CreateNew)
    try {
        $archive = New-Object System.IO.Compression.ZipArchive(
            $stream,
            [System.IO.Compression.ZipArchiveMode]::Create,
            $true
        )
        try {
            $entry = $archive.CreateEntry('META-INF/MANIFEST.MF')
            $entryStream = $entry.Open()
            try {
                $utf8 = New-Object System.Text.UTF8Encoding($false)
                $writer = New-Object System.IO.StreamWriter($entryStream, $utf8, 1024, $true)
                try {
                    $writer.Write("Manifest-Version: 1.0`r`nImplementation-Version: $ImplementationVersion`r`n`r`n")
                } finally {
                    $writer.Dispose()
                }
            } finally {
                $entryStream.Dispose()
            }
        } finally {
            $archive.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

function New-FakeTools([string]$Directory) {
    [void](New-Item -ItemType Directory -Path $Directory)
    $hnpCli = Join-Path $Directory 'fake-hnpcli.ps1'
    $hvigor = Join-Path $Directory 'fake-hvigor.ps1'
    $signer = Join-Path $Directory 'fake-signer.ps1'

    Write-Utf8NoBom $hnpCli @'
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$ToolArguments)
$ErrorActionPreference = 'Stop'
if ($ToolArguments.Count -eq 1 -and $ToolArguments[0] -ceq '--version') {
    Write-Output 'fake-hnpcli 1.0.0'
    exit 0
}
[System.IO.File]::WriteAllText(
    $env:AURA_TEST_HNP_ARGS,
    ($ToolArguments | ConvertTo-Json -Compress),
    (New-Object System.Text.UTF8Encoding($false))
)
$inputIndex = [Array]::IndexOf($ToolArguments, '-i')
$outputIndex = [Array]::IndexOf($ToolArguments, '-o')
if ($inputIndex -lt 0 -or $outputIndex -lt 0) { throw 'fake hnpcli received incomplete arguments' }
$inputRoot = [System.IO.Path]::GetFullPath($ToolArguments[$inputIndex + 1])
$prefix = $inputRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
$files = @(Get-ChildItem -LiteralPath $inputRoot -Recurse -File | ForEach-Object {
    $_.FullName.Substring($prefix.Length).Replace('\', '/')
} | Sort-Object)
[System.IO.File]::WriteAllText(
    $env:AURA_TEST_HNP_FILES,
    ($files | ConvertTo-Json -Compress),
    (New-Object System.Text.UTF8Encoding($false))
)
Copy-Item -LiteralPath (Join-Path $inputRoot 'hnp.json') -Destination $env:AURA_TEST_HNP_JSON
$outputRoot = [System.IO.Path]::GetFullPath($ToolArguments[$outputIndex + 1])
[void](New-Item -ItemType Directory -Path $outputRoot -Force)
[System.IO.File]::WriteAllBytes((Join-Path $outputRoot 'aura_launcher.hnp'), [byte[]](1, 2, 3, 4))
'@

    Write-Utf8NoBom $hvigor @'
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$ToolArguments)
$ErrorActionPreference = 'Stop'
if ($ToolArguments.Count -eq 1 -and $ToolArguments[0] -ceq '--version') {
    Write-Output 'fake-hvigor 5.0.0'
    exit 0
}
[System.IO.File]::WriteAllText(
    $env:AURA_TEST_HVIGOR_ARGS,
    ($ToolArguments | ConvertTo-Json -Compress),
    (New-Object System.Text.UTF8Encoding($false))
)
$app = Get-Content -LiteralPath (Join-Path $PWD.Path 'AppScope/app.json5') -Raw | ConvertFrom-Json
$module = Get-Content -LiteralPath (Join-Path $PWD.Path 'entry/src/main/module.json5') -Raw | ConvertFrom-Json
$project = [ordered]@{
    versionCode = [int]$app.app.versionCode
    versionName = [string]$app.app.versionName
    hnpPackage = [string]$module.module.hnpPackages[0].package
    hnpType = [string]$module.module.hnpPackages[0].type
    hnpExists = Test-Path -LiteralPath (Join-Path $PWD.Path 'entry/src/main/hnp/arm64-v8a/aura_launcher.hnp')
}
[System.IO.File]::WriteAllText(
    $env:AURA_TEST_HVIGOR_PROJECT,
    ($project | ConvertTo-Json -Compress),
    (New-Object System.Text.UTF8Encoding($false))
)
$hap = Join-Path $PWD.Path 'entry/build/default/outputs/default/entry-default-unsigned.hap'
[void](New-Item -ItemType Directory -Path (Split-Path -Parent $hap) -Force)
[System.IO.File]::WriteAllBytes($hap, [byte[]](5, 6, 7, 8, 9))
if ($env:AURA_TEST_INJECT_HVIGOR_FAILURE -ceq '1') { exit 42 }
'@

    Write-Utf8NoBom $signer @'
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$ToolArguments)
$ErrorActionPreference = 'Stop'
if ($ToolArguments.Count -eq 1 -and $ToolArguments[0] -ceq '--version') {
    Write-Output 'fake-signer 1.0.0'
    exit 0
}
if ($ToolArguments[0] -ceq 'sign') {
    [System.IO.File]::WriteAllText(
        $env:AURA_TEST_SIGN_ARGS,
        ($ToolArguments | ConvertTo-Json -Compress),
        (New-Object System.Text.UTF8Encoding($false))
    )
    $inputIndex = [Array]::IndexOf($ToolArguments, '--input')
    $outputIndex = [Array]::IndexOf($ToolArguments, '--output')
    Copy-Item -LiteralPath $ToolArguments[$inputIndex + 1] -Destination $ToolArguments[$outputIndex + 1]
    [System.IO.File]::WriteAllBytes($ToolArguments[$outputIndex + 1], [byte[]](5, 6, 7, 8, 9, 10))
    exit 0
}
if ($ToolArguments[0] -ceq 'verify') {
    [System.IO.File]::WriteAllText(
        $env:AURA_TEST_VERIFY_ARGS,
        ($ToolArguments | ConvertTo-Json -Compress),
        (New-Object System.Text.UTF8Encoding($false))
    )
    $inputIndex = [Array]::IndexOf($ToolArguments, '--input')
    if (-not (Test-Path -LiteralPath $ToolArguments[$inputIndex + 1] -PathType Leaf)) { exit 43 }
    exit 0
}
exit 44
'@

    return [pscustomobject]@{ HnpCli = $hnpCli; Hvigor = $hvigor; Signer = $signer }
}

function New-BuildCase(
    [string]$Name,
    [string]$JarName = 'Aura-Launcher-27.1-next.jar',
    [string]$ImplementationVersion = '27.1-next'
) {
    $root = Join-Path $temporary $Name
    $input = Join-Path $root 'input'
    $output = Join-Path $root 'output'
    $records = Join-Path $root 'records'
    [void](New-Item -ItemType Directory -Path $root, $input, $output, $records)
    $jar = Join-Path $input $JarName
    New-TestJar $jar $ImplementationVersion
    $tools = New-FakeTools (Join-Path $root 'tools')
    return [pscustomobject]@{
        Name = $Name
        Root = $root
        Input = $input
        Output = $output
        Records = $records
        Jar = $jar
        HnpCli = $tools.HnpCli
        Hvigor = $tools.Hvigor
        Signer = $tools.Signer
    }
}

function Set-BuildCaseEnvironment([object]$Case) {
    $env:AURA_TEST_HNP_ARGS = Join-Path $Case.Records 'hnp-args.json'
    $env:AURA_TEST_HNP_FILES = Join-Path $Case.Records 'hnp-files.json'
    $env:AURA_TEST_HNP_JSON = Join-Path $Case.Records 'hnp.json'
    $env:AURA_TEST_HVIGOR_ARGS = Join-Path $Case.Records 'hvigor-args.json'
    $env:AURA_TEST_HVIGOR_PROJECT = Join-Path $Case.Records 'hvigor-project.json'
    $env:AURA_TEST_SIGN_ARGS = Join-Path $Case.Records 'sign-args.json'
    $env:AURA_TEST_VERIFY_ARGS = Join-Path $Case.Records 'verify-args.json'
}

function Invoke-Build(
    [object]$Case,
    [int]$VersionCode,
    [string]$OutputDirectory = '',
    [string]$SigningProfile = '',
    [string]$SigningKind = 'release',
    [switch]$CompleteSigning
) {
    Set-BuildCaseEnvironment $Case
    if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
        $OutputDirectory = $Case.Output
    }
    $arguments = @(
        '-NoProfile', '-File', $buildScript,
        '-LauncherJar', $Case.Jar,
        '-HnpCli', $Case.HnpCli,
        '-Hvigor', $Case.Hvigor,
        '-OutputDirectory', $OutputDirectory,
        '-VersionCode', [string]$VersionCode,
        '-SigningKind', $SigningKind
    )
    if (-not [string]::IsNullOrWhiteSpace($SigningProfile)) {
        $arguments += @('-SigningProfile', $SigningProfile)
    }
    if ($CompleteSigning) {
        $profile = Join-Path $Case.Input 'release-profile.p7b'
        $certificate = Join-Path $Case.Input 'release-certificate.cer'
        [System.IO.File]::WriteAllBytes($profile, [byte[]](11, 12))
        [System.IO.File]::WriteAllBytes($certificate, [byte[]](13, 14))
        $arguments += @(
            '-Signer', $Case.Signer,
            '-SigningProfile', $profile,
            '-SigningCertificate', $certificate,
            '-SigningKeyAlias', 'aura-release',
            '-SigningPasswordReference', 'secret://harmony/aura'
        )
    }

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $records = @(& $powerShell @arguments 2>&1)
        $exitCode = $LASTEXITCODE
        $output = ($records | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
    } finally {
        $ErrorActionPreference = $previousPreference
        $global:LASTEXITCODE = 0
    }
    return [pscustomobject]@{ ExitCode = $exitCode; Output = $output }
}

function Assert-BuildFails(
    [string]$Name,
    [string]$Expected,
    [int]$VersionCode = 271001,
    [string]$JarName = 'Aura-Launcher-27.1-next.jar',
    [string]$ImplementationVersion = '27.1-next',
    [string]$SigningProfile = '',
    [string]$SigningKind = 'release',
    [string]$OutputDirectory = '',
    [switch]$InjectHvigorFailure
) {
    $case = New-BuildCase $Name $JarName $ImplementationVersion
    if ($InjectHvigorFailure) {
        $env:AURA_TEST_INJECT_HVIGOR_FAILURE = '1'
    }
    try {
        $result = Invoke-Build $case $VersionCode $OutputDirectory $SigningProfile $SigningKind
    } finally {
        Remove-Item Env:AURA_TEST_INJECT_HVIGOR_FAILURE -ErrorAction SilentlyContinue
    }
    Assert-Condition ($result.ExitCode -ne 0) "$Name should fail"
    Assert-Condition ($result.Output.Contains($Expected)) `
        "$Name failed without expected diagnostic '$Expected': $($result.Output)"
    $partial = @(Get-ChildItem -LiteralPath $case.Output -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '(?i)\.(hap|sha256)$' -or $_.Name -like '*-evidence.json' })
    Assert-Condition ($partial.Count -eq 0) "$Name left partial public outputs"
    Assert-Condition (-not (Test-Path -LiteralPath (Join-Path $case.Output '.staging'))) `
        "$Name left a staging directory"
    return $case
}

function Assert-RecordedArguments(
    [string]$Path,
    [string[]]$Expected,
    [string]$Tool
) {
    Assert-Condition (Test-Path -LiteralPath $Path -PathType Leaf) "$Tool argument record is missing"
    $actual = [object[]](Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json)
    Assert-Condition ($actual.Count -eq $Expected.Count) `
        "$Tool argument count mismatch: $($actual -join ', ')"
    for ($index = 0; $index -lt $Expected.Count; $index++) {
        Assert-Condition ([string]$actual[$index] -ceq $Expected[$index]) `
            "$Tool argument $index mismatch: expected '$($Expected[$index])', got '$($actual[$index])'"
    }
}

function Assert-EvidenceHasNoMatch([string]$Path, [string[]]$Forbidden) {
    $text = Get-Content -LiteralPath $Path -Raw
    foreach ($value in $Forbidden) {
        Assert-Condition ($text.IndexOf($value, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) `
            "Evidence contains forbidden value: $value"
    }
}

function Assert-NoPartialOutputAfterInjectedFailure() {
    [void](Assert-BuildFails -Name 'injected-hvigor-failure' -Expected 'Hvigor exited with code 42' `
        -InjectHvigorFailure)
}

function Assert-UnsignedBuild() {
    $case = New-BuildCase 'unsigned-success'
    $result = Invoke-Build $case 271001
    Assert-Condition ($result.ExitCode -eq 0) "Unsigned build failed: $($result.Output)"

    $artifactName = 'Aura-Launcher-27.1-next-harmonyos-arm64-unsigned.hap'
    $artifactPath = Join-Path $case.Output $artifactName
    $hashPath = "$artifactPath.sha256"
    $evidencePath = Join-Path $case.Output 'Aura-Launcher-27.1-next-harmonyos-arm64-evidence.json'
    Assert-Condition (Test-Path -LiteralPath $artifactPath -PathType Leaf) 'Unsigned HAP is missing'
    Assert-Condition (Test-Path -LiteralPath $hashPath -PathType Leaf) 'Unsigned HAP checksum is missing'
    Assert-Condition (Test-Path -LiteralPath $evidencePath -PathType Leaf) 'Evidence manifest is missing'

    $hnpArguments = [object[]](
        Get-Content -LiteralPath $env:AURA_TEST_HNP_ARGS -Raw | ConvertFrom-Json
    )
    $hnpInput = [string]$hnpArguments[2]
    $expectedHnpOutput = Join-Path (Split-Path -Parent $hnpInput) 'hnp-output'
    Assert-RecordedArguments $env:AURA_TEST_HNP_ARGS `
        @('pack', '-i', $hnpInput, '-o', $expectedHnpOutput) 'hnpcli'
    Assert-RecordedArguments $env:AURA_TEST_HVIGOR_ARGS `
        @(
            '--mode', 'module',
            '-p', 'module=entry@default',
            '-p', 'product=default',
            '-p', 'buildMode=release',
            'assembleHap'
        ) 'hvigor'

    $hnpFiles = [object[]](
        Get-Content -LiteralPath $env:AURA_TEST_HNP_FILES -Raw | ConvertFrom-Json
    )
    $expectedHnpFiles = @(
        'LICENSE',
        'NOTICE',
        'THIRD_PARTY_NOTICES.md',
        'bin/aura-launcher',
        'hnp.json',
        'share/aura/Aura-Launcher-27.1-next.jar'
    ) | Sort-Object
    Assert-Condition (@(Compare-Object $expectedHnpFiles @($hnpFiles | Sort-Object) -CaseSensitive).Count -eq 0) `
        "Unexpected HNP source matrix: $($hnpFiles -join ', ')"
    $hnp = Get-Content -LiteralPath $env:AURA_TEST_HNP_JSON -Raw | ConvertFrom-Json
    Assert-Condition ($hnp.name -ceq 'aura_launcher' -and $hnp.version -ceq '27.1.next') `
        'Generated hnp.json identity is wrong'

    $stagedProject = Get-Content -LiteralPath $env:AURA_TEST_HVIGOR_PROJECT -Raw | ConvertFrom-Json
    Assert-Condition ([int]$stagedProject.versionCode -eq 271001) 'Staged HAP versionCode is wrong'
    Assert-Condition ($stagedProject.versionName -ceq '27.1-next') 'Staged HAP versionName is wrong'
    Assert-Condition ($stagedProject.hnpPackage -ceq 'aura_launcher.hnp' `
            -and $stagedProject.hnpType -ceq 'private' -and [bool]$stagedProject.hnpExists) `
        'Staged private HNP declaration is wrong'

    $evidenceText = Get-Content -LiteralPath $evidencePath -Raw
    $evidence = $evidenceText | ConvertFrom-Json
    Assert-Condition ([int]$evidence.schemaVersion -eq 1) 'Evidence schemaVersion is wrong'
    Assert-Condition ($evidence.sourceCommit -cmatch '^[0-9a-f]{40}$') 'Evidence sourceCommit is invalid'
    Assert-Condition ($evidence.sdkVersion -ceq '6.0.1(21)') 'Evidence SDK version is wrong'
    Assert-Condition ($evidence.launcherVersion -ceq '27.1-next') 'Evidence launcher version is wrong'
    Assert-Condition ($evidence.hnpVersion -ceq '27.1.next') 'Evidence HNP version is wrong'
    Assert-Condition ([int]$evidence.versionCode -eq 271001) 'Evidence versionCode is wrong'
    Assert-Condition ($evidence.target -ceq 'harmonyos-arm64') 'Evidence target is wrong'
    Assert-Condition ($evidence.signingState -ceq 'unsigned') 'Evidence signing state is wrong'
    Assert-Condition (@($evidence.artifacts).Count -eq 1 `
            -and $evidence.artifacts[0].name -ceq $artifactName) 'Evidence artifact matrix is wrong'
    $actualHash = (Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-Condition ($evidence.artifacts[0].sha256 -ceq $actualHash) 'Evidence artifact hash is wrong'
    Assert-Condition ([int64]$evidence.artifacts[0].size -eq (Get-Item $artifactPath).Length) `
        'Evidence artifact size is wrong'
    Assert-Condition ((Get-Content -LiteralPath $hashPath -Raw).Trim() -ceq "$actualHash  $artifactName") `
        'SHA-256 sidecar is wrong'
    Assert-EvidenceHasNoMatch $evidencePath @(
        'password', 'privateKey', 'AURA_REPOSITORY_TOKEN', $temporary, $case.Signer
    )
    Assert-Condition (-not (Test-Path -LiteralPath (Join-Path $case.Output '.staging'))) `
        'Successful build left a staging directory'
}

function Assert-SignedBuild() {
    $case = New-BuildCase 'signed-success'
    $result = Invoke-Build $case 271001 -CompleteSigning
    Assert-Condition ($result.ExitCode -eq 0) "Signed build failed: $($result.Output)"
    $artifact = Join-Path $case.Output 'Aura-Launcher-27.1-next-harmonyos-arm64.hap'
    $evidencePath = Join-Path $case.Output 'Aura-Launcher-27.1-next-harmonyos-arm64-evidence.json'
    Assert-Condition (Test-Path -LiteralPath $artifact -PathType Leaf) 'Release-signed HAP is missing'
    Assert-Condition (-not (Test-Path -LiteralPath `
            (Join-Path $case.Output 'Aura-Launcher-27.1-next-harmonyos-arm64-unsigned.hap'))) `
        'Signed build must not publish an unsigned HAP'
    $evidence = Get-Content -LiteralPath $evidencePath -Raw | ConvertFrom-Json
    Assert-Condition ($evidence.signingState -ceq 'release-signed') 'Signed evidence state is wrong'
    $signArguments = [object[]](
        Get-Content -LiteralPath $env:AURA_TEST_SIGN_ARGS -Raw | ConvertFrom-Json
    )
    $verifyArguments = [object[]](
        Get-Content -LiteralPath $env:AURA_TEST_VERIFY_ARGS -Raw | ConvertFrom-Json
    )
    Assert-Condition ($signArguments[0] -ceq 'sign' -and $verifyArguments[0] -ceq 'verify') `
        'Signer must perform separate sign and verify operations'
    Assert-EvidenceHasNoMatch $evidencePath @(
        'secret://harmony/aura', 'aura-release', $case.Root, $case.Signer
    )
}

function Assert-DebugSignedBuild() {
    $case = New-BuildCase 'debug-signed-success'
    $result = Invoke-Build $case 271001 -SigningKind 'debug' -CompleteSigning
    Assert-Condition ($result.ExitCode -eq 0) "Debug-signed build failed: $($result.Output)"
    $artifact = Join-Path $case.Output `
        'Aura-Launcher-27.1-next-harmonyos-arm64-debug-signed.hap'
    $evidencePath = Join-Path $case.Output `
        'Aura-Launcher-27.1-next-harmonyos-arm64-evidence.json'
    Assert-Condition (Test-Path -LiteralPath $artifact -PathType Leaf) `
        'Debug-signed HAP is missing'
    Assert-Condition (-not (Test-Path -LiteralPath `
            (Join-Path $case.Output 'Aura-Launcher-27.1-next-harmonyos-arm64.hap'))) `
        'Debug signing must not publish a release filename'
    $evidence = Get-Content -LiteralPath $evidencePath -Raw | ConvertFrom-Json
    Assert-Condition ($evidence.signingState -ceq 'debug-signed') `
        'Debug-signed evidence state is wrong'
    Assert-EvidenceHasNoMatch $evidencePath @(
        'secret://harmony/aura', 'aura-release', $case.Root, $case.Signer
    )
}

Assert-Condition (Test-Path -LiteralPath $buildScript -PathType Leaf) `
    "HarmonyOS package builder is missing: $buildScript"
Assert-Condition (Test-Path -LiteralPath $evidenceSchema -PathType Leaf) `
    "HarmonyOS evidence schema is missing: $evidenceSchema"

$temporary = Join-Path ([System.IO.Path]::GetTempPath()) `
    ('aura-harmony-packaging-test-' + [guid]::NewGuid().ToString('N'))
[void](New-Item -ItemType Directory -Path $temporary)
try {
    [void](Assert-BuildFails -Name 'non-positive-version-code' `
        -VersionCode 0 -Expected 'versionCode must be a positive integer')
    [void](Assert-BuildFails -Name 'wrong-jar-name' `
        -JarName 'Aura-Launcher-27.1.jar' -Expected 'exactly Aura-Launcher-27.1-next.jar')
    [void](Assert-BuildFails -Name 'wrong-implementation-version' `
        -ImplementationVersion '27.1-next-next' -Expected 'Implementation-Version')
    [void](Assert-BuildFails -Name 'partial-signing-inputs' `
        -SigningProfile 'profile.p7b' -Expected 'signing inputs must be complete')
    [void](Assert-BuildFails -Name 'debug-without-signing-inputs' `
        -SigningKind 'debug' -Expected 'Debug signing requires complete signing inputs')

    $insideSource = Join-Path $projectRoot 'out/contract-test'
    $insideCase = New-BuildCase 'source-output-rejection'
    $insideResult = Invoke-Build $insideCase 271001 $insideSource
    Assert-Condition ($insideResult.ExitCode -ne 0 -and $insideResult.Output.Contains('outside the source template')) `
        "Source-tree output was not rejected: $($insideResult.Output)"
    Assert-Condition (-not (Test-Path -LiteralPath $insideSource)) 'Rejected source output path was created'

    Assert-UnsignedBuild
    Assert-SignedBuild
    Assert-DebugSignedBuild
    Assert-NoPartialOutputAfterInjectedFailure
    Write-Host 'Aura HarmonyOS packaging contract tests passed.'
} finally {
    foreach ($name in $testEnvironmentNames) {
        Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    }
    Remove-ValidatedTemporaryDirectory $temporary
}
