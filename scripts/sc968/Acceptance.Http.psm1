Set-StrictMode -Version Latest

# Windows PowerShell 5.1 does not auto-load System.Net.Http, so [System.Net.Http.HttpClient]
# resolves only after the assembly is loaded explicitly. Without this every Invoke-Sc968Http call
# throws "Unable to find type" before a request is ever sent.
Add-Type -AssemblyName System.Net.Http

function Get-Sc968Header([object] $Response, [string] $Name) {
  foreach ($key in $Response.Headers.Keys) {
    if ([string]::Equals([string]$key, $Name, [StringComparison]::OrdinalIgnoreCase)) {
      return [string]$Response.Headers[$key]
    }
  }
  return $null
}

function Invoke-Sc968Http {
  param(
    [Parameter(Mandatory=$true)][string] $BaseUrl,
    [Parameter(Mandatory=$true)][string] $Path,
    [ValidateSet('GET','POST','PUT')][string] $Method = 'GET',
    [string] $BearerToken,
    [string] $Body,
    [string] $IdempotencyKey
  )
  $client = [System.Net.Http.HttpClient]::new()
  $request = $null
  $response = $null
  try {
    $uri = ([Uri]::new($BaseUrl.TrimEnd('/') + '/' + $Path.TrimStart('/'))).AbsoluteUri
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), $uri)
    if ($BearerToken) { $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $BearerToken) }
    if ($IdempotencyKey) { $request.Headers.Add('Idempotency-Key', $IdempotencyKey) }
    # An unbound [string] parameter is '' rather than $null, so a null check attaches an empty body
    # to every GET and .NET rejects it with "Cannot send a content-body with this verb-type."
    if (-not [string]::IsNullOrEmpty($Body)) {
      $request.Content = [System.Net.Http.StringContent]::new($Body, [Text.Encoding]::UTF8, 'application/json')
    }
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $headers = @{}
    foreach ($header in $response.Headers) { $headers[$header.Key] = ($header.Value -join ',') }
    foreach ($header in $response.Content.Headers) { $headers[$header.Key] = ($header.Value -join ',') }
    $text = [Text.Encoding]::UTF8.GetString($bytes)
    $json = $null
    if ($bytes.Length -gt 0) { try { $json = $text | ConvertFrom-Json -Depth 80 } catch { $json = $null } }
    return [pscustomobject]@{ StatusCode = [int]$response.StatusCode; BodyBytes = $bytes; BodyText = $text; Json = $json; Headers = $headers }
  } finally {
    if ($response) { $response.Dispose() }
    if ($request) { $request.Dispose() }
    $client.Dispose()
  }
}

function Assert-Sc968HttpSuccess([object] $Response, [string] $Operation) {
  if ($Response.StatusCode -lt 200 -or $Response.StatusCode -ge 300) {
    throw "$Operation failed with HTTP $($Response.StatusCode)"
  }
  return $Response
}

function Wait-Sc968Http {
  param(
    [Parameter(Mandatory=$true)][string] $BaseUrl,
    [Parameter(Mandatory=$true)][string] $Path,
    [string] $BearerToken,
    [int[]] $AcceptedStatusCodes = @(200),
    [int] $TimeoutSeconds = 120,
    [int] $PollMilliseconds = 500
  )
  $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
  # Keep the last failure so a timeout reports why every attempt failed. Swallowing it silently
  # makes a client-side fault (an unloadable type, a bad URI) indistinguishable from a service that
  # is merely slow to start.
  $lastFailure = 'no response was ever accepted'
  do {
    try {
      $response = Invoke-Sc968Http -BaseUrl $BaseUrl -Path $Path -BearerToken $BearerToken
      if ($AcceptedStatusCodes -contains $response.StatusCode) { return $response }
      $lastFailure = "last status $($response.StatusCode), expected $($AcceptedStatusCodes -join ',')"
    } catch {
      $lastFailure = "last error: $($_.Exception.Message)"
    }
    Start-Sleep -Milliseconds $PollMilliseconds
  } while ([DateTimeOffset]::UtcNow -lt $deadline)
  throw "Timed out waiting for $BaseUrl$Path ($lastFailure)"
}

Export-ModuleMember -Function Get-Sc968Header,Invoke-Sc968Http,Assert-Sc968HttpSuccess,Wait-Sc968Http
