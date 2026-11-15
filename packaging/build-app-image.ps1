<#
 Builds the portable Windows app-image (NFR-2): a folder with "Epic it POS.exe", the app and a bundled Java runtime.
 No installer, no WiX, no admin rights (jpackage --type app-image). Run it on Windows with a JDK 21 (Eclipse Temurin)
 as JAVA_HOME; the GitHub Actions release workflow runs exactly this script. jpackage cannot cross-compile, so a Windows
 app-image can only be built on Windows.

   .\packaging\build-app-image.ps1 -Version 2.0.0          # runs "mvn package" first
   .\packaging\build-app-image.ps1 -Version v2.0.1 -SkipMaven   # reuse target\epicit-pos.jar

 Output: target\dist\Epic it POS\   and   target\EpicIT-POS-<version>-windows-x64.zip
#>
param(
    [string]$Version = "2.0.0",
    [switch]$SkipMaven
)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

# jpackage wants 1 to 3 plain numbers: "v2.0.1-rc1" becomes "2.0.1"
$Version = ($Version -replace '^v', '') -replace '-.*$', ''
if ($Version -notmatch '^\d+(\.\d+){0,2}$') { throw "Version '$Version' is not like 2.0.0" }

if (-not $env:JAVA_HOME) { throw "JAVA_HOME must point to a JDK 21 (Eclipse Temurin)." }
$jpackage = Join-Path $env:JAVA_HOME "bin\jpackage.exe"
if (-not (Test-Path $jpackage)) { throw "jpackage.exe not found in $env:JAVA_HOME\bin. Use a JDK (not a JRE), version 21." }

if (-not $SkipMaven) {
    mvn -B -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "The Maven build failed." }
}
$jar = "target\epicit-pos.jar"
if (-not (Test-Path $jar)) { throw "$jar not found. Run 'mvn package' first (or leave out -SkipMaven)." }

# Fixed folders under target\ only: nothing here uses a wildcard delete.
$stage = "target\jpackage-input"
$dist = "target\dist"
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
if (Test-Path $dist) { Remove-Item -Recurse -Force $dist }
New-Item -ItemType Directory -Path $stage | Out-Null
Copy-Item $jar $stage

# The full JDK is bundled on purpose (context file section 8): a trimmed runtime has to list every module the app
# needs (java.sql, jdk.httpserver for the restore wizard, jdk.crypto.ec, ...) and a missing one only shows up in the field.
$jp = @(
    "--type", "app-image",
    "--name", "Epic it POS",
    "--app-version", $Version,
    "--vendor", "Epic IT",
    "--description", "Offline point of sale for restaurants",
    "--input", $stage,
    "--main-jar", "epicit-pos.jar",
    "--runtime-image", $env:JAVA_HOME,
    "--dest", $dist,
    "--java-options", "-Xmx512m",
    "--java-options", "-XX:+UseSerialGC",
    "--java-options", "-Dfile.encoding=UTF-8"
)
& $jpackage @jp
if ($LASTEXITCODE -ne 0) { throw "jpackage failed (exit code $LASTEXITCODE)." }

$appDir = Join-Path $dist "Epic it POS"
if (-not (Test-Path (Join-Path $appDir "Epic it POS.exe"))) { throw "jpackage did not produce 'Epic it POS.exe' in $appDir." }
Copy-Item "packaging\README-FIRST.txt" $appDir

$zip = "target\EpicIT-POS-$Version-windows-x64.zip"
if (Test-Path $zip) { Remove-Item -Force $zip }
tar -a -c -f $zip -C $dist "Epic it POS"
if ($LASTEXITCODE -ne 0) { throw "Could not create $zip." }

Write-Host "App image: $appDir"
Write-Host "Zip:       $zip"
if ($env:GITHUB_OUTPUT) {
    "zip=$zip" | Out-File -FilePath $env:GITHUB_OUTPUT -Append -Encoding utf8
    "appdir=$appDir" | Out-File -FilePath $env:GITHUB_OUTPUT -Append -Encoding utf8
}
