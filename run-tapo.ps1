Get-Command -Module CredentialManager


$targetName = "KlemmTapoCamera"   # Change only if you want
$credFile = "$env:USERPROFILE\.tapoCred"

if (!(Test-Path $credFile)) {
    $cred = Get-Credential
    $cred | Export-Clixml $credFile
}

$cred = Import-Clixml $credFile
$password = $cred.GetNetworkCredential().Password

# === Launch your Java app (exactly the same as before) ===
$javaArgs = @(
    "--enable-native-access=ALL-UNNAMED",
    "-splash:splash.png",
    "-Dfile.encoding=UTF-8",
    "-Dstdout.encoding=UTF-8",
    "-Dstderr.encoding=UTF-8",
    "-classpath", ".\target\classes;.\target\lib\*",
    "-XX:+ShowCodeDetailsInExceptionMessages",
    "klemm.technology.camera.SubnetScanner",
    "/s", $password
)

Write-Host "Launching Tapo Camera app..." -ForegroundColor Cyan
Start-Process javaw.exe -ArgumentList $javaArgs -NoNewWindow