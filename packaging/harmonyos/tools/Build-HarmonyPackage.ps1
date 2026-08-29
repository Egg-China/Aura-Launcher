[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$LauncherJar,
    [Parameter(Mandatory)][string]$HnpCli,
    [Parameter(Mandatory)][string]$Hvigor,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [Parameter(Mandatory)]
    [ValidateScript({
        if ($_ -le 0) {
            throw 'versionCode must be a positive integer'
        }
        return $true
    })]
    [int]$VersionCode,
    [string]$Signer = '',
    [string]$SigningProfile = '',
    [string]$SigningCertificate = '',
    [string]$SigningKeyAlias = '',
    [string]$SigningPasswordReference = '',
    [ValidateSet('debug', 'release')][string]$SigningKind = 'release'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$launcherVersion = '27.1-next'
$hnpVersion = '27.1.next'
$sdkVersion = '6.0.1(21)'
$target = 'harmonyos-arm64'
$expectedJarName = "Aura-Launcher-$launcherVersion.jar"
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$sourceRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$evidenceSchemaPath = Join-Path $projectRoot 'evidence.schema.json'
$pathComparison = if ($env:OS -ceq 'Windows_NT') {
    [System.StringComparison]::OrdinalIgnoreCase
} else {
    [System.StringComparison]::Ordinal
}

function Write-Utf8NoBom([string]$Path, [string]$Content) {
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $utf8)
}

function Get-CanonicalPath([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw 'A required path is blank'
    }

    $fullPath = [System.IO.Path]::GetFullPath($Path)
    if (Test-Path -LiteralPath $fullPath) {
        return [System.IO.Path]::GetFullPath(
            (Resolve-Path -LiteralPath $fullPath -ErrorAction Stop).ProviderPath
        )
    }

    $missingSegments = New-Object System.Collections.Generic.List[string]
    $cursor = $fullPath
    while (-not (Test-Path -LiteralPath $cursor)) {
        $leaf = Split-Path -Leaf $cursor
        if ([string]::IsNullOrWhiteSpace($leaf)) {
            throw "Unable to resolve path: $Path"
        }
        $missingSegments.Insert(0, $leaf)
        $parent = Split-Path -Parent $cursor
        if ([string]::IsNullOrWhiteSpace($parent) -or $parent -ceq $cursor) {
            throw "Unable to resolve path: $Path"
        }
        $cursor = $parent
    }

    $resolved = [System.IO.Path]::GetFullPath(
        (Resolve-Path -LiteralPath $cursor -ErrorAction Stop).ProviderPath
    )
    foreach ($segment in $missingSegments) {
        $resolved = Join-Path $resolved $segment
    }
    return [System.IO.Path]::GetFullPath($resolved)
}

function Test-IsPathWithin([string]$Candidate, [string]$Root, [bool]$AllowEqual = $true) {
    $candidatePath = [System.IO.Path]::GetFullPath($Candidate).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    $rootPath = [System.IO.Path]::GetFullPath($Root).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    if ($AllowEqual -and $candidatePath.Equals($rootPath, $pathComparison)) {
        return $true
    }
    $prefix = $rootPath + [System.IO.Path]::DirectorySeparatorChar
    return $candidatePath.StartsWith($prefix, $pathComparison)
}

function Resolve-RequiredFile([string]$Path, [string]$Description) {
    $resolved = Get-CanonicalPath $Path
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
        throw "$Description does not exist or is not a file"
    }
    return $resolved
}

function Invoke-ExternalTool(
    [string]$DisplayName,
    [string]$ToolPath,
    [string[]]$ToolArguments,
    [string]$WorkingDirectory = '',
    [switch]$RedactOutput
) {
    $previousLocation = Get-Location
    try {
        if (-not [string]::IsNullOrWhiteSpace($WorkingDirectory)) {
            Set-Location -LiteralPath $WorkingDirectory
        }
        $global:LASTEXITCODE = 0
        try {
            $records = @(& $ToolPath @ToolArguments 2>&1)
            $exitCode = $LASTEXITCODE
        } catch {
            if ($RedactOutput) {
                throw "$DisplayName invocation failed"
            }
            throw "$DisplayName invocation failed: $($_.Exception.Message)"
        }
    } finally {
        Set-Location -LiteralPath $previousLocation.Path
    }

    if ($exitCode -ne 0) {
        throw "$DisplayName exited with code $exitCode"
    }
    return @($records | ForEach-Object { $_.ToString() })
}

