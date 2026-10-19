# Evidencia de verificación y límites

Se compila el proyecto con Java 17 y Maven 3.9.11. Las dependencias de aplicación quedan dentro del JAR ejecutable. No se ejecutaron llamadas a Hacienda, envío SMTP real ni tareas en las computadoras de REDENT.

## Comprobaciones automatizadas

La suite incluida contiene 21 casos: seis variantes de generación de DTE contra los esquemas oficiales, cálculos de IVA incluido y separado, exención, importes inválidos, bloqueo de cargos de exportación no implementados, letras del total, idempotencia, preparación concurrente, recuperación de resultado incierto, correspondencia de respuesta, invalidación, límite de notas de crédito, contingencia, importación atómica, revisión concurrente, controles de acceso y PDF multipágina.

- Las seis variantes de DTE pasan validación estructural con datos ficticios.
- Se preparan 12 documentos concurrentes sin repetir número de control.
- Un timeout simulado produce INCIERTO y no permite retransmitir a ciegas.
- Una respuesta perteneciente a otro código de generación nunca se registra como aceptación.
- El original solo pasa a INVALIDADO después de aceptación simulada del evento.
- El evento de contingencia debe aceptarse antes de transmitir los DTE declarados.
- Un duplicado en la importación revierte todas las filas de esa operación.
- Un rol CONSULTA no puede crear documentos; las mutaciones requieren CSRF.
- El PDF multipágina mantiene el último ítem y los totales. Se inspeccionó visualmente la primera página después de incorporar fuentes embebidas.

Las respuestas MH en estas pruebas son **dobles de prueba**, no sellos reales. Los valores ficticios aparecen solo en código de pruebas, nunca como aceptación automática en la aplicación.

Reproducir:

```text
mvn clean verify
```

Los informes se generan en `target/surefire-reports/`. El PDF de QA se genera en `target/qa/`. Ninguno contiene datos reales de REDENT.

## Ejecución del JAR por HTTP

Se levantó el JAR en un servidor local con H2 en memoria y se comprobaron 13 pasos: CSRF, login, datos de empresa, alta de cliente, borrador, vista previa, preparación, PDF, bloqueo de transmisión sin configuración, lista de documentos, resumen, logout y denegación posterior al cierre. Se comprobó un total de $22.60 para dos unidades netas de $10 con IVA incluido en factura.

Se revisó la sintaxis JavaScript con `node --check`. No se completó una prueba visual end-to-end en navegador: la descarga del navegador de pruebas no estuvo disponible. Por eso no se afirma que la interfaz haya sido verificada en Windows/Opera/Chrome.

## No comprobado en este entorno

- Migraciones/operación en un servidor PostgreSQL real: las pruebas de persistencia usaron H2 en modo PostgreSQL. La instalación local de PostgreSQL no estuvo disponible.
- Scripts PowerShell, certificado HTTPS, confianza de las dos computadoras y permisos NTFS en Windows.
- Credenciales/certificado del emisor, autenticación y transmisión reales a MH.
- Todos los casos y plazos normativos, catálogos y representación fiscal. Ver MATRIZ-MH.md.
- Importación de los archivos Excel actuales de REDENT.
- Envío de correo con su proveedor SMTP.
- Repositorio de respaldo externo, ejecución programada o restauración real.

La evidencia local no justifica habilitar producción. La entrega permite probar y continuar el desarrollo; aún no satisface todo el alcance de un sistema fiscal integral listo para operar.
