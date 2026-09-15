param([switch]$DisableReviewDelivery)
$ErrorActionPreference = 'Stop'

$m5Repository = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$m5EnvironmentFile = Join-Path $m5Repository '.env'
$m5ConfigurationFile = Join-Path $m5Repository 'backend\src\main\resources\m5-backend.properties'
if (-not (Test-Path -LiteralPath $m5EnvironmentFile -PathType Leaf)) {
  throw "Missing $m5EnvironmentFile. Add ORACLE_JDBC_URL, ORACLE_USERNAME, ORACLE_PASSWORD and JWT_SECRET."
}
if (-not (Test-Path -LiteralPath $m5ConfigurationFile -PathType Leaf)) {
  throw "Missing project configuration: $m5ConfigurationFile"
}

$m5Allowed = @('ORACLE_JDBC_URL','ORACLE_USERNAME','ORACLE_PASSWORD','JWT_SECRET')
foreach ($m5Line in Get-Content -LiteralPath $m5EnvironmentFile) {
  if ($m5Line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$' -and $Matches[1] -in $m5Allowed) {
    $m5Name = $Matches[1]
    $m5Value = $Matches[2].Trim()
    if ($m5Value.Length -ge 2 -and (($m5Value.StartsWith('"') -and $m5Value.EndsWith('"')) -or
        ($m5Value.StartsWith("'") -and $m5Value.EndsWith("'")))) {
      $m5Value = $m5Value.Substring(1,$m5Value.Length-2)
    }
    [Environment]::SetEnvironmentVariable($m5Name,$m5Value,'Process')
  }
}
foreach ($m5Name in $m5Allowed) {
  if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($m5Name,'Process'))) {
    throw "$m5Name is missing or empty in $m5EnvironmentFile"
  }
}
if ([Text.Encoding]::UTF8.GetByteCount($env:JWT_SECRET) -lt 32) {
  throw 'JWT_SECRET must contain at least 32 UTF-8 bytes.'
}

$m5Arguments = @(
  '-B','-ntp','-f',(Join-Path $m5Repository 'backend\pom.xml'),
  '-Dstart-class=com.fluxpay.config.M5BackendApplication',
  '-Dspring-boot.run.profiles=m5-m3-integration'
)
if ($DisableReviewDelivery) {
  $m5Arguments += '-Dspring-boot.run.arguments=--m5.review-delivery.enabled=false'
}
$m5Arguments += 'spring-boot:run'

Write-Host 'Starting M5BackendApplication at http://127.0.0.1:8082'
Write-Host 'Configuration: backend/src/main/resources/m5-backend.properties'
Write-Host 'Secrets: .env (values hidden)'
& (Join-Path $m5Repository 'mvnw.cmd') @m5Arguments
exit $LASTEXITCODE
