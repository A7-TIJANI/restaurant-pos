<#
 Boots the real "Epic it POS.exe" on an isolated data folder and checks it: it starts, answers /ping, serves the POS
 page, an owner session can close it from the app, and it then exits cleanly and removes port.txt. Prints how long the
 server side took (also appended to startup-times.log). Needs Windows. The data folder must not contain spaces
 (it is passed through JAVA_TOOL_OPTIONS). Opens no browser.

   .\packaging\smoke-test.ps1 -AppDir "target\dist\Epic it POS"
#>
param(
    [Parameter(Mandatory = $true)][string]$AppDir,
    [string]$DataDir = (Join-Path $env:SystemDrive "epicit-smoke"),
    [int]$TimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"
if ($DataDir -match '\s') { throw "-DataDir must not contain spaces: $DataDir" }
$exe = Join-Path (Resolve-Path $AppDir) "Epic it POS.exe"
if (-not (Test-Path $exe)) { throw "Not found: $exe" }

if (Test-Path $DataDir) { Remove-Item -Recurse -Force $DataDir }
New-Item -ItemType Directory -Path $DataDir | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Depicit.data-dir=$DataDir -Depicit.open-browser=false"

$proc = $null
try {
    $clock = [System.Diagnostics.Stopwatch]::StartNew()
    $proc = Start-Process -FilePath $exe -PassThru

    $portFile = Join-Path $DataDir "port.txt"
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while (-not (Test-Path $portFile)) {
        if ($proc.HasExited) { throw "The app exited early with code $($proc.ExitCode)." }
        if ((Get-Date) -gt $deadline) { throw "port.txt did not appear within $TimeoutSeconds seconds." }
        Start-Sleep -Milliseconds 250
    }
    $port = [int]((Get-Content $portFile -Raw).Trim())
    $base = "http://127.0.0.1:$port"
    Write-Host ("OK   started: port.txt after {0:N1} s (process start to listening)" -f $clock.Elapsed.TotalSeconds)

    $ping = (Invoke-WebRequest -Uri "$base/ping" -UseBasicParsing).Content.Trim()
    if ($ping -ne "epicit-pos") { throw "/ping answered '$ping'." }
    Write-Host "OK   /ping identifies Epic IT POS"

    $page = (Invoke-WebRequest -Uri "$base/" -UseBasicParsing).Content
    if ($page -notmatch 'id="invoice-wrap"') { throw "The POS page did not contain the receipt preview." }
    Write-Host "OK   the POS page is served"

    $web = $null
    Invoke-WebRequest -Uri "$base/api/auth/setup" -Method Post -ContentType "application/json" -Body '{"pin":"4821"}' -SessionVariable web -UseBasicParsing | Out-Null
    Invoke-WebRequest -Uri "$base/api/owner/app/close" -Method Post -WebSession $web -UseBasicParsing | Out-Null
    Write-Host "OK   owner asked the app to close"

    if (-not $proc.WaitForExit(30000)) { throw "The app did not exit within 30 seconds of the close request." }
    if ($proc.ExitCode -ne 0) { throw "The app exited with code $($proc.ExitCode)." }
    if (Test-Path $portFile) { throw "port.txt was left behind after a clean close." }
    Write-Host "OK   closed cleanly and removed port.txt"

    $timing = Join-Path $DataDir "startup-times.log"
    if (Test-Path $timing) { Write-Host "Startup timing:"; Get-Content $timing }
    Write-Host "SMOKE TEST PASSED"
}
catch {
    Write-Host "SMOKE TEST FAILED: $($_.Exception.Message)"
    $logFile = Join-Path $DataDir "logs\epicit-pos.log"
    if (Test-Path $logFile) { Write-Host "---- last lines of epicit-pos.log"; Get-Content $logFile -Tail 60 }
    exit 1
}
finally {
    Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
    if ($proc -and -not $proc.HasExited) { $proc.Kill() }
}
