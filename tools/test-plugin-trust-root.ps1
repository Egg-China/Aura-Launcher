$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $repositoryRoot 'gradlew.bat'
$temporary = Join-Path ([System.IO.Path]::GetTempPath()) `
    ('aura-plugin-root-test-' + [guid]::NewGuid().ToString('N'))
$gradleHome = Join-Path $temporary 'gradle-home'
$projectCache = Join-Path $temporary 'project-cache'
[void](New-Item -ItemType Directory -Path $gradleHome)
[void](New-Item -ItemType Directory -Path $projectCache)
$sharedWrapperDists = Join-Path ([System.Environment]::GetFolderPath('UserProfile')) '.gradle\wrapper\dists'
if (Test-Path -LiteralPath $sharedWrapperDists) {
    $isolatedWrapperDists = Join-Path $gradleHome 'wrapper\dists'
    [void](New-Item -ItemType Directory -Path $isolatedWrapperDists -Force)
    Copy-Item -Path (Join-Path $sharedWrapperDists '*') -Destination $isolatedWrapperDists -Recurse
}

function New-TestKey([byte]$Seed) {
    [byte[]]$prefix = @(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
    [byte[]]$encoded = New-Object byte[] 44
    [System.Array]::Copy($prefix, 0, $encoded, 0, $prefix.Length)
    for ($index = 0; $index -lt 32; $index++) {
        $encoded[$prefix.Length + $index] = [byte](($Seed + $index) % 256)
    }
    $digest = [System.Security.Cryptography.SHA256]::Create().ComputeHash($encoded)
    $hex = -join ($digest | ForEach-Object { $_.ToString('x2') })
    return [pscustomobject]@{
        Id = "ed25519:$hex"
        Declaration = [ordered]@{
            keyType = 'ed25519'
            scheme = 'ed25519'
            publicKey = [System.Convert]::ToBase64String($encoded)
        }
    }
}

function New-Role([string]$KeyId) {
    return [ordered]@{
        keyIds = @($KeyId)
        threshold = 1
    }
}

function New-RootJson(
    [System.Collections.IDictionary]$Keys,
    [System.Collections.IDictionary]$Roles,
    [string]$StatusUrl = '',
    [string]$Expires = '2036-08-29T00:00:00Z'
) {
    return [ordered]@{
        signed = [ordered]@{
            _type = 'root'
            schemaVersion = 1
            expires = $Expires
            statusUrl = $StatusUrl
            keys = $Keys
            roles = $Roles
        }
        signatures = @()
    } | ConvertTo-Json -Compress -Depth 16
}

function Invoke-RootBuild([string]$Name, [AllowNull()][string]$RootJson) {
    Write-Host "Testing $Name..."
    $previousRoot = $env:AURA_PLUGIN_ROOT_JSON
    $previousGradleHome = $env:GRADLE_USER_HOME
    $previousPreference = $ErrorActionPreference
    try {
        $env:AURA_PLUGIN_ROOT_JSON = $RootJson
        $env:GRADLE_USER_HOME = $gradleHome
        $ErrorActionPreference = 'Continue'
        $output = & $gradle :AuraLauncher:createPluginTrustRoot `
            --no-daemon --console plain --rerun-tasks `
            --project-cache-dir $projectCache 2>&1 | Out-String
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
        $env:AURA_PLUGIN_ROOT_JSON = $previousRoot
        $env:GRADLE_USER_HOME = $previousGradleHome
    }
    return [pscustomobject]@{ Name = $Name; ExitCode = $exitCode; Output = $output }
}

function Assert-Succeeds([object]$Result) {
    if ($Result.ExitCode -ne 0) {
        throw "$($Result.Name) should succeed, but failed: $($Result.Output)"
    }
}

function Assert-Fails([object]$Result, [string]$Expected) {
    if ($Result.ExitCode -eq 0) {
        throw "$($Result.Name) should fail"
    }
    if (-not $Result.Output.Contains($Expected)) {
        throw "$($Result.Name) failed without expected diagnostic '$Expected': $($Result.Output)"
    }
}

try {
    $key1 = New-TestKey 1
    $key2 = New-TestKey 33
    $key3 = New-TestKey 65
    $key4 = New-TestKey 97

    $officialKeys = [ordered]@{}
    $officialKeys[$key1.Id] = $key1.Declaration
    $officialRoles = [ordered]@{}
    $officialRoles['official-repository'] = New-Role $key1.Id
    $officialRoot = New-RootJson $officialKeys $officialRoles

    $completeKeys = [ordered]@{}
    foreach ($key in @($key1, $key2, $key3, $key4)) {
        $completeKeys[$key.Id] = $key.Declaration
    }
    $completeRoles = [ordered]@{}
    $completeRoles['official-repository'] = New-Role $key1.Id
    $completeRoles['repository-attestor'] = New-Role $key2.Id
    $completeRoles['artifact-attestor'] = New-Role $key3.Id
    $completeRoles['trust-status'] = New-Role $key4.Id
    $completeRoot = New-RootJson $completeKeys $completeRoles 'https://trust.aura.example/status.json'

    Assert-Succeeds (Invoke-RootBuild 'checked-in development root' $null)
    Assert-Succeeds (Invoke-RootBuild 'official-only root' $officialRoot)
    Assert-Succeeds (Invoke-RootBuild 'complete certification root' $completeRoot)

    Assert-Fails (
        Invoke-RootBuild 'expired root' (
            New-RootJson $officialKeys $officialRoles '' '2020-01-01T00:00:00Z'
        )
    ) 'expired'

    Assert-Fails (
        Invoke-RootBuild 'missing official role' (New-RootJson ([ordered]@{}) ([ordered]@{}))
    ) 'official-repository'

    $wrongKeys = [ordered]@{}
    $wrongId = 'ed25519:' + ('0' * 64)
    $wrongKeys[$wrongId] = $key1.Declaration
    $wrongRoles = [ordered]@{}
    $wrongRoles['official-repository'] = New-Role $wrongId
    Assert-Fails (
        Invoke-RootBuild 'wrong key id' (New-RootJson $wrongKeys $wrongRoles)
    ) 'does not match'

    $reusedRoles = [ordered]@{}
    $reusedRoles['official-repository'] = New-Role $key1.Id
    $reusedRoles['repository-attestor'] = New-Role $key1.Id
    $reusedRoles['artifact-attestor'] = New-Role $key3.Id
    $reusedRoles['trust-status'] = New-Role $key4.Id
    Assert-Fails (
        Invoke-RootBuild 'reused online key' (
            New-RootJson $completeKeys $reusedRoles 'https://trust.aura.example/status.json'
        )
    ) 'must not reuse'

    $partialRoles = [ordered]@{}
    $partialRoles['official-repository'] = New-Role $key1.Id
    $partialRoles['repository-attestor'] = New-Role $key2.Id
    Assert-Fails (
        Invoke-RootBuild 'partial certification suite' (New-RootJson $completeKeys $partialRoles)
    ) 'all be present or all be absent'

    Assert-Fails (
        Invoke-RootBuild 'non-HTTPS certification status' (
            New-RootJson $completeKeys $completeRoles 'http://trust.aura.example/status.json'
        )
    ) 'HTTPS'

    Assert-Fails (
        Invoke-RootBuild 'official-only non-blank status' (
            New-RootJson $officialKeys $officialRoles 'https://trust.aura.example/status.json'
        )
    ) 'must be blank'

    Write-Host 'Aura plugin trust-root profile tests passed.'
} finally {
    $resolvedTemporary = [System.IO.Path]::GetFullPath($temporary)
    $resolvedSystemTemporary = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if (-not $resolvedTemporary.StartsWith(
            $resolvedSystemTemporary,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
        throw "Refusing to remove non-temporary path: $resolvedTemporary"
    }
    Remove-Item -LiteralPath $resolvedTemporary -Recurse -Force
}

$global:LASTEXITCODE = 0
