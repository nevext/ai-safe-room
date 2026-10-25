# Liga o camera-ai junto com o Windows, sem janela: uma tarefa agendada "camera-ai" que roda quando
# você entra no PC. Se o Node cair, o run.ps1 sobe de novo. Log em server\camera-ai.now.log.
#
#   powershell -ExecutionPolicy Bypass -File server\autostart.ps1           # instala e já liga
#   powershell -ExecutionPolicy Bypass -File server\autostart.ps1 -Remove   # tira (e desliga)
param([switch]$Remove)
$name = 'camera-ai'
$vbs = Join-Path $PSScriptRoot 'hidden.vbs'

function Stop-CameraAi {
    Get-CimInstance Win32_Process -Filter "name='powershell.exe'" |
        Where-Object { $_.CommandLine -like "*camera-ai*run.ps1*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Get-CimInstance Win32_Process -Filter "name='node.exe'" |
        Where-Object { $_.CommandLine -like '*server*proxy.mjs*' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

if ($Remove) {
    Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue
    Stop-CameraAi
    Write-Output 'camera-ai: tirado do início do Windows e desligado.'
    return
}

$action = New-ScheduledTaskAction -Execute 'wscript.exe' -Argument "`"$vbs`""
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Settings $settings `
    -Description 'camera-ai: a câmera com IA para as TVs e o celular (porta 8787, UDP 8788).' -Force | Out-Null
Stop-CameraAi
Start-ScheduledTask -TaskName $name
Write-Output 'camera-ai: liga junto com o Windows e já está ligado (log em server\camera-ai.now.log).'