function Get-BoundedToolVersion([string]$DisplayName, [string]$ToolPath) {
    $records = @(Invoke-ExternalTool -DisplayName $DisplayName -ToolPath $ToolPath `
        -ToolArguments @('--version'))
    $version = [regex]::Replace(($records -join ' ').Trim(), '\s+', ' ')
    if ([string]::IsNullOrWhiteSpace($version) -or $version.Length -gt 256) {
        throw "$DisplayName returned an invalid bounded version"
    }
    foreach ($character in $version.ToCharArray()) {
        if ([char]::IsControl($character)) {
            throw "$DisplayName returned an invalid bounded version"
        }
    }
    $toolDirectory = Split-Path -Parent $ToolPath
    if ($version.IndexOf($ToolPath, $pathComparison) -ge 0 `
            -or (-not [string]::IsNullOrWhiteSpace($toolDirectory) `
                -and $version.IndexOf($toolDirectory, $pathComparison) -ge 0)) {
        throw "$DisplayName version must not expose an absolute tool path"
    }
    return $version
}

function Read-JarImplementationVersion([string]$JarPath) {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem

    $stream = [System.IO.File]::OpenRead($JarPath)
    try {
        $archive = New-Object System.IO.Compression.ZipArchive(
            $stream,
            [System.IO.Compression.ZipArchiveMode]::Read,
            $true
        )
        try {
            $entries = @($archive.Entries | Where-Object { $_.FullName -ceq 'META-INF/MANIFEST.MF' })
            if ($entries.Count -ne 1 -or $entries[0].Length -gt 65536) {
                throw 'Launcher JAR must contain one bounded META-INF/MANIFEST.MF'
            }
            $entryStream = $entries[0].Open()
            try {
                $encoding = New-Object System.Text.UTF8Encoding($false, $true)
                $reader = New-Object System.IO.StreamReader($entryStream, $encoding, $true, 1024, $true)
                try {
                    $manifest = $reader.ReadToEnd()
                } finally {
                    $reader.Dispose()
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

    $logicalLines = New-Object System.Collections.Generic.List[string]
    $current = ''
    foreach ($line in ($manifest -split "`r?`n")) {
        if ($line.StartsWith(' ', [System.StringComparison]::Ordinal)) {
            if ([string]::IsNullOrEmpty($current)) {
                throw 'Launcher JAR manifest contains an invalid continuation line'
            }
            $current += $line.Substring(1)
        } else {
            if (-not [string]::IsNullOrEmpty($current)) {
                $logicalLines.Add($current)
            }
            $current = $line
        }
    }
    if (-not [string]::IsNullOrEmpty($current)) {
        $logicalLines.Add($current)
    }

    $prefix = 'Implementation-Version:'
    $versions = @($logicalLines | Where-Object {
        $_.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)
    })
    if ($versions.Count -ne 1) {
        throw 'Launcher JAR must contain exactly one Implementation-Version'
    }
    return $versions[0].Substring($prefix.Length).Trim()
}

function Copy-RequiredFile([string]$Source, [string]$Destination) {
    if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) {
        throw "Required packaging source is missing: $Source"
    }
    $parent = Split-Path -Parent $Destination
    [void](New-Item -ItemType Directory -Path $parent -Force)
    Copy-Item -LiteralPath $Source -Destination $Destination
}

function Copy-StageProject([string]$DestinationRoot) {
    $projectFiles = @(
        'oh-package.json5',
        'build-profile.json5',
        'hvigorfile.ts',
        'hvigor/hvigor-config.json5',
        'AppScope/app.json5',
        'AppScope/resources/base/element/string.json',
        'AppScope/resources/base/media/app_icon.png',
        'entry/oh-package.json5',
        'entry/build-profile.json5',
        'entry/hvigorfile.ts',
        'entry/src/main/module.json5',
        'entry/src/main/ets/entryability/EntryAbility.ets',
        'entry/src/main/ets/pages/Index.ets',
        'entry/src/main/resources/base/element/color.json',
        'entry/src/main/resources/base/element/string.json',
        'entry/src/main/resources/zh_CN/element/string.json',
        'entry/src/main/resources/base/profile/main_pages.json',
        'entry/src/main/cpp/CMakeLists.txt',
        'entry/src/main/cpp/aura_launcher_napi.cpp',
        'entry/src/main/cpp/types/libaura_launcher/index.d.ts',
        'entry/src/main/cpp/types/libaura_launcher/oh-package.json5'
    )
    foreach ($relativePath in $projectFiles) {
        Copy-RequiredFile (Join-Path $projectRoot $relativePath) `
            (Join-Path $DestinationRoot $relativePath)
    }
}

function Assert-ObjectHasProperty([object]$Value, [string]$Name, [string]$JsonPath) {
    if ($null -eq $Value.PSObject.Properties[$Name]) {
        throw "Evidence schema validation failed at ${JsonPath}: missing property $Name"
    }
}

function Test-IsJsonInteger([object]$Value) {
    return $Value -is [byte] -or $Value -is [sbyte] `
        -or $Value -is [int16] -or $Value -is [uint16] `
        -or $Value -is [int32] -or $Value -is [uint32] `
        -or $Value -is [int64] -or $Value -is [uint64]
}

function Assert-JsonSchemaNode(
    [object]$Value,
    [object]$Schema,
    [string]$JsonPath
) {
    Assert-ObjectHasProperty $Schema 'type' '$schema'
    $type = [string]$Schema.type
    switch ($type) {
        'object' {
            if ($null -eq $Value -or $Value -is [string] -or $Value -is [array] `
                    -or $Value -is [System.Collections.IDictionary]) {
                throw "Evidence schema validation failed at ${JsonPath}: expected object"
            }
            if ($null -ne $Schema.PSObject.Properties['required']) {
                foreach ($requiredName in @($Schema.required)) {
                    Assert-ObjectHasProperty $Value ([string]$requiredName) $JsonPath
                }
            }
            Assert-ObjectHasProperty $Schema 'properties' '$schema'
            $allowedNames = @($Schema.properties.PSObject.Properties.Name)
            if ($null -ne $Schema.PSObject.Properties['additionalProperties'] `
                    -and -not [bool]$Schema.additionalProperties) {
                foreach ($property in @($Value.PSObject.Properties)) {
                    if ([string]$property.Name -cnotin $allowedNames) {
                        throw "Evidence schema validation failed at ${JsonPath}: unknown property $($property.Name)"
                    }
                }
            }
            foreach ($schemaProperty in @($Schema.properties.PSObject.Properties)) {
                $actualProperty = $Value.PSObject.Properties[$schemaProperty.Name]
                if ($null -ne $actualProperty) {
                    Assert-JsonSchemaNode $actualProperty.Value $schemaProperty.Value `
                        "$JsonPath.$($schemaProperty.Name)"
                }
            }
        }
        'array' {
            if (-not ($Value -is [array])) {
                throw "Evidence schema validation failed at ${JsonPath}: expected array"
            }
            $items = @($Value)
            if ($null -ne $Schema.PSObject.Properties['minItems'] `
                    -and $items.Count -lt [int]$Schema.minItems) {
                throw "Evidence schema validation failed at ${JsonPath}: too few items"
            }
            if ($null -ne $Schema.PSObject.Properties['maxItems'] `
                    -and $items.Count -gt [int]$Schema.maxItems) {
                throw "Evidence schema validation failed at ${JsonPath}: too many items"
            }
            for ($index = 0; $index -lt $items.Count; $index++) {
                Assert-JsonSchemaNode $items[$index] $Schema.items "$JsonPath[$index]"
            }
        }
        'integer' {
            if (-not (Test-IsJsonInteger $Value)) {
                throw "Evidence schema validation failed at ${JsonPath}: expected integer"
            }
            if ($null -ne $Schema.PSObject.Properties['minimum'] `
                    -and [decimal]$Value -lt [decimal]$Schema.minimum) {
                throw "Evidence schema validation failed at ${JsonPath}: below minimum"
            }
        }
        'string' {
            if (-not ($Value -is [string])) {
                throw "Evidence schema validation failed at ${JsonPath}: expected string"
            }
            if ($null -ne $Schema.PSObject.Properties['minLength'] `
                    -and $Value.Length -lt [int]$Schema.minLength) {
                throw "Evidence schema validation failed at ${JsonPath}: string is too short"
            }
            if ($null -ne $Schema.PSObject.Properties['maxLength'] `
                    -and $Value.Length -gt [int]$Schema.maxLength) {
                throw "Evidence schema validation failed at ${JsonPath}: string is too long"
            }
            if ($null -ne $Schema.PSObject.Properties['pattern'] `
                    -and -not [regex]::IsMatch($Value, [string]$Schema.pattern)) {
                throw "Evidence schema validation failed at ${JsonPath}: pattern mismatch"
            }
        }
        default {
            throw "Evidence schema uses unsupported type: $type"
        }
    }

    if ($null -ne $Schema.PSObject.Properties['enum']) {
        $matched = $false
        foreach ($allowed in @($Schema.enum)) {
            if ($Value -ceq $allowed) {
                $matched = $true
                break
            }
        }
        if (-not $matched) {
            throw "Evidence schema validation failed at ${JsonPath}: value is not allowed"
        }
    }
}

