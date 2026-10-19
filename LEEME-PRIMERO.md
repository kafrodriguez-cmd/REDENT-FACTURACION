# REDENT Local 0.9 — aplicación ejecutable para validación y pruebas MH

**Estado de esta entrega: funciona localmente y está restringida al ambiente 00 de pruebas. No está homologada ni lista para sustituir el portal gratuito en producción.** No se dispone de las credenciales, certificado, archivos Excel reales ni infraestructura de REDENT. No se ha enviado ningún documento al MH ni correo a clientes.

## 1. Probar hoy en una computadora Windows

1. Extraiga TODO el ZIP en una carpeta, por ejemplo `C:\REDENT`. No ejecute dentro del ZIP ni dentro de OneDrive. Si Windows marca los scripts descargados, desbloquee el ZIP desde Propiedades antes de extraerlo.
2. Compruebe `java -version`. Requiere **Java 17 o 21**. Si aparece Java 8, seleccione una instalación compatible en PATH. No necesita IntelliJ, Maven, Docker ni virtualización para ejecutar el JAR incluido.
3. Abra **INICIAR-DEMO.bat**. En el primer inicio, establezca su contraseña de administrador (12 caracteres como mínimo). No existe una contraseña predeterminada.
4. Espere el mensaje `Started RedentApplication`. Abra **http://localhost:8080**.
5. Ingrese como **admin**, con la contraseña que eligió. En inicios posteriores deje vacía la pregunta de clave inicial; su contraseña guardada sigue vigente.
6. Complete **Empresa**, agregue clientes y productos o use las plantillas Excel. Utilice datos ficticios para la primera revisión.
7. Cree una operación → guarde el borrador → revise cálculo y estructura → prepare el documento → abra su PDF.
8. La transmisión permanecerá desactivada hasta configurar y comprobar el acceso de pruebas de REDENT.

La demostración usa H2 en `data/redent-demo.mv.db`. No la confunda con la instalación de oficina PostgreSQL. Cerrar la ventana detiene el servicio. Los datos permanecen en disco. No abra dos instancias apuntando a esa misma carpeta. No borre `data` para resolver errores.

## 2. Instalar en las dos computadoras: opción B

- **Computadora principal:** Java, PostgreSQL, aplicación REDENT y firmador oficial MH. Permanece encendida y conectada a la red. Use un UPS y desactive la suspensión durante el horario de trabajo.
- **Segunda computadora:** navegador y certificado público de confianza. No instala otra base de datos ni otra copia del servidor.
- **Tres personas:** tres cuentas individuales. ADMIN administra datos fiscales y usuarios; OPERADOR administra catálogos y documentos; CONSULTA solo lee.
- **Sin internet:** puede utilizar los catálogos, preparar borradores y documentos locales. Los DTE en contingencia siguen el procedimiento fiscal; no se presentan como aceptados antes de recibir la respuesta correspondiente.

Siga `docs/INSTALACION-OFICINA.md`. Reserve una IP local para la computadora principal. La instalación de oficina usa HTTPS para proteger las credenciales entre equipos. No publique los puertos del servidor en internet.

## 3. Qué está implementado

- API REST y aplicación web local, sesión con CSRF, contraseñas BCrypt y tres cuentas con roles.
- **Catálogos oficiales MH integrados (CAT-001 al CAT-034):** 14 departamentos, 44 municipios, 262 distritos, unidades de medida, formas de pago, actividades económicas, tributos, Incoterms, tipos de contingencia y tipos de invalidación validados en backend y UI.
- Catálogos de clientes y productos, edición con control de revisión y archivo lógico con códigos oficiales MH.
- Importación `.xlsx`/`.xls` con revisión, rechazo de fórmulas, detección de duplicados y confirmación atómica. Plantillas vacías en `plantillas/`.
- Borradores, cálculo decimal, instantánea de cliente, versiones de esquemas MH oficiales y validación contra esquemas antes de preparar.
- Generadores de **01** (Factura, incluyendo ventas a consumidor final / mostrador sin documento si es < $25,000), **03** (CCF con validación estricta de NIT, NRC y actividad), **05** (Nota de Crédito sobre CCF o Factura), **06** (Nota de Débito sobre CCF o Factura), **11** (Factura de Exportación con Incoterms y flete/seguro confirmados) y **14** (Sujeto Excluido con retención del 10% de renta Art. 156-A).
- Preparación y transmisión de eventos de **Invalidación V3** y **Contingencia V4**.
- Numeración por ambiente, tipo, establecimiento, punto y año; bloqueo transaccional; clave de idempotencia.
- Autenticación MH, firmador local, sobres de transmisión, consulta DTE y conservación de JWS/respuesta/sello en ambientes **00 (Pruebas)** y **01 (Producción)** configurables.
- Estado INCIERTO ante un resultado de red no confirmado, sin reenvío automático a ciegas.
- **Representación Gráfica (PDF oficial):** código QR público (`https://admin.factura.gob.sv/consultaPublica`), leyendas legales obligatorias de Hacienda, desglose de IVA/renta/retenciones, tabla de ítems y condiciones de pago.
- Scripts de respaldo PostgreSQL cifrado con restic y restauración en una base nueva de prueba.
- Código fuente, POM, 26 pruebas automatizadas, contratos de API y ejemplos de Postman.

## 4. Requisitos y consideraciones operativas para producción

**El sistema cuenta con validaciones de esquemas oficiales y catálogos MH.** Antes de operar en producción:

- **Credenciales y certificado:** Configurar en `config/redent.properties` o variables de entorno el NIT, contraseña API MH, clave privada del certificado del firmador MH (`passwordPri`) y activar ambiente `01` cuando el MH autorice el paso a producción.
- **Plan de transmisión de pruebas:** Completar las transmisiones mínimas exigidas por el MH en ambiente 00 antes de solicitar el pase a producción al Ministerio de Hacienda.
- **Firmador oficial MH:** Debe estar ejecutándose localmente en el puerto 8113 con el certificado `.p12` emitido por el Ministerio de Hacienda para REDENT.
- **Revisión contable:** Validar con el contador de la empresa los códigos de actividad económica principal (CAT-019) y las reglas de retención de IVA (Grandes Contribuyentes) y Renta (Art. 156-A) aplicables a su giro.
- **Infraestructura:** En la oficina, asegurar la configuración de PostgreSQL, red local con IP fija para el servidor y respaldos periódicos con restic.

## 5. Entregables y desarrollo

- `redent-local.jar`: ejecutable con dependencias de la aplicación incluidas; requiere Java.
- `src/`, `pom.xml`: proyecto Maven para IntelliJ.
- `plantillas/`: archivos Excel vacíos para importar.
- `config/*.example`: modelos; no incluyen credenciales.
- `docs/API.md`, `docs/openapi.json`, `docs/REDENT.postman_collection.json`: API y ejemplos.
- `docs/MATRIZ-MH.md`: fuentes, versiones, verificación y pendientes.
- `docs/PRUEBAS.md`: resultados reproducibles y alcance de las comprobaciones.

Para compilar desde IntelliJ: abrir **pom.xml**, seleccionar JDK 17, importar Maven y ejecutar `sv.redent.RedentApplication` con `REDENT_ADMIN_PASSWORD` definida en el primer arranque.

Desde terminal con Maven 3.9 o posterior:

```text
mvn clean verify
java -jar target/redent-local-0.9.0.jar
```

La primera compilación requiere internet para descargar dependencias. Ejecutar el JAR incluido no requiere Maven. El firmador del MH, Java, PostgreSQL y restic son instalaciones externas; no se distribuyen dentro de este ZIP.
