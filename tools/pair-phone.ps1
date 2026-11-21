# Pairs the phone for wireless debugging. On the phone, open
# Developer options > Wireless debugging > Pair device with pairing code,
# keep that pop-up open, then run:
#   powershell -ExecutionPolicy Bypass -File tools\pair-phone.ps1
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"

Write-Host "Looking for the phone's pairing pop-up (keep it open on the phone)..."
$service = $null
for ($i = 0; $i -lt 10 -and -not $service; $i++) {
    $service = & $adb mdns services | Select-String "_adb-tls-pairing\._tcp" | Select-Object -First 1
    if (-not $service) { Start-Sleep -Seconds 1 }
}
if ($service) {
    $address = ($service.Line -split '\s+')[2]
    Write-Host ""
    Write-Host "Found the phone's pairing port: $address"
} else {
    # mDNS can fail, e.g. when the PC is on Ethernet and the router doesn't pass it to Wi-Fi.
    Write-Host ""
    Write-Host "Couldn't find it automatically."
    $address = (Read-Host "Type the 'IP address & Port' shown in the phone's pairing pop-up (e.g. 192.168.1.11:41234)").Trim()
    if ($address -notmatch '^\d{1,3}(\.\d{1,3}){3}:\d+$') {
        Write-Host "That doesn't look like IP:port. On the phone tap 'Pair device with pairing code', keep the pop-up open, and run this again."
        exit 1
    }
}
Write-Host "Now type the 6-digit code shown on the phone and press Enter."
Write-Host ""
& $adb pair $address
