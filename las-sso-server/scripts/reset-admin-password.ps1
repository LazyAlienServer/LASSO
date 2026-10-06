param(
    [string]$Profile = 'local',

    [ValidateNotNullOrEmpty()]
    [string]$JarPath = (Join-Path $PSScriptRoot '..\build\libs\las-sso-server-0.1.0-SNAPSHOT.jar'),

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
    '--las.sso.maintenance.command=admin-reset-password'
)

& java @arguments
exit $LASTEXITCODE
