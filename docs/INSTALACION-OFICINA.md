# Instalación de oficina y respaldos

Esta guía instala la versión **de pruebas** en PostgreSQL. No habilita emisión fiscal productiva.

## PostgreSQL

1. Instale una versión soportada de PostgreSQL en la computadora principal. Configure el servicio para inicio automático, con acceso de base de datos limitado al propio servidor.
2. En `psql`, como administrador de PostgreSQL, cree el usuario sin otorgarle privilegios de superusuario:

```sql
CREATE ROLE redent LOGIN;
\password redent
CREATE DATABASE redent OWNER redent;
```

`\password` solicita la clave sin guardarla en el comando SQL. No use `postgres` como cuenta de la aplicación. Mantenga el puerto 5432 cerrado para la otra computadora: solo la aplicación accede a la base.

3. Extraiga la aplicación en `C:\REDENT`. Copie `config/secrets.ps1.example` a `config/secrets.ps1` y complete la contraseña PostgreSQL. Mantenga los datos de pruebas y los futuros datos de producción en bases separadas.
4. Restrinja NTFS de la carpeta `config` y de los respaldos al usuario que ejecuta el servidor, SYSTEM y administradores autorizados. No comparta esa carpeta por red. No incluya claves en Git.

## Red local y HTTPS

1. Reserve en el router una IP para el servidor. Ejemplo de la guía: `192.168.1.20`; sustituya por la IP real.
2. Defina `REDENT_TLS_PASSWORD` en `config/secrets.ps1` (clave nueva, no la de MH).
3. Desde PowerShell en `C:\REDENT`:

```powershell
.\scripts\configurar-tls.ps1 -IpServidor 192.168.1.20
```

4. Copie `config/redent.properties.example` a `config/redent.properties`.
5. Importe **únicamente** `config/redent-publico.cer` como certificado de confianza en las dos computadoras autorizadas. Verifique su huella SHA-256 antes de confiar en él. No copie la clave privada `redent.p12`. El certificado generado vence en 365 días; documente su renovación.
6. Abra en Windows Firewall **solo TCP 8443** para la red privada o las dos IP autorizadas. No abra 5432 ni 8113. No configure reenvío de puertos del router.
7. Ejecute `INICIAR-OFICINA.bat` y establezca la contraseña inicial de admin si la base está vacía. Desde ambas computadoras abra `https://192.168.1.20:8443`.
8. Cree las otras dos cuentas en Usuarios. Pruebe que CONSULTA no pueda modificar y que OPERADOR no pueda cambiar Empresa/Usuarios.

La versión entregada se ejecuta en una consola, no instala un servicio Windows. Mantenga la sesión/ventana activa. Antes de una instalación definitiva, el técnico debe instalarla como servicio con una cuenta limitada y comprobar reinicio automático, cierre ordenado, apagones y protección de la carpeta de datos.

## Configuración MH

1. Confirme con el MH el procedimiento de incorporación/cambio desde el portal gratuito al **Sistema de Transmisión**, y la habilitación de los seis tipos de DTE y eventos en pruebas. La autorización actual en el portal gratuito no demuestra acceso API.
2. Obtenga mediante los canales oficiales las credenciales API y el certificado del emisor para pruebas. No utilice las credenciales de otra empresa ni suponga que la contraseña del portal es la clave API.
3. Descargue e instale el **firmador oficial** desde la documentación del MH. Siga su propio manual para el certificado y sus credenciales. La aplicación consume `http://127.0.0.1:8113/firmardocumento/`; compruebe la ruta real de la instalación oficial.
4. Configure `MH_API_PASSWORD` y `MH_SIGNER_PASSWORD` en `config/secrets.ps1`. El NIT se obtiene de Empresa y debe coincidir con el emisor habilitado. No pegue estas claves en la interfaz, Postman exportado, capturas o mensajes.
5. Active `MH_ENABLED=true` y reinicie REDENT. El ambiente sigue fijo en `00`.
6. Prepare y revise un documento ficticio; transmita y conserve la respuesta. Una prueba satisfactoria necesita `PROCESADO`, código de generación coincidente, ambiente `00` y sello de recepción. No basta un HTTP 200.
7. Complete el plan de pruebas fiscales del MH con el contador y registre evidencia de aceptación de cada tipo y evento. No se implementó un cambio automático a producción.

