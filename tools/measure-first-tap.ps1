# Measures how long a first tap takes to be handled, and what the main thread is
# doing while it waits.
#
# gfxinfo's "janky frames" is the wrong metric here: it counts frames the app is
# already drawing, and a tap that the main thread cannot pick up produces *no*
# frames at all. What matters is the gap between the input event and the app's
# response, plus where the main thread spent its time -- so this collects a method
# trace as well as frame statistics.
param(
    [string]$Package = "pi.kit.mob",
    [int]$X = 400,
    [int]$Y = 2040,
    [int]$SettleSeconds = 16
)

$ErrorActionPreference = "Continue"
$traceFile = Join-Path $env:TEMP "pikit-first-tap.trace"

Write-Output "=== cold start"
adb shell am force-stop $Package | Out-Null
Start-Sleep -Seconds 2
adb shell am start -n "$Package/pi.kit.mob.ui.MainActivity" | Out-Null
Start-Sleep -Seconds $SettleSeconds
adb shell input keyevent 111 | Out-Null
Start-Sleep -Seconds 2

$size = adb shell wm size
Write-Output "  $size"

Write-Output "=== method trace + tap"
adb shell am profile start --sampling 1000 $Package /data/local/tmp/pikit.trace | Out-Null
adb logcat -c

# Mark the moment on the device clock so the trace can be aligned with it.
$deviceStart = (adb shell date +%s%N).Trim()
adb shell input tap $X $Y | Out-Null
Start-Sleep -Milliseconds 1500
$deviceEnd = (adb shell date +%s%N).Trim()

adb shell am profile stop $Package | Out-Null
Start-Sleep -Seconds 2

Write-Output "=== input dispatch latency"
adb logcat -d -v threadtime 2>&1 |
    Select-String -Pattern "InputDispatcher|InputReader|Choreographer|ActivityManager.*pi\.kit\.mob" |
    Select-Object -Last 12 | ForEach-Object { "  " + $_.Line.Trim() }

Write-Output ""
Write-Output "=== frame phase timings for the tap window"
# Each row is one frame: DRAW START, QUEUE, and the four phases. A tap that blocks
# the main thread shows up as a large gap between the SYSTEM_TIME rows rather than
# as a slow phase.
$stats = adb shell dumpsys gfxinfo $Package framestats 2>&1
$inWindow = $false
$slow = @()
foreach ($line in $stats) {
    if ($line -match "---PROFILEDATA---") { $inWindow = -not $inWindow; continue }
    if (-not $inWindow) { continue }
    if ($line -notmatch "^\s*\d") { continue }
    $p = $line.Trim() -split ","
    if ($p.Count -lt 16) { continue }
    $intended = [double]$p[1]
    $completed = [double]$p[2]
    $total = ($completed - $intended) / 1000000.0
    if ($total -gt 16) {
        $slow += [pscustomobject]@{
            TotalMs  = [math]::Round($total, 1)
            DrawMs   = [math]::Round(([double]$p[5] - [double]$p[4]) / 1000000.0, 1)
            PrepareMs = [math]::Round(([double]$p[7] - [double]$p[6]) / 1000000.0, 1)
            LayoutMs = [math]::Round(([double]$p[9] - [double]$p[8]) / 1000000.0, 1)
            SyncMs   = [math]::Round(([double]$p[11] - [double]$p[10]) / 1000000.0, 1)
            IssueMs  = [math]::Round(([double]$p[13] - [double]$p[12]) / 1000000.0, 1)
        }
    }
}
Write-Output ("  frames over 16 ms: {0}" -f $slow.Count)
$slow | Sort-Object TotalMs -Descending | Select-Object -First 12 | Format-Table -AutoSize | Out-String | Write-Output

Write-Output "=== main-thread hotspots in the trace"
adb pull /data/local/tmp/pikit.trace $traceFile 2>&1 | Out-Null
if (Test-Path $traceFile) {
    Write-Output ("  trace: {0} ({1:N0} bytes)" -f $traceFile, (Get-Item $traceFile).Length)
} else {
    Write-Output "  no trace pulled (needs a debuggable build)"
}