function Assert-EvidenceAgainstSchema([string]$EvidenceJson, [string]$SchemaPath) {
    $schemaFile = Get-Item -LiteralPath $SchemaPath
    if ($schemaFile.Length -gt 65536) {
        throw 'HarmonyOS evidence schema exceeds 64 KiB'
    }
    try {
        $schema = Get-Content -LiteralPath $SchemaPath -Raw | ConvertFrom-Json
        $evidence = $EvidenceJson | ConvertFrom-Json
    } catch {
        throw "Unable to parse HarmonyOS evidence schema or document: $($_.Exception.Message)"
    }
    Assert-JsonSchemaNode $evidence $schema '$'
}

function Remove-ValidatedStage([string]$StagePath, [string]$StagingRoot) {
    if ([string]::IsNullOrWhiteSpace($StagePath) -or -not (Test-Path -LiteralPath $StagePath)) {
        return
    }
    $resolvedStage = Get-CanonicalPath $StagePath
    $resolvedRoot = Get-CanonicalPath $StagingRoot
    if (-not (Test-IsPathWithin $resolvedStage $resolvedRoot $false)) {
        throw "Refusing to remove staging path outside its boundary: $resolvedStage"
    }
    Remove-Item -LiteralPath $resolvedStage -Recurse -Force
}

