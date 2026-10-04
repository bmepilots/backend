$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$dbRepo = Join-Path (Split-Path -Parent $repo) 'db'
$values = @{}
Get-Content -LiteralPath (Join-Path $dbRepo '.env') | ForEach-Object { if ($_ -match '^([A-Z_]+)=(.*)$') { $values[$matches[1]] = $matches[2] } }
$env:TEST_DB_PASSWORD = $values['MARIADB_PASSWORD']
if (!$env:JAVA_HOME) {
  $candidate = Join-Path $env:USERPROFILE '.jdks/ms-21.0.11'
  if (Test-Path $candidate) { $env:JAVA_HOME = $candidate } else { throw 'Set JAVA_HOME to a JDK 21 installation.' }
}
Push-Location $dbRepo
try {
  & docker compose -f compose.test.yml up -d --wait
  if ($LASTEXITCODE -ne 0) { throw 'Test database startup failed.' }
} finally { Pop-Location }
Push-Location $repo
try {
  & .\mvnw.cmd --no-transfer-progress verify
  if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed; inspect target/surefire-reports.' }
} finally { Pop-Location }
