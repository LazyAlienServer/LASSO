param(
    [Parameter(Mandatory = $true, ParameterSetName = 'NewUser')]
    [string]$Username,

    [Parameter(Mandatory = $true, ParameterSetName = 'ExistingUser')]
    [Guid]$UserId,

    [string]$Profile = 'local'
)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $projectDirectory 'build\libs\las-sso-server-0.1.0-SNAPSHOT.jar'

if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw "Application JAR not found. Run .\gradlew.bat bootJar first."
}

$arguments = @(
    '-jar', $jar,
    '--spring.main.web-application-type=none',
    "--spring.profiles.active=$Profile",
    '--las.sso.maintenance.command=admin-bootstrap'
)

if ($PSCmdlet.ParameterSetName -eq 'NewUser') {
    $arguments += "--username=$Username"
} else {
    $arguments += "--user-id=$UserId"
}

& java @arguments
exit $LASTEXITCODE
