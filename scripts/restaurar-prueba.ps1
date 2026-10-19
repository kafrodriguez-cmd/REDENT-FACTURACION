param([Parameter(Mandatory=$true)][string]$ArchivoDump,[Parameter(Mandatory=$true)][string]$BaseDestino)
$ErrorActionPreference='Stop'
if($BaseDestino -notmatch '^redent_restore_[a-zA-Z0-9_]+$'){throw 'Use una base nueva con nombre redent_restore_FECHA; no se permite restaurar sobre redent.'}
if(-not (Test-Path -LiteralPath $ArchivoDump)){throw 'No existe el archivo dump'}
& createdb -- $BaseDestino
if($LASTEXITCODE -ne 0){throw 'No se creo la base nueva. Si ya existe, el script se detiene.'}
& pg_restore --exit-on-error --no-owner --no-acl --dbname=$BaseDestino $ArchivoDump
if($LASTEXITCODE -ne 0){throw 'La restauracion fallo; revise el registro. No se modifico la base original.'}
& psql --dbname=$BaseDestino --command='SELECT status, count(*) FROM document GROUP BY status;'
if($LASTEXITCODE -ne 0){throw 'No se pudieron verificar las tablas restauradas'}
Write-Host 'Restauracion terminada en base de prueba. Verifique catalogos, DTE, firmas y auditoria antes de considerar probado el respaldo.'
