$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $repoRoot 'setu-server/target/setu-server-1.0.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jarPath)) {
    $packaged = @(Get-ChildItem -LiteralPath $repoRoot -Filter 'setu-server-*.jar')
    if ($packaged.Count -eq 1) { $jarPath = $packaged[0].FullName }
}
if (-not (Test-Path -LiteralPath $jarPath)) {
    throw 'Build the broker first: ./mvnw.cmd -pl setu-server -am package'
}
$javaExecutable = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$jvmArguments = @("@$(Join-Path $repoRoot 'config/jvm.options')")
$brokerArguments = @($args)
& $javaExecutable @jvmArguments '-jar' $jarPath @brokerArguments
exit $LASTEXITCODE
