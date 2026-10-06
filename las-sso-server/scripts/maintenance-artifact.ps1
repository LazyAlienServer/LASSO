# Shared artifact selection only; passwords remain in the application's hidden console input.
function Resolve-LasSsoMaintenanceArtifact {
    param(
        [Parameter(Mandatory = $true)]
        [string]$JarPath,
        [string]$ExpectedSha256
    )

    if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) {
        throw 'Application JAR not found. Build it first, then provide its exact path with -JarPath.'
    }
    $artifact = Get-Item -LiteralPath $JarPath -ErrorAction Stop
    if ($artifact.PSProvider.Name -ne 'FileSystem' -or $artifact.Extension -ne '.jar') {
        throw 'Maintenance artifact must be a filesystem JAR.'
    }
    $hash = (Get-FileHash -LiteralPath $artifact.FullName -Algorithm SHA256 -ErrorAction Stop).Hash
    if ($ExpectedSha256 -and $hash -ne $ExpectedSha256) {
        throw 'Maintenance JAR SHA-256 does not match the expected delivery hash; Java was not started.'
    }
    Write-Host "Maintenance artifact: $($artifact.FullName)"
    Write-Host "SHA-256: $hash"
    return $artifact.FullName
}
