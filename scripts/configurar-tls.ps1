param([Parameter(Mandatory=$true)][string]$IpServidor)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$parsed=$null
if(-not [System.Net.IPAddress]::TryParse($IpServidor,[ref]$parsed)){throw 'Direccion IP invalida'}
if(Test-Path './config/redent.p12'){throw 'Ya existe un certificado; no se sobrescribe.'}
if(Test-Path './config/secrets.ps1'){. './config/secrets.ps1'}
if(-not $env:REDENT_TLS_PASSWORD -or $env:REDENT_TLS_PASSWORD.Length -lt 12){throw 'Defina REDENT_TLS_PASSWORD (12 caracteres minimo) en config/secrets.ps1.'}
& keytool -genkeypair -alias redent -keyalg RSA -keysize 3072 -validity 365 -storetype PKCS12 -keystore './config/redent.p12' -storepass:env REDENT_TLS_PASSWORD -dname 'CN=REDENT Oficina, O=REDENT, C=SV' -ext "SAN=ip:$IpServidor,dns:localhost"
if($LASTEXITCODE -ne 0){throw 'No se genero el certificado'}
& keytool -exportcert -alias redent -keystore './config/redent.p12' -storepass:env REDENT_TLS_PASSWORD -file './config/redent-publico.cer'
if($LASTEXITCODE -ne 0){throw 'No se exporto el certificado publico'}
Write-Host 'Certificado creado. Importe SOLO redent-publico.cer en la confianza de las dos computadoras.'
Write-Host 'No copie el archivo .p12 ni secrets.ps1 a la computadora cliente. Renueve antes de un ano.'
