<#
.SYNOPSIS
  Builds, signs and (optionally) publishes a Handoff release to GitHub Releases.

.DESCRIPTION
  1. Runs the tests and builds the release APK (signed with the Android release key), the
     Windows installers (MSI and setup EXE) and a portable Windows zip.
  2. Writes update.json, update.json.sig (signed with the update key) and SHA256SUMS.txt.
  3. With -Publish: tags v<version>, pushes the tag and creates the GitHub release.

  Keys stay on this machine, in %USERPROFILE%\.handoff-release (see CONTRIBUTING.md).
  Release notes are read from docs/releases/v<version>.md.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\release.ps1            # build and sign only
  powershell -ExecutionPolicy Bypass -File tools\release.ps1 -Publish   # also publish on GitHub
#>
param([switch]$Publish)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$keys = Join-Path $env:USERPROFILE '.handoff-release'

$version = (Select-String -Path "$root\desktop\build.gradle.kts" -Pattern 'val appVersion = "(.+)"').Matches[0].Groups[1].Value
$androidVersion = (Select-String -Path "$root\app\build.gradle.kts" -Pattern 'versionName = "(.+)"').Matches[0].Groups[1].Value
if ($version -ne $androidVersion) { throw "Version mismatch: desktop $version, Android $androidVersion" }
$notes = "$root\docs\releases\v$version.md"
if (-not (Test-Path $notes)) { throw "Missing release notes: $notes" }
foreach ($f in 'signing.properties', 'update-signing.pk8') {
    if (-not (Test-Path "$keys\$f")) { throw "Missing $keys\$f (see CONTRIBUTING.md, Releases)" }
}

Write-Host "Building Handoff $version..."
& "$root\gradlew.bat" -p $root --console=plain -q test lintDebug assembleRelease ':desktop:packageMsi' ':desktop:packageExe' ':desktop:createDistributable'
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }

$out = "$root\build\release\v$version"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $out | Out-Null

Copy-Item (Get-ChildItem "$root\app\build\outputs\apk\release\*.apk" | Select-Object -First 1).FullName "$out\Handoff-$version.apk"
Copy-Item "$root\desktop\build\compose\binaries\main\msi\Handoff-$version.msi" "$out\Handoff-$version.msi"
Copy-Item "$root\desktop\build\compose\binaries\main\exe\Handoff-$version.exe" "$out\Handoff-$version-setup.exe"
Compress-Archive -Path "$root\desktop\build\compose\binaries\main\app\Handoff" -DestinationPath "$out\Handoff-$version-windows-portable.zip"

& "$root\gradlew.bat" -p $root --console=plain -q ':core:releaseTool' "-PtoolArgs=sign,$out,$version,$notes,$keys\update-signing.pk8"
if ($LASTEXITCODE -ne 0) { throw 'Signing failed' }
Get-ChildItem $out | Format-Table Name, Length

if (-not $Publish) {
    Write-Host "Built and signed in $out. Run again with -Publish to create the GitHub release."
    return
}

$gh = if ($env:HANDOFF_GH) { $env:HANDOFF_GH } else { 'gh' }
git -C $root tag -a "v$version" -m "Handoff $version"
git -C $root push origin "v$version"
& $gh release create "v$version" (Get-ChildItem $out).FullName --repo FancyCorey/handoff --title "Handoff $version" --notes-file $notes
if ($LASTEXITCODE -ne 0) { throw 'Publishing failed' }
Write-Host "Published https://github.com/FancyCorey/handoff/releases/tag/v$version"
