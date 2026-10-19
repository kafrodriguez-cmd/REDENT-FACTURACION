# Copia consistente de PostgreSQL + archivo cifrado con restic.
# backup-settings.ps1 debe definir las variables documentadas. No borra respaldos.
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if(-not (Test-Path './config/backup-settings.ps1')){throw 'Falta config/backup-settings.ps1'}
. './config/backup-settings.ps1'
foreach($name in @('PGHOST','PGPORT','PGDATABASE','PGUSER','PGPASSFILE','RESTIC_REPOSITORY','RESTIC_PASSWORD_FILE')){
 if(-not [Environment]::GetEnvironmentVariable($name)){throw "Falta $name"}
}
$dir=Join-Path (Get-Location) 'backups/staging'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$dump=Join-Path $dir "redent-$stamp.dump"
$log=Join-Path (Get-Location) 'backups/ultima-ejecucion.txt'
try {
 & pg_dump --no-password --format=custom --no-owner --no-acl --file=$dump
 if($LASTEXITCODE -ne 0){throw 'pg_dump no termino correctamente'}
 & pg_restore --list $dump | Out-Null
 if($LASTEXITCODE -ne 0){throw 'El archivo de respaldo no pudo leerse'}
 # restic puede apuntar a S3, SFTP, Azure u otro repositorio soportado.
 & restic backup $dump './config' './redent-local.jar' --tag redent --tag postgresql
 if($LASTEXITCODE -ne 0){throw 'No se confirmo el respaldo externo'}
 & restic check
 if($LASTEXITCODE -ne 0){throw 'El repositorio requiere revision'}
 "EXITO $stamp - respaldo externo confirmado" | Set-Content $log
 # La copia de staging contiene datos sin cifrar; se elimina solo despues de confirmar restic.
 Remove-Item -LiteralPath $dump
}
catch {
 "FALLO $stamp - revise espacio, credenciales y conectividad. No se eliminaron los archivos de staging." | Set-Content $log
 throw
}
