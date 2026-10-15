param(
    [string]$Profile = 'production',

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$JarPath,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F]{64}$')]
    [string]$ExpectedSha256
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'maintenance-artifact.ps1')
$jar = Resolve-LasSsoMaintenanceArtifact -JarPath $JarPath -ExpectedSha256 $ExpectedSha256

$arguments = @(
    '-jar', $jar,
    '--spring.main.web-application-type=none',
    "--spring.profiles.active=$Profile",
    '--las.sso.scheduling.enabled=false',
    '--spring.session.jdbc.cleanup-cron=-',
    '--las.sso.maintenance.command=admin-reset-password'
)

& java @arguments
exit $LASTEXITCODE
