param(
    [Parameter(Mandatory = $true)][string]$SigningKeyPath,
    [Parameter(Mandatory = $true)][string]$ProtectedPasswordPath,
    [string]$KeyAlias = 'ciallo-reader-release',
    [ValidateRange(1536, 8192)][int]$HeapMiB = 3072
)
$ErrorActionPreference = 'Stop'
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw 'DPAPI signing credentials require the owning Windows account; other hosts should use the documented environment variables.' }
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to the project JDK before building.' }
if (-not (Test-Path -LiteralPath $SigningKeyPath -PathType Leaf)) { throw 'The private signing key is missing.' }
if (-not (Test-Path -LiteralPath $ProtectedPasswordPath -PathType Leaf)) { throw 'The DPAPI-protected password file is missing.' }
$protected = (Get-Content -LiteralPath $ProtectedPasswordPath -Raw).Trim() | ConvertTo-SecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($protected)
$names = @('KEYSTORE_PATH', 'KEY_ALIAS', 'STORE_PASSWORD', 'KEY_PASSWORD')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:KEYSTORE_PATH = (Resolve-Path -LiteralPath $SigningKeyPath).ProviderPath
    $env:KEY_ALIAS = $KeyAlias
    $env:STORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $env:KEY_PASSWORD = $env:STORE_PASSWORD
    $root = Split-Path -Parent $PSScriptRoot
    Push-Location -LiteralPath $root
    try {
        & "$root/gradlew.bat" :app:assembleRelease --offline --no-daemon --no-watch-fs --no-build-cache --max-workers=1 `
            "-Dorg.gradle.jvmargs=-Xmx${HeapMiB}m -Dfile.encoding=UTF-8" '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Secure release build failed; inspect the Gradle diagnostics.' }
    } finally { Pop-Location }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
