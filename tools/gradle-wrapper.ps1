function Resolve-AuraGradleWrapper(
    [string]$RepositoryRoot,
    [bool]$IsWindowsPlatform
) {
    $wrapperName = if ($IsWindowsPlatform) { 'gradlew.bat' } else { 'gradlew' }
    $wrapper = Join-Path $RepositoryRoot $wrapperName
    if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
        throw "Missing Gradle wrapper for the current platform: $wrapper"
    }
    return $wrapper
}
