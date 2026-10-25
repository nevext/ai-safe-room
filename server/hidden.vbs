' Abre o server\run.ps1 sem janela nenhuma (o PowerShell sozinho piscaria uma janela).
Set fs = CreateObject("Scripting.FileSystemObject")
here = fs.GetParentFolderName(WScript.ScriptFullName)
CreateObject("WScript.Shell").Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -File """ & here & "\run.ps1""", 0, False
