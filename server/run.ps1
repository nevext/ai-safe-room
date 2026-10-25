# Mantém o camera-ai rodando: se o Node cair, sobe de novo em 5 s. O que ele escreve vai para
# server\camera-ai.now.log (sessões anteriores em camera-ai.log). Quem chama é o server\autostart.ps1 (junto com o Windows), sem janela.
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$log = Join-Path $PSScriptRoot 'camera-ai.log'       # as sessões anteriores
$out = Join-Path $PSScriptRoot 'camera-ai.now.log'   # a de agora
$err = Join-Path $PSScriptRoot 'camera-ai.err.log'
$node = (Get-Command node -ErrorAction SilentlyContinue).Source
if (-not $node) { $node = Join-Path $env:ProgramFiles 'nodejs\node.exe' }
while ($true) {
    [IO.File]::AppendAllText($log, "--- $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') camera-ai ligando`r`n")
    # Start-Process grava a saída do Node como ela é (o redirecionamento do PowerShell 5.1 vira UTF-16)
    $p = Start-Process -FilePath $node -ArgumentList 'server\proxy.mjs' -WorkingDirectory $root -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $out -RedirectStandardError $err
    [IO.File]::AppendAllText($log, [IO.File]::ReadAllText($out) + [IO.File]::ReadAllText($err))
    [IO.File]::AppendAllText($log, "--- $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') camera-ai parou (código $($p.ExitCode)); de novo em 5 s`r`n")
    Start-Sleep -Seconds 5
}
