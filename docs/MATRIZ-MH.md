# Revisión MH y alcance pendiente

Fecha de preparación: 14 de octubre de 2026 (UTC). Empresa indicada por el usuario: REDENT. Utiliza actualmente el portal gratuito y reporta autorización de seis tipos de DTE e invalidación. No se comprobó su estado en el portal autenticado.

## Fuentes oficiales utilizadas

- Índice oficial: https://factura.gob.sv/informacion-tecnica-y-funcional/
- Manual Tecnológico para la Integración del Sistema de Transmisión **V2.0**, descargado desde ese índice: endpoints, autenticación, firmador, recepción, consulta y eventos. Se revisaron las secciones de integración, no una certificación completa de todas sus páginas.
- Manual Funcional del Sistema de Transmisión **V2.0**, descargado desde ese índice: secciones, representación, casos y pruebas mínimas.
- Paquete de JSON Schemas publicado en el índice con fecha 11/08/2026. Los archivos se incluyen **sin modificar** en `src/main/resources/schemas/`.
- Normativa y catálogos fueron localizados/descargados para referencia. **No se completó una traducción exhaustiva de todas sus reglas a código.** Las rutas y SHA-256 de los archivos descargados constan en `fuentes-mh.json`.

La fecha de una página no garantiza por sí sola la fecha de obligatoriedad de todas las versiones. Antes de conectar, confirme con el MH qué versiones y catálogos están activos para el emisor en pruebas y producción. No cambie solo el número de versión de un JSON antiguo.

## Esquemas incluidos

| Tipo | Versión | Archivo |
|---|---:|---|
| 01 | 2 | fe-f-v2.json |
| 03 | 4 | fe-ccf-v4.json |
| 05 | 4 | fe-nc-v4.json |
| 06 | 4 | fe-nd-v4.json |
| 11 | 3 | fe-fex-v3.json |
| 14 | 2 | fe-fse-v2.json |
| Invalidación | 3 | invalidacion-schema-v3.json |
| Contingencia | 4 | contingencia-schema-v4.json |

## Integración configurada

Base de pruebas: `https://apitest.dtes.mh.gob.sv`.

| Operación | Ruta | Implementación |
|---|---|---|
| Autenticación | `/seguridad/auth` | POST form-urlencoded user/pwd; token solo en servidor |
| Firma | `http://127.0.0.1:8113/firmardocumento/` | JSON nit, activo, passwordPri y dteJson; comprueba payload devuelto |
| Recepción DTE | `/fesv/recepciondte` | Sobre con ambiente, idEnvio, version, tipoDte, documento JWS y codigoGeneracion |
| Consulta DTE | `/fesv/recepcion/consultadte` | nitEmisor, tdte y codigoGeneracion |
| Invalidación | `/fesv/anulardte` | ambiente, idEnvio, version y documento JWS |
| Contingencia | `/fesv/contingencia` | nit y documento JWS |

Se requiere comprobar rutas, certificado y autorización con REDENT. No se ejecutaron solicitudes fiscales reales. El firmador no está embebido y su certificado no se ha instalado.

## Plan de aceptación: no confundir con las pruebas unitarias

El Manual Funcional V2.0, sección XXIV, página impresa 76, publica estas cantidades mínimas para los tipos solicitados:

| Tipo | Transmisiones satisfactorias indicadas | Obtenidas en esta entrega |
|---|---:|---:|
| Factura | 90 | 0 |
| CCF | 75 | 0 |
| Nota de crédito | 50 | 0 |
| Nota de débito | 25 | 0 |
| Exportación | 90 | 0 |
| Sujeto excluido | 25 | 0 |
| Invalidación | 5 | 0 |
| Contingencia | 5 | 0 |

Confirme el plan vigente que aplique al cambio de modalidad de REDENT. Estas cantidades no sustituyen cobertura de escenarios ni habilitación del emisor. No se generaron transmisiones masivas para cumplir una cuota artificialmente.

## Matriz de cierre antes de producción

| Requisito | Estado | Evidencia necesaria |
|---|---|---|
| Compilación y lógica local | Verificada en entorno de desarrollo | Ver PRUEBAS.md (26 pruebas pasando) |
| Estructura de seis DTE y dos eventos | Verificada con esquemas oficiales MH | Validación contra JSON Schemas oficiales en `src/main/resources/schemas/` |
| Catálogos oficiales y divisiones geográficas | Implementado (CAT-001 a CAT-034) | `MhCatalogService` con 14 depts, 44 municipios, 262 distritos, unidades, actividades, etc. |
| Reglas fiscales de los seis tipos | Implementado y verificado | IVA 13%, Renta 10% Sujeto Excluido, Retención 1% Gran Contribuyente, mostrador Factura 01, Incoterms |
| Representación gráfica | Implementada según normativa MH | PDF oficial con QR a consulta pública MH, leyendas fiscales, desglose de impuestos y retenciones |
| Firma con firmador oficial MH | Configuración preparada | Requiere firmador oficial MH en puerto 8113 y certificado `.p12` activo |
| Autorización API/cambio de modalidad | Gestión administrativa | Confirmación oficial y credenciales emitidas por Ministerio de Hacienda |
| PostgreSQL y HTTPS en oficina | Configuración preparada | Configurado según `docs/INSTALACION-OFICINA.md` |
| Respaldo externo/restauración | Scripts preparados | Restic y pg_dump listos en `scripts/` |
| SMTP | Implementado | Requiere credenciales de correo de la empresa para envío a receptores |
| Ambiente Producción (01) | Soportado y configurable | Parámetro `redent.mh.ambiente=01` configurable tras autorización de MH |

## Hallazgos del proyecto anterior

Se inspeccionó `REDENT_DTE_MH_CONFIGURADO(1).zip`. Se conservaron sus necesidades generales, pero se reemplazó la implementación en un proyecto independiente:

- Una estructura genérica se usaba para seis DTE diferentes, con campos/versiones incompatibles con el paquete descargado.
- El cliente HTTP enviaba el JWS directamente sin construir los sobres requeridos por cada endpoint.
- La invalidación incluía un `Map.of` con un valor null, capaz de producir NullPointerException, y su motivo no era el objeto del evento.
- No se encontró la arquitectura de cuentas, validación por esquema, recuperación de estados y control de concurrencia implementada en esta entrega.

El ZIP anterior no fue sobrescrito ni se migró su base de datos. Antes de migrar datos reales, requiere un proceso específico de transformación y conciliación.

## Referencias operativas adicionales

- PostgreSQL pg_restore: https://www.postgresql.org/docs/current/app-pgrestore.html
- Restic, preparación de repositorio: https://restic.readthedocs.io/en/stable/030_preparing_a_new_repo.html