$resolvedProjectRoot = Get-CanonicalPath $projectRoot
$resolvedSourceRoot = Get-CanonicalPath $sourceRoot
$resolvedLauncherJar = Resolve-RequiredFile $LauncherJar 'Launcher JAR'
$resolvedHnpCli = Resolve-RequiredFile $HnpCli 'hnpcli'
$resolvedHvigor = Resolve-RequiredFile $Hvigor 'Hvigor'
$resolvedEvidenceSchema = Resolve-RequiredFile $evidenceSchemaPath 'HarmonyOS evidence schema'
$resolvedOutput = Get-CanonicalPath $OutputDirectory

if (Test-IsPathWithin $resolvedOutput $resolvedProjectRoot $true) {
    throw 'OutputDirectory must be outside the source template'
}
if ((Split-Path -Leaf $resolvedLauncherJar) -cne $expectedJarName) {
    throw "Launcher JAR must be named exactly $expectedJarName"
}
$implementationVersion = Read-JarImplementationVersion $resolvedLauncherJar
if ($implementationVersion -cne $launcherVersion) {
    throw "Launcher JAR Implementation-Version must be exactly $launcherVersion"
}

$signingValues = @(
    $Signer,
    $SigningProfile,
    $SigningCertificate,
    $SigningKeyAlias,
    $SigningPasswordReference
)
$providedSigningValues = @($signingValues | Where-Object {
    -not [string]::IsNullOrWhiteSpace($_)
})
if ($providedSigningValues.Count -ne 0 -and $providedSigningValues.Count -ne 5) {
    throw 'HarmonyOS signing inputs must be complete or all omitted'
}
$signedBuild = $providedSigningValues.Count -eq 5
if (-not $signedBuild -and $SigningKind -ceq 'debug') {
    throw 'Debug signing requires complete signing inputs'
}
$resolvedSigner = ''
$resolvedSigningProfile = ''
$resolvedSigningCertificate = ''
if ($signedBuild) {
    $resolvedSigner = Resolve-RequiredFile $Signer 'Signer'
    $resolvedSigningProfile = Resolve-RequiredFile $SigningProfile 'Signing profile'
    $resolvedSigningCertificate = Resolve-RequiredFile $SigningCertificate 'Signing certificate'
}

