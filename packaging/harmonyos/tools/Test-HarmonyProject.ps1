$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$sourceRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$projectRoot = Join-Path $sourceRoot 'packaging/harmonyos'
$maximumTextBytes = 1024 * 1024
$maximumJsonBytes = 256 * 1024

function Assert-Condition([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw $Message
    }
}

function Assert-File([string]$Path) {
    Assert-Condition (Test-Path -LiteralPath $Path -PathType Leaf) "Required file is missing: $Path"
}

function Read-BoundedUtf8([string]$Path, [int]$MaximumBytes = $maximumTextBytes) {
    Assert-File $Path
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    Assert-Condition ($bytes.Length -le $MaximumBytes) "File exceeds $MaximumBytes bytes: $Path"
    $utf8 = New-Object System.Text.UTF8Encoding($false, $true)
    try {
        return $utf8.GetString($bytes)
    } catch {
        throw "File is not valid UTF-8: $Path"
    }
}

function Remove-Json5Comments([string]$Text, [string]$Path) {
    $result = New-Object System.Text.StringBuilder
    $inString = $false
    $escaped = $false
    $lineComment = $false
    $blockComment = $false

    for ($index = 0; $index -lt $Text.Length; $index++) {
        $character = $Text[$index]
        $next = if ($index + 1 -lt $Text.Length) { $Text[$index + 1] } else { [char]0 }

        if ($lineComment) {
            if ($character -eq "`n" -or $character -eq "`r") {
                $lineComment = $false
                [void]$result.Append($character)
            } else {
                [void]$result.Append(' ')
            }
            continue
        }

        if ($blockComment) {
            if ($character -eq '*' -and $next -eq '/') {
                [void]$result.Append('  ')
                $index++
                $blockComment = $false
            } elseif ($character -eq "`n" -or $character -eq "`r") {
                [void]$result.Append($character)
            } else {
                [void]$result.Append(' ')
            }
            continue
        }

        if ($inString) {
            [void]$result.Append($character)
            if ($escaped) {
                $escaped = $false
            } elseif ($character -eq '\') {
                $escaped = $true
            } elseif ($character -eq '"') {
                $inString = $false
            }
            continue
        }

        if ($character -eq '"') {
            $inString = $true
            [void]$result.Append($character)
        } elseif ($character -eq '/' -and $next -eq '/') {
            [void]$result.Append('  ')
            $index++
            $lineComment = $true
        } elseif ($character -eq '/' -and $next -eq '*') {
            [void]$result.Append('  ')
            $index++
            $blockComment = $true
        } else {
            [void]$result.Append($character)
        }
    }

    Assert-Condition (-not $blockComment) "Unterminated JSON5 block comment: $Path"
    Assert-Condition (-not $inString) "Unterminated JSON5 string: $Path"
    return $result.ToString()
}

