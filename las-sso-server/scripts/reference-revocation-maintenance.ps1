param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('replay', 'rotate-key', 'purge-completed')]
    [string]$Command,
    [Parameter(Mandatory = $true)]
    [string]$JarPath,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F]{64}$')]
    [string]$ExpectedSha256,
    [Parameter(Mandatory = $true)]
    [string]$ExpectedKeyId,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9._@-]{1,64}$')]
    [string]$Actor,
    [Parameter(Mandatory = $true)]
    [ValidateLength(1, 128)]
    [string]$Reason,
    [Guid]$TaskId,
    [ValidateRange(0, 2147483646)]
    [int]$ExpectedReplayCount = 0,
    [string]$NextKeyId
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'maintenance-artifact.ps1')
$jar = Resolve-LasSsoMaintenanceArtifact -JarPath $JarPath -ExpectedSha256 $ExpectedSha256
$arguments = @(
    '-jar', $jar, '--revocation-maintenance',
    '--spring.main.web-application-type=none',
    '--reference.revocation.worker-enabled=false',
    "--command=$Command", "--expected-key-id=$ExpectedKeyId",
    "--actor=$Actor", "--reason=$Reason"
)
if ($Command -eq 'replay') {
    if ($TaskId -eq [Guid]::Empty) { throw 'Replay requires an exact task UUID.' }
    $arguments += "--task-id=$TaskId", "--expected-replay-count=$ExpectedReplayCount"
}
if ($Command -eq 'rotate-key') {
    if (-not $NextKeyId) { throw 'Rotation requires the new key identifier; key material must be external configuration.' }
    $arguments += "--next-key-id=$NextKeyId"
}
& java @arguments
exit $LASTEXITCODE
