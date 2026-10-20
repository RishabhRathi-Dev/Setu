# Forward all options to the portable launcher. Example: ./scripts/run-jfr.ps1 --storage-mode FILE
$ErrorActionPreference = 'Stop'
& python (Join-Path $PSScriptRoot 'run-jfr.py') @args
exit $LASTEXITCODE
