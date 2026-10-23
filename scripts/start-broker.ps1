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
$cachePath = Join-Path (Split-Path -Parent $jarPath) 'setu-server.aot'
if ($brokerArguments.Count -gt 0 -and $brokerArguments[0] -eq '--train-aot') {
    if ($brokerArguments.Count -ne 1) { throw '--train-aot does not accept broker configuration arguments' }
    $trainingCachePath = "$cachePath.training"
    & $javaExecutable @jvmArguments "-XX:AOTCacheOutput=$trainingCachePath" '-jar' $jarPath '--aot-train'
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    if (-not (Test-Path -LiteralPath $trainingCachePath)) { throw 'The JVM did not generate the AOT cache' }
    Move-Item -LiteralPath $trainingCachePath -Destination $cachePath -Force
    Write-Host "AOT cache ready: $cachePath"
    exit 0
}
if ($brokerArguments.Count -gt 0 -and $brokerArguments[0] -eq '--no-aot') {
    $brokerArguments = @($brokerArguments | Select-Object -Skip 1)
} elseif (Test-Path -LiteralPath $cachePath) {
    $jvmArguments += @('-XX:AOTMode=on', "-XX:AOTCache=$cachePath")
    Write-Host "Using AOT cache: $cachePath"
}
& $javaExecutable @jvmArguments '-jar' $jarPath @brokerArguments
exit $LASTEXITCODE
