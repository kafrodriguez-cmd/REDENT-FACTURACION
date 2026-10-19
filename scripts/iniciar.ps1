param([ValidateSet('demo','local')][string]$Perfil='demo')
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

$javaExecutable = 'java'
$isModernJava = $false
try {
    $verOut = & java -version 2>&1 | Out-String
    if ($verOut -match 'version "(1[7-9]|[2-9][0-9])') {
        $isModernJava = $true
    }
} catch {}

if (-not $isModernJava) {
    $candidates = @(
        $env:JAVA_HOME,
        'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.2\jbr',
        'C:\Program Files\Java\jdk-21',
        'C:\Program Files\Java\jdk-17',
        'C:\Program Files\Eclipse Adoptium\jdk-21*',
        'C:\Program Files\Eclipse Adoptium\jdk-17*'
    )
    foreach ($cand in $candidates) {
        if ($cand) {
            $resolved = Resolve-Path $cand -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Path -First 1
            if ($resolved -and (Test-Path "$resolved\bin\java.exe")) {
                $env:JAVA_HOME = $resolved
                $env:PATH = "$resolved\bin;" + $env:PATH
                $javaExecutable = "$resolved\bin\java.exe"
                $isModernJava = $true
                break
            }
        }
    }
}

if (-not $isModernJava) { throw 'Se requiere Java 17 o 21 (actualmente no se encontro en PATH ni en las rutas estandar).' }
if (Test-Path './config/secrets.ps1') { . './config/secrets.ps1' }
if ($Perfil -eq 'local' -and -not $env:REDENT_DB_PASSWORD) { throw 'Configure REDENT_DB_PASSWORD en config/secrets.ps1. Consulte LEEME-PRIMERO.md.' }
if (-not $env:REDENT_ADMIN_PASSWORD) {
    Write-Host 'Primer inicio: indique una clave de administrador de al menos 12 caracteres.'
    Write-Host 'Si ya creo su cuenta, puede dejar este campo vacio.'
    $secret=Read-Host 'Clave inicial de admin' -AsSecureString
    $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    try {$env:REDENT_ADMIN_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)}
    finally {[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}
}
Write-Host 'Servidor local. Mantenga esta ventana abierta. Detener: Ctrl+C.'
Write-Host 'Demo: http://localhost:8080  |  Oficina con TLS: https://IP-DEL-SERVIDOR:8443'
$env:SPRING_PROFILES_ACTIVE=$Perfil
try { & $javaExecutable -Xms128m -Xmx768m -jar './redent-local.jar'; if($LASTEXITCODE -ne 0){throw 'La aplicacion no pudo iniciar. Revise el mensaje anterior.'} }
finally {Remove-Item Env:REDENT_ADMIN_PASSWORD -ErrorAction SilentlyContinue}
