$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$gradleWorkflow = Get-Content -Raw -LiteralPath (Join-Path $repositoryRoot '.github\workflows\gradle.yml')
$releaseWorkflow = Get-Content -Raw -LiteralPath (Join-Path $repositoryRoot '.github\workflows\release.yml')
$publicRootBinding = 'AURA_PLUGIN_ROOT_JSON: ${{ vars.AURA_PLUGIN_ROOT_JSON }}'

function Assert-Condition([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw $Message
    }
}

function Assert-ActionsPinned([string]$Name, [string]$Workflow) {
    $uses = [regex]::Matches($Workflow, '(?m)^\s*(?:-\s*)?uses:\s*([^\s#]+)')
    Assert-Condition ($uses.Count -gt 0) "$Name must use at least one action"
    foreach ($match in $uses) {
        Assert-Condition ($match.Groups[1].Value -match '@[0-9a-f]{40}$') `
            "$Name action is not pinned to a full commit SHA: $($match.Groups[1].Value)"
    }
}

Assert-Condition ($gradleWorkflow.Contains($publicRootBinding)) `
    'Java CI must inject the public plugin root.'
Assert-Condition ($releaseWorkflow.Contains($publicRootBinding)) `
    'Release CI must inject the public plugin root.'
Assert-Condition (-not ($gradleWorkflow + $releaseWorkflow).Contains(
        'AURA_OFFICIAL_REGISTRY_SIGNING_KEY'
    )) 'Aura workflows must never receive the Store signing key.'
Assert-Condition (-not ($gradleWorkflow + $releaseWorkflow).Contains(
        'AURA_PLUGIN_ROOT_JSON: ${{ secrets.'
    )) 'The public plugin root must come from a repository variable, not a secret.'

Assert-Condition ($gradleWorkflow.Contains(
        'pwsh -NoProfile -File ./tools/test-gradle-wrapper.ps1'
    )) 'Java CI must test Gradle wrapper selection before trust-root profile tests.'
Assert-Condition ($gradleWorkflow.Contains(
        'pwsh -NoProfile -File ./tools/test-plugin-trust-root.ps1'
    )) 'Java CI must run plugin trust-root profile tests.'
Assert-Condition ($gradleWorkflow.Contains("AURA_PUBLIC_STORE_SMOKE: 'true'")) `
    'Java CI must opt in to the public Store smoke test.'
Assert-Condition ($gradleWorkflow.Contains(
        '--tests org.jackhuang.hmcl.plugin.store.PluginStorePublicSmokeTest'
    )) 'Java CI must run the public Store smoke test explicitly.'

$buildVersionBindings = [regex]::Matches(
    $releaseWorkflow,
    '(?m)^\s*BUILD_VERSION:\s*\$\{\{\s*inputs\.version\s*\}\}\s*$'
)
Assert-Condition ($buildVersionBindings.Count -eq 1) `
    'Release CI must pass the unsuffixed input to BUILD_VERSION exactly once.'
Assert-Condition (-not ($releaseWorkflow -match '(?m)^\s*BUILD_VERSION:.*-next')) `
    'Release CI must not append -next before Gradle applies the suffix.'
Assert-Condition ($releaseWorkflow.Contains('Aura-Launcher-$RELEASE_VERSION-next.')) `
    'Release verification must require the once-suffixed Aura artifact name.'

Assert-ActionsPinned 'Java CI' $gradleWorkflow
Assert-ActionsPinned 'Release CI' $releaseWorkflow

Write-Host 'Aura plugin trust workflow policy tests passed.'
