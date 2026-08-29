$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$sourceRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$maximumWorkflowBytes = 128 * 1024

function Assert-Condition([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw $Message
    }
}

function Read-Workflow([string]$RelativePath) {
    $path = Join-Path $sourceRoot $RelativePath
    Assert-Condition (Test-Path -LiteralPath $path -PathType Leaf) `
        "HarmonyOS workflow is missing: $RelativePath"
    $item = Get-Item -LiteralPath $path
    Assert-Condition ($item.Length -le $maximumWorkflowBytes) `
        "HarmonyOS workflow exceeds 128 KiB: $RelativePath"
    $encoding = New-Object System.Text.UTF8Encoding($false, $true)
    try {
        return [System.IO.File]::ReadAllText($item.FullName, $encoding)
    } catch {
        throw "HarmonyOS workflow is not strict UTF-8: $RelativePath"
    }
}

function Assert-WorkflowUsesFullActionShas([string]$RelativePath) {
    $text = Read-Workflow $RelativePath
    $matches = @([regex]::Matches(
        $text,
        '(?m)^\s*(?:-\s*)?uses:\s*([^\s#]+)\s*(?:#.*)?$'
    ))
    Assert-Condition ($matches.Count -gt 0) "$RelativePath must use at least one pinned action"
    foreach ($match in $matches) {
        $reference = $match.Groups[1].Value
        Assert-Condition ($reference -cmatch `
                '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*@[0-9a-f]{40}$') `
            "$RelativePath contains an action that is not pinned to a full SHA: $reference"
    }
}

function Get-WorkflowTriggers([string]$RelativePath) {
    $lines = (Read-Workflow $RelativePath) -split "`r?`n"
    $onIndex = -1
    for ($index = 0; $index -lt $lines.Count; $index++) {
        if ($lines[$index] -cmatch '^on:\s*$') {
            $onIndex = $index
            break
        }
    }
    Assert-Condition ($onIndex -ge 0) "$RelativePath must contain a top-level on block"

    $triggers = New-Object System.Collections.Generic.List[string]
    for ($index = $onIndex + 1; $index -lt $lines.Count; $index++) {
        $line = $lines[$index]
        if ($line -cmatch '^[A-Za-z0-9_-]+:\s*') {
            break
        }
        if ($line -cmatch '^  ([A-Za-z0-9_-]+):(?:\s.*)?$') {
            $triggers.Add($Matches[1])
        }
    }
    Assert-Condition ($triggers.Count -gt 0) "$RelativePath must declare at least one trigger"
    return @($triggers)
}

function Assert-WorkflowHasOnlyTrigger([string]$RelativePath, [string]$ExpectedTrigger) {
    $triggers = @(Get-WorkflowTriggers $RelativePath)
    Assert-Condition ($triggers.Count -eq 1 -and $triggers[0] -ceq $ExpectedTrigger) `
        "$RelativePath must use only the $ExpectedTrigger trigger; found: $($triggers -join ', ')"
}

function Assert-WorkflowContains([string]$RelativePath, [string[]]$ExpectedValues) {
    $text = Read-Workflow $RelativePath
    foreach ($value in $ExpectedValues) {
        Assert-Condition ($text.IndexOf($value, [System.StringComparison]::Ordinal) -ge 0) `
            "$RelativePath must contain: $value"
    }
}

function Assert-WorkflowDoesNotContain([string]$RelativePath, [string[]]$ForbiddenValues) {
    $text = Read-Workflow $RelativePath
    foreach ($value in $ForbiddenValues) {
        Assert-Condition ($text.IndexOf($value, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) `
            "$RelativePath must not contain: $value"
    }
}

$contractWorkflow = '.github/workflows/harmonyos-contracts.yml'
$sdkWorkflow = '.github/workflows/harmonyos-sdk.yml'

Assert-WorkflowUsesFullActionShas $contractWorkflow
Assert-WorkflowUsesFullActionShas $sdkWorkflow
Assert-WorkflowHasOnlyTrigger $sdkWorkflow 'workflow_dispatch'
Assert-WorkflowContains $contractWorkflow @(
    'ubuntu-24.04',
    'permissions:',
    'contents: read',
    'push:',
    'pull_request:',
    'Test-HarmonyProject.ps1',
    'Test-HarmonyShell.ps1',
    'Test-HarmonyPackaging.ps1',
    'test-harmonyos-workflow-policy.ps1',
    'PluginPlatformTargetTest',
    'PluginCompatibilityEvaluatorTest',
    'PluginStoreManifestTest'
)
Assert-WorkflowContains $sdkWorkflow @(
    'self-hosted',
    'harmonyos-sdk',
    'arm64',
    'environment:',
    'harmonyos-packaging',
    'permissions:',
    'contents: read',
    'concurrency:',
    'signing_mode:',
    '- unsigned',
    '- debug',
    '- release',
    'Build-HarmonyPackage.ps1',
    'Aura-Launcher-27.1-next.jar',
    'actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a'
)
Assert-WorkflowDoesNotContain $contractWorkflow @(
    'secrets.',
    'hnpcli download',
    'DevEco',
    'upload-release-asset'
)
Assert-WorkflowDoesNotContain $sdkWorkflow @(
    'contents: write',
    'release create',
    'upload-release-asset',
    'gh release upload'
)

Write-Host 'Aura HarmonyOS workflow policy tests passed.'
