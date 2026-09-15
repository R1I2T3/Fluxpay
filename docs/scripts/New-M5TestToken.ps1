param([Parameter(Mandatory=$true)][ValidateSet('admin','sender','pending')][string]$Account)
$ErrorActionPreference = 'Stop'
# LOCAL DEVELOPMENT ONLY. Fixed synthetic identities from m5-backend-test-data.sql.
# This is not an authentication endpoint. Never use a production signing secret here.
if ([string]::IsNullOrWhiteSpace($env:JWT_SECRET)) {
  $m5Repository = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
  $m5EnvironmentFile = Join-Path $m5Repository '.env'
  if (Test-Path -LiteralPath $m5EnvironmentFile -PathType Leaf) {
    foreach ($m5Line in Get-Content -LiteralPath $m5EnvironmentFile) {
      if ($m5Line -match '^\s*JWT_SECRET\s*=\s*(.*)$') {
        $m5Value = $Matches[1].Trim()
        if ($m5Value.Length -ge 2 -and (($m5Value.StartsWith('"') -and $m5Value.EndsWith('"')) -or
            ($m5Value.StartsWith("'") -and $m5Value.EndsWith("'")))) {
          $m5Value = $m5Value.Substring(1,$m5Value.Length-2)
        }
        [Environment]::SetEnvironmentVariable('JWT_SECRET',$m5Value,'Process')
        break
      }
    }
  }
}
if ([string]::IsNullOrWhiteSpace($env:JWT_SECRET)) { throw 'Set the same private JWT_SECRET used by the local backend.' }
$m5Key = [Text.Encoding]::UTF8.GetBytes($env:JWT_SECRET)
if ($m5Key.Length -lt 32) { throw 'JWT_SECRET must contain at least 32 UTF-8 bytes.' }
$m5Accounts = @{
  admin = @('5f000000-0000-0000-0000-000000000001','m5.admin@example.invalid','ADMIN')
  sender = @('5f000000-0000-0000-0000-000000000003','m5.sender@example.invalid','USER')
  pending = @('5f000000-0000-0000-0000-000000000004','m5.pending@example.invalid','USER')
}
function ConvertTo-M5Base64Url([byte[]]$Bytes) {
  return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
}
$m5Now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$m5Identity = $m5Accounts[$Account]
$m5Payload = @{sub=$m5Identity[0];email=$m5Identity[1];role=$m5Identity[2];iat=$m5Now;exp=($m5Now+3600)} | ConvertTo-Json -Compress
$m5Header = ConvertTo-M5Base64Url ([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}'))
$m5Body = ConvertTo-M5Base64Url ([Text.Encoding]::UTF8.GetBytes($m5Payload))
$m5Unsigned = "$m5Header.$m5Body"
$m5Hmac = New-Object Security.Cryptography.HMACSHA256
try {
  $m5Hmac.Key = $m5Key
  $m5Signature = ConvertTo-M5Base64Url ($m5Hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($m5Unsigned)))
  "$m5Unsigned.$m5Signature"
} finally {
  $m5Hmac.Dispose()
  [Array]::Clear($m5Key,0,$m5Key.Length)
}