if (Test-Path -LiteralPath $resolvedOutput -PathType Leaf) {
    throw 'OutputDirectory must be a directory'
}
[void](New-Item -ItemType Directory -Path $resolvedOutput -Force)
$resolvedOutput = Get-CanonicalPath $resolvedOutput
if (Test-IsPathWithin $resolvedOutput $resolvedProjectRoot $true) {
    throw 'OutputDirectory must be outside the source template'
}

$artifactName = if ($signedBuild) {
    if ($SigningKind -ceq 'debug') {
        "Aura-Launcher-$launcherVersion-$target-debug-signed.hap"
    } else {
        "Aura-Launcher-$launcherVersion-$target.hap"
    }
} else {
    "Aura-Launcher-$launcherVersion-$target-unsigned.hap"
}
$evidenceName = "Aura-Launcher-$launcherVersion-$target-evidence.json"
$artifactOutput = Join-Path $resolvedOutput $artifactName
$hashOutput = "$artifactOutput.sha256"
$evidenceOutput = Join-Path $resolvedOutput $evidenceName
$publicOutputs = @($artifactOutput, $hashOutput, $evidenceOutput)
foreach ($publicOutput in $publicOutputs) {
    if (Test-Path -LiteralPath $publicOutput) {
        throw "Refusing to overwrite existing packaging output: $(Split-Path -Leaf $publicOutput)"
    }
}

