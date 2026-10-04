param([switch]$Test, [switch]$Bootstrap)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
if ($Test) { & (Join-Path $PSScriptRoot 'test.ps1'); return }
$dbEnv = Join-Path (Split-Path -Parent $repo) 'db/.env'
if (!(Test-Path $dbEnv)) { throw 'First run ../db/scripts/setup.ps1 and start Docker MariaDB.' }
$values = @{}
Get-Content -LiteralPath $dbEnv | ForEach-Object { if ($_ -match '^([A-Z_]+)=(.*)$') { $values[$matches[1]] = $matches[2] } }
$env:DB_USER = $values['MARIADB_USER']
$env:DB_PASSWORD = $values['MARIADB_PASSWORD']
$env:DB_URL = "jdbc:mariadb://127.0.0.1:$($values['DB_PORT'])/$($values['MARIADB_DATABASE'])"
$mailEnv = Join-Path $repo '.env.mail.local'
if (Test-Path -LiteralPath $mailEnv) {
  $allowedMailKeys = @('MAIL_ENABLED', 'MAIL_USERNAME', 'MAIL_PASSWORD_FILE', 'MAIL_STORAGE', 'MAIL_INTERVAL_MS', 'MAIL_INITIAL_DAYS')
  Get-Content -LiteralPath $mailEnv | ForEach-Object {
    if ($_ -match '^([A-Z_]+)=(.*)$' -and $matches[1] -in $allowedMailKeys) {
      $key = $matches[1]
      $value = $matches[2].Trim()
      if ($key -in @('MAIL_PASSWORD_FILE', 'MAIL_STORAGE') -and $value -and ![IO.Path]::IsPathRooted($value)) {
        $value = Join-Path $repo $value
      }
      # Explicit process environment overrides local defaults (e.g. MAIL_ENABLED=false).
      if ([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($key, 'Process'))) {
        [Environment]::SetEnvironmentVariable($key, $value, 'Process')
      }
    }
  }
}
if ($Bootstrap) {
  $adminFile = Join-Path $repo '.local-admin.env'
  if (!(Test-Path $adminFile)) {
    $adminSecret = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(20)).ToLowerInvariant()
    @('BOOTSTRAP_ADMIN_EMAIL=admin@bmepilots2026.local', "BOOTSTRAP_ADMIN_PASSWORD=$adminSecret") | Set-Content -LiteralPath $adminFile -Encoding utf8
    Write-Host 'Created .local-admin.env with a random local admin password. Do not commit or share it.'
  }
  Get-Content -LiteralPath $adminFile | ForEach-Object { if ($_ -match '^(BOOTSTRAP_ADMIN_[A-Z]+)=(.*)$') { [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process') } }
}
if (!$env:JAVA_HOME) {
  $candidate = Join-Path $env:USERPROFILE '.jdks/ms-21.0.11'
  if (Test-Path $candidate) { $env:JAVA_HOME = $candidate }
  else { throw 'Set JAVA_HOME to a JDK 21 installation.' }
}
Push-Location $repo
try {
  & .\mvnw.cmd --no-transfer-progress spring-boot:run
  if ($LASTEXITCODE -ne 0) { throw "Maven exited with code $LASTEXITCODE" }
} finally { Pop-Location }
