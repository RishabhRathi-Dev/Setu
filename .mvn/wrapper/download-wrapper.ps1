$ErrorActionPreference = 'Stop'
$downloadUri = $env:WRAPPER_URL.Trim('"')
$destination = $env:WRAPPER_JAR.Trim('"')
$temporary = $destination + '.tmp.' + [Guid]::NewGuid().ToString('N')
$client = New-Object System.Net.WebClient
try {
    if ($env:MVNW_USERNAME -or $env:MVNW_PASSWORD) {
        $client.Credentials = New-Object System.Net.NetworkCredential($env:MVNW_USERNAME, $env:MVNW_PASSWORD)
    }
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        try {
            $client.DownloadFile($downloadUri, $temporary)
            if ((Get-Item -LiteralPath $temporary).Length -eq 0) {
                throw 'The Maven wrapper download was empty.'
            }
            Move-Item -LiteralPath $temporary -Destination $destination -Force
            exit 0
        } catch {
            $failure = $_.Exception
            while ($failure -and $failure -isnot [Net.WebException]) { $failure = $failure.InnerException }
            $status = 0
            if ($failure -and $failure.Response) { $status = [int] $failure.Response.StatusCode }
            $retryable = $failure -and (($status -in @(429, 500, 502, 503, 504)) -or
                ($failure.Status -in @([Net.WebExceptionStatus]::Timeout, [Net.WebExceptionStatus]::ConnectFailure,
                    [Net.WebExceptionStatus]::ConnectionClosed, [Net.WebExceptionStatus]::ReceiveFailure,
                    [Net.WebExceptionStatus]::SendFailure, [Net.WebExceptionStatus]::NameResolutionFailure)))
            if (-not $retryable -or $attempt -eq 5) {
                [Console]::Error.WriteLine("Maven wrapper download failed after $attempt attempt(s) (HTTP $status). Maven was not started.")
                exit 1
            }
            $delay = [int] [Math]::Pow(2, $attempt - 1)
            if ($failure.Response) {
                $retryAfter = $failure.Response.Headers['Retry-After']
                $seconds = 0
                $date = [DateTimeOffset]::MinValue
                if ([int]::TryParse($retryAfter, [ref] $seconds)) {
                    $delay = [Math]::Max(0, [Math]::Min(30, $seconds))
                } elseif ([DateTimeOffset]::TryParse($retryAfter, [ref] $date)) {
                    $delay = [Math]::Max(0, [Math]::Min(30, [Math]::Ceiling(($date - [DateTimeOffset]::UtcNow).TotalSeconds)))
                }
            }
            Write-Warning "Maven wrapper download attempt $attempt failed (HTTP $status); retrying in $delay second(s)."
            Start-Sleep -Seconds $delay
        }
    }
} finally {
    $client.Dispose()
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
}