$stagingRoot = ''
$stageRoot = ''
try {
    $stagingRoot = Join-Path $resolvedOutput '.staging'
    if (Test-Path -LiteralPath $stagingRoot) {
        $stagingItem = Get-Item -LiteralPath $stagingRoot -Force
        if (-not $stagingItem.PSIsContainer `
                -or ($stagingItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
            throw 'Output-local staging root must be a regular directory'
        }
    } else {
        [void](New-Item -ItemType Directory -Path $stagingRoot)
    }
    $stagingRoot = Get-CanonicalPath $stagingRoot
    if (-not (Test-IsPathWithin $stagingRoot $resolvedOutput $false)) {
        throw 'Output-local staging root escaped OutputDirectory'
    }
    $stageRoot = Join-Path $stagingRoot ([guid]::NewGuid().ToString('N'))
    [void](New-Item -ItemType Directory -Path $stageRoot)
    $stageRoot = Get-CanonicalPath $stageRoot
    if (-not (Test-IsPathWithin $stageRoot $stagingRoot $false)) {
        throw 'Fresh HarmonyOS staging directory escaped its boundary'
    }

    $hnpSource = Join-Path $stageRoot 'hnp-source'
    $hnpOutput = Join-Path $stageRoot 'hnp-output'
    [void](New-Item -ItemType Directory -Path (Join-Path $hnpSource 'bin') -Force)
    [void](New-Item -ItemType Directory -Path (Join-Path $hnpSource 'share/aura') -Force)
    [void](New-Item -ItemType Directory -Path $hnpOutput -Force)

    Copy-RequiredFile (Join-Path $projectRoot 'hnp/bin/aura-launcher') `
        (Join-Path $hnpSource 'bin/aura-launcher')
    Copy-RequiredFile $resolvedLauncherJar `
        (Join-Path $hnpSource "share/aura/$expectedJarName")
    Copy-RequiredFile (Join-Path $resolvedSourceRoot 'LICENSE') (Join-Path $hnpSource 'LICENSE')
    Copy-RequiredFile (Join-Path $projectRoot 'hnp/NOTICE') (Join-Path $hnpSource 'NOTICE')
    Copy-RequiredFile (Join-Path $projectRoot 'hnp/THIRD_PARTY_NOTICES.md') `
        (Join-Path $hnpSource 'THIRD_PARTY_NOTICES.md')

    $hnpDocument = [ordered]@{
        name = 'aura_launcher'
        version = $hnpVersion
        description = "Aura Launcher $launcherVersion private HarmonyOS PC package"
        install = [ordered]@{}
    }
    Write-Utf8NoBom (Join-Path $hnpSource 'hnp.json') `
        (($hnpDocument | ConvertTo-Json -Depth 4) + "`n")

    if ($env:OS -cne 'Windows_NT') {
        $global:LASTEXITCODE = 0
        & chmod 755 (Join-Path $hnpSource 'bin/aura-launcher')
        if ($LASTEXITCODE -ne 0) {
            throw "chmod exited with code $LASTEXITCODE"
        }
    }

    $hnpCliVersion = Get-BoundedToolVersion 'hnpcli' $resolvedHnpCli
    [void](Invoke-ExternalTool -DisplayName 'hnpcli' -ToolPath $resolvedHnpCli `
        -ToolArguments @('pack', '-i', $hnpSource, '-o', $hnpOutput))
    $hnpPackages = @(Get-ChildItem -LiteralPath $hnpOutput -File | Where-Object {
        $_.Extension -ceq '.hnp' -and $_.Length -gt 0
    })
    if ($hnpPackages.Count -ne 1 -or $hnpPackages[0].Name -cne 'aura_launcher.hnp') {
        throw 'hnpcli must produce exactly one nonempty aura_launcher.hnp'
    }

    $stagedProject = Join-Path $stageRoot 'project'
    [void](New-Item -ItemType Directory -Path $stagedProject)
    Copy-StageProject $stagedProject
    $stagedHnp = Join-Path $stagedProject 'entry/src/main/hnp/arm64-v8a/aura_launcher.hnp'
    Copy-RequiredFile $hnpPackages[0].FullName $stagedHnp

    $appPath = Join-Path $stagedProject 'AppScope/app.json5'
    try {
        $appDocument = Get-Content -LiteralPath $appPath -Raw | ConvertFrom-Json
    } catch {
        throw "Unable to parse staged AppScope/app.json5: $($_.Exception.Message)"
    }
    if ($null -eq $appDocument.app) {
        throw 'Staged AppScope/app.json5 is missing app metadata'
    }
    $appDocument.app.versionCode = $VersionCode
    $appDocument.app.versionName = $launcherVersion
    Write-Utf8NoBom $appPath (($appDocument | ConvertTo-Json -Depth 8) + "`n")

    $modulePath = Join-Path $stagedProject 'entry/src/main/module.json5'
    $moduleDocument = Get-Content -LiteralPath $modulePath -Raw | ConvertFrom-Json
    $hnpDeclarations = @($moduleDocument.module.hnpPackages)
    if ($hnpDeclarations.Count -ne 1 `
            -or $hnpDeclarations[0].package -cne 'aura_launcher.hnp' `
            -or $hnpDeclarations[0].type -cne 'private') {
        throw 'Staged module must declare aura_launcher.hnp as private'
    }

    $hvigorVersion = Get-BoundedToolVersion 'Hvigor' $resolvedHvigor
    $hvigorArguments = @(
        '--mode', 'module',
        '-p', 'module=entry@default',
        '-p', 'product=default',
        '-p', 'buildMode=release',
        'assembleHap'
    )
    [void](Invoke-ExternalTool -DisplayName 'Hvigor' -ToolPath $resolvedHvigor `
        -ToolArguments $hvigorArguments -WorkingDirectory $stagedProject)
    $unsignedHaps = @(Get-ChildItem -LiteralPath (Join-Path $stagedProject 'entry/build') `
        -Recurse -File -Filter '*.hap' -ErrorAction SilentlyContinue | Where-Object {
            $_.Length -gt 0
        })
    if ($unsignedHaps.Count -ne 1) {
        throw 'Hvigor must produce exactly one nonempty unsigned HAP'
    }

    $artifactStage = Join-Path $stageRoot $artifactName
    $signingState = 'unsigned'
    if ($signedBuild) {
        [void](Get-BoundedToolVersion 'Signer' $resolvedSigner)
        $signArguments = @(
            'sign',
            '--input', $unsignedHaps[0].FullName,
            '--output', $artifactStage,
            '--profile', $resolvedSigningProfile,
            '--certificate', $resolvedSigningCertificate,
            '--key-alias', $SigningKeyAlias,
            '--password-reference', $SigningPasswordReference
        )
        [void](Invoke-ExternalTool -DisplayName 'Signer sign operation' `
            -ToolPath $resolvedSigner -ToolArguments $signArguments -RedactOutput)
        if (-not (Test-Path -LiteralPath $artifactStage -PathType Leaf) `
                -or (Get-Item -LiteralPath $artifactStage).Length -le 0) {
            throw 'Signer did not produce a nonempty signed HAP'
        }
        $verifyArguments = @(
            'verify',
            '--input', $artifactStage,
            '--profile', $resolvedSigningProfile,
            '--certificate', $resolvedSigningCertificate
        )
        [void](Invoke-ExternalTool -DisplayName 'Signer verification operation' `
            -ToolPath $resolvedSigner -ToolArguments $verifyArguments -RedactOutput)
        $signingState = if ($SigningKind -ceq 'debug') {
            'debug-signed'
        } else {
            'release-signed'
        }
    } else {
        Copy-RequiredFile $unsignedHaps[0].FullName $artifactStage
    }

    $sourceCommitRecords = @(git -C $resolvedSourceRoot rev-parse HEAD 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to resolve the Aura source commit'
    }
    $sourceCommit = ($sourceCommitRecords | Select-Object -First 1).ToString().Trim()
    if ($sourceCommit -cnotmatch '^[0-9a-f]{40}$') {
        throw 'Aura source commit is not a full lowercase Git object ID'
    }

    $artifactHash = (Get-FileHash -LiteralPath $artifactStage -Algorithm SHA256).Hash.ToLowerInvariant()
    $artifactSize = (Get-Item -LiteralPath $artifactStage).Length
    $evidence = [ordered]@{
        schemaVersion = 1
        sourceCommit = $sourceCommit
        sdkVersion = $sdkVersion
        launcherVersion = $launcherVersion
        hnpVersion = $hnpVersion
        versionCode = $VersionCode
        target = $target
        signingState = $signingState
        tools = [ordered]@{
            hnpcli = $hnpCliVersion
            hvigor = $hvigorVersion
        }
        artifacts = @(
            [ordered]@{
                name = $artifactName
                size = [int64]$artifactSize
                sha256 = $artifactHash
            }
        )
    }
    $evidenceJson = ($evidence | ConvertTo-Json -Depth 8) + "`n"
    Assert-EvidenceAgainstSchema $evidenceJson $resolvedEvidenceSchema

    $hashStage = Join-Path $stageRoot "$artifactName.sha256"
    $evidenceStage = Join-Path $stageRoot $evidenceName
    Write-Utf8NoBom $hashStage "$artifactHash  $artifactName`n"
    Write-Utf8NoBom $evidenceStage $evidenceJson

    Move-Item -LiteralPath $artifactStage -Destination $artifactOutput
    Move-Item -LiteralPath $hashStage -Destination $hashOutput
    Move-Item -LiteralPath $evidenceStage -Destination $evidenceOutput
} catch {
    foreach ($publicOutput in $publicOutputs) {
        if (Test-Path -LiteralPath $publicOutput -PathType Leaf) {
            Remove-Item -LiteralPath $publicOutput -Force
        }
    }
    throw
} finally {
    if (-not [string]::IsNullOrWhiteSpace($stageRoot) `
            -and -not [string]::IsNullOrWhiteSpace($stagingRoot)) {
        Remove-ValidatedStage $stageRoot $stagingRoot
    }
    if (-not [string]::IsNullOrWhiteSpace($stagingRoot) `
            -and (Test-Path -LiteralPath $stagingRoot -PathType Container)) {
        $remainingStages = @(Get-ChildItem -LiteralPath $stagingRoot -Force)
        if ($remainingStages.Count -eq 0) {
            Remove-Item -LiteralPath $stagingRoot -Force
        }
    }
}

Write-Host "Created $artifactName and verified schema-v1 packaging evidence."
