$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot 'gradle-wrapper.ps1')

function Assert-Condition([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw $Message
    }
}

$systemTemporary = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd(
    [System.IO.Path]::DirectorySeparatorChar,
    [System.IO.Path]::AltDirectorySeparatorChar
)
$temporary = [System.IO.Path]::GetFullPath((Join-Path $systemTemporary (
    'aura-gradle-wrapper-' + [guid]::NewGuid().ToString('N')
)))
$temporaryPrefix = $systemTemporary + [System.IO.Path]::DirectorySeparatorChar
Assert-Condition ($temporary.StartsWith($temporaryPrefix, [System.StringComparison]::OrdinalIgnoreCase)) `
    'Gradle wrapper test directory must remain below the system temporary directory.'

try {
    [void][System.IO.Directory]::CreateDirectory($temporary)
    $windowsWrapper = Join-Path $temporary 'gradlew.bat'
    $unixWrapper = Join-Path $temporary 'gradlew'
    [System.IO.File]::WriteAllText($windowsWrapper, '')
    [System.IO.File]::WriteAllText($unixWrapper, '')

    Assert-Condition (
        (Resolve-AuraGradleWrapper -RepositoryRoot $temporary -IsWindowsPlatform $true) -ceq $windowsWrapper
    ) 'Windows must select gradlew.bat.'
    Assert-Condition (
        (Resolve-AuraGradleWrapper -RepositoryRoot $temporary -IsWindowsPlatform $false) -ceq $unixWrapper
    ) 'Unix platforms must select gradlew.'

    Remove-Item -LiteralPath $unixWrapper -Force
    $missingFailure = $null
    try {
        [void](Resolve-AuraGradleWrapper -RepositoryRoot $temporary -IsWindowsPlatform $false)
    } catch {
        $missingFailure = $_.Exception.Message
    }
    Assert-Condition (-not [string]::IsNullOrWhiteSpace($missingFailure)) `
        'A missing platform wrapper must fail before Gradle execution.'
    Assert-Condition ($missingFailure.Contains('gradlew')) `
        'The missing-wrapper failure must identify the selected wrapper.'
} finally {
    if (Test-Path -LiteralPath $temporary) {
        Remove-Item -LiteralPath $temporary -Recurse -Force
    }
}

Write-Host 'Aura Gradle wrapper selection tests passed.'
