param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$ExpectedEpoch,

    [Parameter(Mandatory = $true)]
    [ValidateLength(1, 128)]
    [string]$Reason,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$JarPath,

    [ValidatePattern('^[0-9a-fA-F]{64}$')]
    [string]$ExpectedSha256,

    [string]$Profile = 'production'
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
    '--las.sso.maintenance.command=rotate-security-epoch-after-restore',
    "--expected-epoch=$ExpectedEpoch",
    "--reason=$Reason"
)
& java @arguments
exit $LASTEXITCODE
