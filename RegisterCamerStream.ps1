# Virtual ONVIF camera registration for Windows WS-Discovery

# Define your camera properties
$cameraName = "Virtual ONVIF Camera"
$cameraId   = [guid]::NewGuid().ToString()
$rtspUrl    = "rtsp://camera1:camera12@192.168.1.100:554/stream1"

# Create a COM object for Function Discovery
# Note: FDResPub is exposed via "FDResPub.ResourcePublication"
$fdResPub = New-Object -ComObject "FDResPub.ResourcePublication"

# Register a new network video transmitter
# For simplicity, we're using basic property sets. You can add more as needed.
$properties = @{}
$properties["DeviceType"] = "dn:NetworkVideoTransmitter"
$properties["FriendlyName"] = $cameraName
$properties["EndpointReference"] = "urn:uuid:$cameraId"
$properties["XAddrs"] = $rtspUrl
$properties["MetadataVersion"] = "1"

# Publish the device
$fdResPub.PublishResource($properties)

Write-Host "Virtual ONVIF camera registered with Windows WS-Discovery:"
Write-Host "Name: $cameraName"
Write-Host "Endpoint: $rtspUrl"
Write-Host "UUID: $cameraId"

# Keep script running so device stays published
Write-Host "Press Ctrl+C to stop and unregister the device..."
while ($true) { Start-Sleep -Seconds 10 }
