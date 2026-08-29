$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$sourceEntrypoint = Join-Path $projectRoot 'hnp/bin/aura-launcher'
$bashCandidates = if ($env:OS -ceq 'Windows_NT') {
    @(
        'C:\Program Files\Git\bin\bash.exe',
        'C:\Program Files\Git\usr\bin\bash.exe'
    )
} else {
    @('/usr/bin/bash', '/bin/bash')
}
$bash = $bashCandidates | Where-Object {
    Test-Path -LiteralPath $_ -PathType Leaf
} | Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($bash)) {
    throw 'Bash is required to test the HarmonyOS shell entrypoint'
}

function Assert-Condition([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw $Message
    }
}

function Convert-ToBashPath([string]$Path) {
    if ($env:OS -cne 'Windows_NT') {
        return [System.IO.Path]::GetFullPath($Path)
    }
    $converted = & $bash -lc 'cygpath -u -- "$1"' aura-test $Path
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($converted)) {
        throw "Unable to convert test path for Git Bash: $Path"
    }
    return [string]$converted
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

function Invoke-Entrypoint([string]$Entrypoint, [string]$FakeBin, [string]$UntrustedArgument) {
    $entrypointUnix = Convert-ToBashPath $Entrypoint
    $fakeBinUnix = Convert-ToBashPath $FakeBin
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $records = @(& $bash -lc `
            'PATH="$1"; export PATH; exec /usr/bin/sh "$2" "$3"' `
            aura-test $fakeBinUnix $entrypointUnix $UntrustedArgument 2>&1)
        $exitCode = $LASTEXITCODE
        $output = ($records | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    return [pscustomobject]@{ ExitCode = $exitCode; Output = $output }
}

$temporary = Join-Path ([System.IO.Path]::GetTempPath()) `
    ('aura-harmony-shell-test-' + [guid]::NewGuid().ToString('N'))
[void](New-Item -ItemType Directory -Path $temporary)
try {
    $hnpRoot = Join-Path $temporary 'hnp'
    $bin = Join-Path $hnpRoot 'bin'
    $share = Join-Path $hnpRoot 'share/aura'
    $fakeBin = Join-Path $temporary 'fake-bin'
    [void](New-Item -ItemType Directory -Path $bin, $share, $fakeBin)

    $entrypoint = Join-Path $bin 'aura-launcher'
    Copy-Item -LiteralPath $sourceEntrypoint -Destination $entrypoint
    [System.IO.File]::WriteAllBytes(
        (Join-Path $share 'Aura-Launcher-27.1-next.jar'),
        [byte[]](0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    )

    $toolWrappers = @('readlink', 'dirname', 'sed')
    foreach ($toolName in $toolWrappers) {
        $toolPath = Join-Path $fakeBin $toolName
        Write-Utf8NoBom $toolPath "#!/bin/sh`nexec /usr/bin/$toolName `"`$@`"`n"
    }
    $entrypointUnix = Convert-ToBashPath $entrypoint
    $wrapperPaths = @($toolWrappers | ForEach-Object {
        Convert-ToBashPath (Join-Path $fakeBin $_)
    })
    & $bash -lc 'chmod 755 "$@"' aura-test $entrypointUnix @wrapperPaths
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to mark shell fixtures executable'
    }

    $missing = Invoke-Entrypoint $entrypoint $fakeBin 'must-not-reach-java'
    Assert-Condition ($missing.ExitCode -eq 69) 'Missing Java must exit 69'
    Assert-Condition ($missing.Output.Contains('BiShengJDK17-OH is required')) `
        'Missing Java diagnostic is absent'

    $capture = Join-Path $temporary 'java-arguments.txt'
    $env:AURA_TEST_JAVA_ARGS = Convert-ToBashPath $capture
    $java = Join-Path $fakeBin 'java'
    Write-Utf8NoBom $java @'
#!/bin/sh
if [ "$#" -eq 1 ] && [ "$1" = "-version" ]; then
  echo "openjdk version \"$AURA_TEST_JAVA_VERSION.0.0\"" >&2
  exit 0
fi
printf '%s\n' "$@" > "$AURA_TEST_JAVA_ARGS"
'@
    $javaUnix = Convert-ToBashPath $java
    & $bash -lc 'chmod 755 "$1"' aura-test $javaUnix
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to mark fake Java executable'
    }

    $env:AURA_TEST_JAVA_VERSION = '16'
    $java16 = Invoke-Entrypoint $entrypoint $fakeBin 'must-not-reach-java'
    Assert-Condition ($java16.ExitCode -eq 69) 'Java 16 must exit 69'
    Assert-Condition ($java16.Output.Contains('BiSheng JDK 17 or later is required')) `
        'Java 16 diagnostic is absent'
    Assert-Condition (-not (Test-Path -LiteralPath $capture)) 'Java 16 must not launch Aura Launcher'

    $env:AURA_TEST_JAVA_VERSION = '17'
    $java17 = Invoke-Entrypoint $entrypoint $fakeBin 'must-not-reach-java'
    Assert-Condition ($java17.ExitCode -eq 0) 'Java 17 launch fixture must succeed'
    $arguments = @(Get-Content -LiteralPath $capture)
    $expectedJar = (Convert-ToBashPath $hnpRoot) + '/share/aura/Aura-Launcher-27.1-next.jar'
    Assert-Condition ($arguments.Count -eq 2) 'Aura entrypoint must pass exactly two Java arguments'
    Assert-Condition ($arguments[0] -ceq '-jar') 'Aura entrypoint must use the -jar option'
    Assert-Condition ($arguments[1] -ceq $expectedJar) `
        "Aura entrypoint used an unexpected JAR path: $($arguments[1])"
    Assert-Condition ('must-not-reach-java' -cnotin $arguments) `
        'Aura entrypoint must not forward caller-controlled arguments'

    Write-Host 'Aura HarmonyOS shell entrypoint tests passed.'
} finally {
    Remove-Item Env:AURA_TEST_JAVA_ARGS -ErrorAction SilentlyContinue
    Remove-Item Env:AURA_TEST_JAVA_VERSION -ErrorAction SilentlyContinue
    Remove-ValidatedTemporaryDirectory $temporary
}