function Remove-Json5TrailingCommas([string]$Text) {
    $result = New-Object System.Text.StringBuilder
    $inString = $false
    $escaped = $false

    for ($index = 0; $index -lt $Text.Length; $index++) {
        $character = $Text[$index]
        if ($inString) {
            [void]$result.Append($character)
            if ($escaped) {
                $escaped = $false
            } elseif ($character -eq '\') {
                $escaped = $true
            } elseif ($character -eq '"') {
                $inString = $false
            }
            continue
        }

        if ($character -eq '"') {
            $inString = $true
            [void]$result.Append($character)
            continue
        }

        if ($character -eq ',') {
            $nextIndex = $index + 1
            while ($nextIndex -lt $Text.Length -and [char]::IsWhiteSpace($Text[$nextIndex])) {
                $nextIndex++
            }
            if ($nextIndex -lt $Text.Length `
                    -and ($Text[$nextIndex] -eq '}' -or $Text[$nextIndex] -eq ']')) {
                [void]$result.Append(' ')
                continue
            }
        }
        [void]$result.Append($character)
    }
    return $result.ToString()
}

function Read-Json5([string]$Path) {
    $text = Read-BoundedUtf8 $Path $maximumJsonBytes
    $withoutComments = Remove-Json5Comments $text $Path
    $strictJson = Remove-Json5TrailingCommas $withoutComments
    try {
        return $strictJson | ConvertFrom-Json
    } catch {
        throw "Invalid bounded JSON5 document ${Path}: $($_.Exception.Message)"
    }
}

function Assert-SetEquals([object[]]$Actual, [string[]]$Expected, [string]$Message) {
    $actualValues = @($Actual | ForEach-Object { [string]$_ } | Sort-Object)
    $expectedValues = @($Expected | Sort-Object)
    $difference = @(Compare-Object $expectedValues $actualValues -CaseSensitive)
    Assert-Condition ($difference.Count -eq 0) `
        "$Message. Expected: $($expectedValues -join ', '); actual: $($actualValues -join ', ')"
}

function Assert-FileContains([string]$Text, [string]$Expected, [string]$Path) {
    Assert-Condition ($Text.IndexOf($Expected, [System.StringComparison]::Ordinal) -ge 0) `
        "$Path must contain: $Expected"
}

function Assert-FileDoesNotContain([string]$Text, [string[]]$Forbidden, [string]$Path) {
    foreach ($value in $Forbidden) {
        Assert-Condition ($Text.IndexOf($value, [System.StringComparison]::Ordinal) -lt 0) `
            "$Path must not contain: $value"
    }
}

$requiredFiles = @(
    'README.md',
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
    'entry/src/main/cpp/types/libaura_launcher/oh-package.json5',
    'hnp/bin/aura-launcher'
)
foreach ($relativePath in $requiredFiles) {
    Assert-File (Join-Path $projectRoot $relativePath)
}

$app = (Read-Json5 (Join-Path $projectRoot 'AppScope/app.json5')).app
Assert-Condition ($app.bundleName -ceq 'com.eggchina.auralauncher') 'Wrong HarmonyOS bundle name'
Assert-Condition ([int]$app.versionCode -gt 0) 'Source HAP versionCode must be positive'
Assert-Condition ($app.versionName -ceq '27.1-next') 'Source HAP versionName must be 27.1-next'

$buildProfile = (Read-Json5 (Join-Path $projectRoot 'build-profile.json5')).app
$defaultProduct = @($buildProfile.products | Where-Object { $_.name -ceq 'default' })
Assert-Condition ($defaultProduct.Count -eq 1) 'HarmonyOS project must define one default product'
Assert-Condition ($defaultProduct[0].compatibleSdkVersion -ceq '6.0.1(21)') `
    'compatibleSdkVersion must be 6.0.1(21)'
Assert-Condition ($defaultProduct[0].targetSdkVersion -ceq '6.0.1(21)') `
    'targetSdkVersion must be 6.0.1(21)'

$module = (Read-Json5 (Join-Path $projectRoot 'entry/src/main/module.json5')).module
Assert-Condition ($module.name -ceq 'entry' -and $module.type -ceq 'entry') `
    'HarmonyOS package must contain the entry module'
Assert-Condition ($module.deviceTypes.Count -eq 1 -and $module.deviceTypes[0] -ceq '2in1') `
    'HarmonyOS package must target 2in1 only'
Assert-Condition ($module.hnpPackages.Count -eq 1 `
        -and $module.hnpPackages[0].package -ceq 'aura_launcher.hnp' `
        -and $module.hnpPackages[0].type -ceq 'private') 'Aura HNP must be private'
Assert-SetEquals $module.requestPermissions.name @(
    'ohos.permission.INTERNET',
    'ohos.permission.FILE_ACCESS_PERSIST',
    'ohos.permission.READ_WRITE_DESKTOP_DIRECTORY',
    'ohos.permission.READ_WRITE_DOCUMENTS_DIRECTORY',
    'ohos.permission.READ_WRITE_DOWNLOAD_DIRECTORY'
) 'HarmonyOS permission allowlist changed'

$expectedStrings = @(
    'app_name',
    'module_description',
    'status_prerequisite_missing',
    'status_ready',
    'status_starting',
    'status_started_unverified',
    'status_early_exit',
    'diagnostics_title',
    'permission_reason',
    'retry'
)
$baseStrings = (Read-Json5 (Join-Path $projectRoot 'entry/src/main/resources/base/element/string.json')).string
$chineseStrings = (Read-Json5 (Join-Path $projectRoot 'entry/src/main/resources/zh_CN/element/string.json')).string
Assert-SetEquals $baseStrings.name $expectedStrings 'English Stage resources are incomplete'
Assert-SetEquals $chineseStrings.name $expectedStrings 'Simplified Chinese Stage resources are incomplete'
foreach ($resource in @($baseStrings + $chineseStrings)) {
    Assert-Condition (-not [string]::IsNullOrWhiteSpace([string]$resource.value)) `
        "Blank HarmonyOS resource: $($resource.name)"
}

$sourceIcon = Join-Path $sourceRoot 'docs/assets/aura-launcher.png'
$stageIcon = Join-Path $projectRoot 'AppScope/resources/base/media/app_icon.png'
Assert-Condition ((Get-FileHash -LiteralPath $sourceIcon -Algorithm SHA256).Hash `
        -ceq (Get-FileHash -LiteralPath $stageIcon -Algorithm SHA256).Hash) `
    'HarmonyOS app icon must exactly match the repository Aura icon'

$declarationsPath = Join-Path $projectRoot 'entry/src/main/cpp/types/libaura_launcher/index.d.ts'
$declarations = Read-BoundedUtf8 $declarationsPath
Assert-FileContains $declarations 'export interface LaunchResult' $declarationsPath
Assert-FileContains $declarations 'export interface PollResult' $declarationsPath
Assert-FileContains $declarations 'export const startAura: (logPath: string) => LaunchResult;' $declarationsPath
Assert-FileContains $declarations 'export const pollAura: (pid: number) => PollResult;' $declarationsPath
Assert-FileContains $declarations 'export const readDiagnosticTail: (logPath: string) => string;' $declarationsPath
Assert-FileDoesNotContain $declarations @('command:', 'arguments:', 'environment:', 'executable:') $declarationsPath
$exportNames = @([regex]::Matches($declarations, '(?m)^\s*export\s+(?:interface|const)\s+([A-Za-z0-9_]+)') |
    ForEach-Object { $_.Groups[1].Value })
Assert-SetEquals $exportNames @(
    'LaunchResult', 'PollResult', 'startAura', 'pollAura', 'readDiagnosticTail'
) 'Native TypeScript surface changed'

$nativePath = Join-Path $projectRoot 'entry/src/main/cpp/aura_launcher_napi.cpp'
$nativeSource = Read-BoundedUtf8 $nativePath
foreach ($required in @(
        '"/data/app/bin/aura-launcher"',
        'constexpr size_t kMaximumDiagnosticBytes = 16 * 1024',
        'constexpr std::chrono::seconds kStartupWindow(15)',
        'fork()',
        'O_CREAT | O_WRONLY | O_APPEND | O_CLOEXEC',
        'dup2(',
        'execv(kAuraExecutable, argv)',
        'waitpid(',
        'WNOHANG')) {
    Assert-FileContains $nativeSource $required $nativePath
}
Assert-Condition ([regex]::Matches($nativeSource, '\bnapi_get_cb_info\s*\(').Count -eq 2) `
    'Native argument decoding must use one bounded two-pass callback-info reader'
Assert-FileContains $nativeSource 'argumentCount != 1' $nativePath
Assert-FileDoesNotContain $nativeSource @('system(', 'popen(', '/bin/sh') $nativePath

$pagePath = Join-Path $projectRoot 'entry/src/main/ets/pages/Index.ets'
$pageSource = Read-BoundedUtf8 $pagePath
Assert-FileContains $pageSource "filesDir + '/aura-launcher.log'" $pagePath
Assert-FileContains $pageSource 'setInterval(' $pagePath
Assert-FileContains $pageSource ', 1000)' $pagePath
Assert-FileContains $pageSource '15_000' $pagePath
Assert-FileDoesNotContain $pageSource @('TextInput', 'TextArea', 'commandLine', 'executablePath') $pagePath

$shellPath = Join-Path $projectRoot 'hnp/bin/aura-launcher'
$shellSource = Read-BoundedUtf8 $shellPath
Assert-FileContains $shellSource '#!/system/bin/sh' $shellPath
Assert-FileContains $shellSource 'Aura-Launcher-27.1-next.jar' $shellPath
Assert-FileContains $shellSource 'BiShengJDK17-OH is required' $shellPath
Assert-FileContains $shellSource 'exec "$JAVA" -jar "$JAR"' $shellPath

$tracked = @(git -C $sourceRoot ls-files -- 'packaging/harmonyos')
Assert-Condition ($LASTEXITCODE -eq 0) 'Unable to inspect tracked HarmonyOS files'
$forbiddenTracked = @($tracked | Where-Object {
        $_ -cmatch '(?i)\.(hnp|hap|p12|pfx|pem|key|cer|jks)$' `
            -or $_ -cmatch '(?i)(bisheng|jdk).*\.(zip|tar|gz|tgz|7z)$'
    })
Assert-Condition ($forbiddenTracked.Count -eq 0) `
    "Generated package, JDK, or signing material is tracked: $($forbiddenTracked -join ', ')"

Write-Host 'Aura HarmonyOS Stage project contract tests passed.'
