param(
    [Parameter(Mandatory = $true)]
    [string]$FileKitRepository,
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [Parameter(Mandatory = $true)]
    [string]$SourceCommit,
    [Parameter(Mandatory = $true)]
    [string]$StandardGradleWrapper,
    [Parameter(Mandatory = $true)]
    [string]$CpfGradleWrapper,
    [Parameter(Mandatory = $true)]
    [string]$CpfPluginRepository,
    [Parameter(Mandatory = $true)]
    [string]$CpfDependencyRepository,
    [Parameter(Mandatory = $true)]
    [string]$KotlinNativeHome,
    [Parameter(Mandatory = $true)]
    [string]$MergeReport,
    [Parameter(Mandatory = $true)]
    [string]$JavaHome,
    [string]$StandardGradleUserHome,
    [string]$CpfGradleUserHome,
    [string]$StandardKotlinVersion = '2.4.20',
    [string]$AndroidGradlePluginVersion = '9.4.0',
    [string]$CpfKotlinVersion = '2.4.21-0.1.0-13'
)

$ErrorActionPreference = 'Stop'

if ($SourceCommit -notmatch '^[0-9a-fA-F]{40}$') {
    throw 'SourceCommit must be a full 40-character hexadecimal SHA.'
}

$expectedVersion = '0.16.0-cpf-unified-' + $SourceCommit.Substring(0, 12).ToLowerInvariant()
if ($Version -cne $expectedVersion) {
    throw "Version must be $expectedVersion."
}
if (-not (Test-Path -LiteralPath $KotlinNativeHome)) {
    throw 'KotlinNativeHome does not exist.'
}
if (-not (Test-Path -LiteralPath $MergeReport)) {
    throw 'MergeReport does not exist.'
}
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'JavaHome must contain bin/java.exe.'
}

$standardProject = Join-Path $PSScriptRoot 'standard'
$ohosProject = Join-Path $PSScriptRoot 'ohos'
$standardGradleUserHome = if ($StandardGradleUserHome) {
    $StandardGradleUserHome
} else {
    Join-Path $PSScriptRoot 'build/gradle-user-home-standard'
}
$cpfGradleUserHome = if ($CpfGradleUserHome) {
    $CpfGradleUserHome
} else {
    Join-Path $PSScriptRoot 'build/gradle-user-home-cpf'
}

$standardTasks = @(
    '-p', $standardProject,
    "-PfilekitRepository=$FileKitRepository",
    "-PfilekitVersion=$Version",
    "-PkotlinVersion=$StandardKotlinVersion",
    "-PagpVersion=$AndroidGradlePluginVersion",
    "-PfilekitMergeReport=$MergeReport",
    'verifyFileKitResolution',
    '--max-workers=1',
    '--console=plain'
)

$ohosTasks = @(
    '-p', $ohosProject,
    "-PfilekitRepository=$FileKitRepository",
    "-PfilekitVersion=$Version",
    "-PkotlinVersion=$CpfKotlinVersion",
    "-PcpfPluginRepository=$CpfPluginRepository",
    "-PcpfDependencyRepository=$CpfDependencyRepository",
    "-Pkotlin.native.home=$KotlinNativeHome",
    "-PfilekitMergeReport=$MergeReport",
    'verifyFileKitResolution',
    '--max-workers=1',
    '--console=plain'
)

$previousGradleUserHome = $env:GRADLE_USER_HOME
$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JavaHome
    $env:GRADLE_USER_HOME = $standardGradleUserHome
    & $StandardGradleWrapper @standardTasks
    if ($LASTEXITCODE -ne 0) {
        throw "Standard Android/iOS consumer compilation failed with exit code $LASTEXITCODE."
    }

    $env:GRADLE_USER_HOME = $cpfGradleUserHome
    & $CpfGradleWrapper @ohosTasks
    if ($LASTEXITCODE -ne 0) {
        throw "CPF OHOS consumer compilation failed with exit code $LASTEXITCODE."
    }
} finally {
    if ($null -eq $previousGradleUserHome) {
        Remove-Item Env:GRADLE_USER_HOME -ErrorAction SilentlyContinue
    } else {
        $env:GRADLE_USER_HOME = $previousGradleUserHome
    }
    if ($null -eq $previousJavaHome) {
        Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
    } else {
        $env:JAVA_HOME = $previousJavaHome
    }
}

Write-Output "Consumer compilation passed for $Version ($SourceCommit)."