## SMTP

Complete SMTP_HOST, SMTP_PORT, SMTP_USER y SMTP_PASSWORD con una cuenta autorizada de la empresa; habilite `redent.mail.enabled=true`. Se utiliza STARTTLS obligatorio. El proveedor puede exigir una contraseña de aplicación o un mecanismo diferente. Esta entrega no implementa OAuth2 SMTP.

El envío se realiza por acción del usuario sobre un DTE aceptado, al correo guardado en el receptor. Los documentos y el asunto se marcan como PRUEBAS. Si SMTP no confirma, se registra INCIERTO; revise la bandeja antes de repetir. No se ha probado con una cuenta real.

## Respaldo cifrado externo

La nube guarda **copias**, no una segunda base activa. La aplicación continúa siendo local.

1. Instale las herramientas `pg_dump`, `pg_restore`, `psql`, `createdb` de una versión compatible con su servidor PostgreSQL, y `restic` en PATH.
2. Elija y contrate/configure su repositorio externo: por ejemplo S3 compatible o SFTP. Los costos y las credenciales dependen del proveedor; no hay cuenta creada ni cobro efectuado en esta entrega.
3. Copie `config/backup-settings.ps1.example` a `config/backup-settings.ps1`. Complete el endpoint real y credenciales limitadas al repositorio de REDENT.
4. Cree `pgpass.conf` y `restic-password.txt` en las ubicaciones configuradas, con NTFS restringido. Guarde una copia segura de la clave de restic **fuera de la computadora principal**. Sin ella no podrá recuperar el respaldo.
5. Cargue la configuración e inicialice el repositorio, solo una vez:

```powershell
. .\config\backup-settings.ps1
restic init
.\scripts\backup.ps1
restic snapshots --tag redent
```

6. Verifique `backups/ultima-ejecucion.txt`. El script usa `pg_dump` consistente, archiva configuración y ejecutable, cifra mediante restic, ejecuta `restic check` y elimina el dump temporal únicamente después del éxito. No se debe copiar el directorio de datos de PostgreSQL mientras esté en uso.
7. Programe `scripts/backup.ps1` en el Programador de tareas de Windows, diariamente después del horario laboral y después de cambios relevantes. Defina la cuenta de ejecución, permiso de acceso a red, reintento ante fallo y recuperación de ejecuciones omitidas. Ejecute manualmente la tarea con esa cuenta y revise su código de salida. No se ha registrado ninguna tarea en sus computadoras.
8. El script no elimina snapshots antiguos. Defina una política de conservación con el contador y documente una segunda copia independiente antes de automatizar cualquier borrado. `restic check` revisa integridad, pero no reemplaza una restauración de prueba.

## Restauración de prueba (sin tocar la base activa)

```powershell
. .\config\backup-settings.ps1
restic restore ID_DEL_SNAPSHOT --target C:\REDENT-RECUPERACION
.\scripts\restaurar-prueba.ps1 -ArchivoDump 'RUTA_AL_DUMP_RECUPERADO' -BaseDestino redent_restore_20261014
```

Use una cuenta de PostgreSQL autorizada para crear esa base de restauración. El script exige un nombre nuevo `redent_restore_*` y no sobrescribe la base `redent`. Compruebe usuarios, clientes, productos, cantidades de documentos, JSON, firmas y auditoría. Si arranca una copia de la aplicación para revisarla, mantenga `MH_ENABLED=false`, SMTP desactivado, puerto diferente y sin acceso de clientes: una restauración no debe retransmitir documentos.

## Recuperación de fallos

- Corte durante envío: al reiniciar, los documentos ENVIANDO pasan a INCIERTO. Consulte antes de decidir una acción; no prepare otra venta como sustituto automático.
- Corte durante firma: vuelve a PREPARADO, porque el envío no había quedado registrado.
- Error de esquema: permanezca en borrador, corrija datos y vuelva a revisar. Si ya está preparado, no lo edite directamente en SQL.
- Documento rechazado: conserve evidencia y revise con el técnico. No hay botón de “borrar historial” ni reemisión automática.
- Contingencia sin internet: prepare DTE con tipo operación 2, registre causa e intervalo; cuando termine, prepare y transmita el evento y después los DTE declarados. Falta verificar plazos y todos los escenarios normativos antes de producción.
