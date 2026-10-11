<#
.SYNOPSIS
  Copies the SpotMute sources into a freshly generated React Native project and patches
  AndroidManifest.xml + MainApplication.kt.  Safe to run more than once.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\apply-overlay.ps1 -ProjectPath C:\dev\SpotMute
#>
param(
    [Parameter(Mandatory = $true)][string]$ProjectPath
)

$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)   # UTF-8 without BOM
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectPath = (Resolve-Path $ProjectPath).Path

$main = Join-Path $ProjectPath 'android\app\src\main'
$manifestPath = Join-Path $main 'AndroidManifest.xml'
if (-not (Test-Path $manifestPath)) {
    throw "This does not look like a React Native project root (no $manifestPath)."
}

# 1. Find MainApplication.kt and read the Android package name from it
$app = Get-ChildItem -Path (Join-Path $main 'java') -Recurse -Filter 'MainApplication.kt' | Select-Object -First 1
if (-not $app) {
    throw 'MainApplication.kt not found. This script expects the Kotlin template - see "Manual steps" in the README.'
}
$appText = [IO.File]::ReadAllText($app.FullName)
$pkg = [regex]::Match($appText, '(?m)^\s*package\s+([\w.]+)').Groups[1].Value
if (-not $pkg) { throw 'Could not read the package name from MainApplication.kt.' }
Write-Host "Android package : $pkg"
Write-Host "Kotlin folder   : $($app.DirectoryName)"

# 2. Copy Kotlin sources, rewriting the package line if your package differs
Get-ChildItem -Path (Join-Path $here 'android-kotlin') -Filter '*.kt' | ForEach-Object {
    $src = [IO.File]::ReadAllText($_.FullName)
    $src = [regex]::Replace($src, '(?m)^package\s+[\w.]+', "package $pkg", 1)
    [IO.File]::WriteAllText((Join-Path $app.DirectoryName $_.Name), $src, $utf8)
    Write-Host "  copied $($_.Name)"
}

# 3. Copy the UI
Copy-Item (Join-Path $here 'App.tsx') (Join-Path $ProjectPath 'App.tsx') -Force
Write-Host '  copied App.tsx'
if (Test-Path (Join-Path $ProjectPath 'App.js')) {
    Write-Warning 'App.js also exists. Delete it so index.js picks up App.tsx.'
}

# 4. Declare the notification-listener service in the manifest
$manifest = [IO.File]::ReadAllText($manifestPath)
if ($manifest -notmatch 'SpotMuteListenerService') {
    $service = @"

        <service
            android:name="${pkg}.SpotMuteListenerService"
            android:label="SpotMute"
            android:exported="true"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>
"@
    $manifest = $manifest.Replace('</application>', $service + "`r`n    </application>")
    [IO.File]::WriteAllText($manifestPath, $manifest, $utf8)
    Write-Host '  patched AndroidManifest.xml'
} else {
    Write-Host '  AndroidManifest.xml already patched'
}

# 5. Register the React Native package in MainApplication.kt
if ($appText -notmatch 'SpotMutePackage') {
    $re = [regex]::new('PackageList\(this\)\.packages\.apply\s*\{')
    if ($re.IsMatch($appText)) {
        $appText = $re.Replace($appText, ('$0' + "`n              add(SpotMutePackage())"), 1)
        [IO.File]::WriteAllText($app.FullName, $appText, $utf8)
        Write-Host '  patched MainApplication.kt'
    } else {
        Write-Warning 'Could not find "PackageList(this).packages.apply {" in MainApplication.kt.'
        Write-Warning 'Add  add(SpotMutePackage())  to your package list by hand (README > Manual steps).'
    }
} else {
    Write-Host '  MainApplication.kt already patched'
}

Write-Host ''
Write-Host 'Done. Next:  cd android ; .\gradlew assembleRelease'
