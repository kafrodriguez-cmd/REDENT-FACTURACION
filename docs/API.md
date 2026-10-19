# API REST de REDENT Local

Base demo: `http://localhost:8080`. Oficina: `https://IP-DEL-SERVIDOR:8443`.

## Autenticación y CSRF

1. `GET /api/csrf`: conserve la cookie de sesión y los campos `token` y `header`.
2. `POST /login`, `Content-Type: application/x-www-form-urlencoded`, campos `username` y `password`, encabezado CSRF recibido. Respuesta 200 si es correcto, 401 si no.
3. **Obtenga un nuevo token con `GET /api/csrf` después del login.** Conserve la nueva cookie `JSESSIONID`.
4. En cada POST/PUT use el encabezado CSRF devuelto (normalmente `X-CSRF-TOKEN`). GET no lo requiere.
5. `POST /logout` con CSRF cierra la sesión.

Los roles son ADMIN, OPERADOR y CONSULTA. No hay JWT propio para la API local: se utilizan sesiones de servidor. El token JWT del MH pertenece exclusivamente al backend y no se devuelve al navegador.

## Recursos

| Método | Ruta | Función |
|---|---|---|
| GET | `/api/me` | Cuenta, perfil y ambiente |
| GET | `/api/dashboard` | Conteos por estado |
| GET / PUT | `/api/empresa` | Datos fiscales; escritura ADMIN |
| GET | `/api/catalogos/{kind}?q=&page=0` | Clientes/productos; 100 por página |
| POST | `/api/catalogos/{kind}` | Alta |
| PUT | `/api/catalogos/{kind}/{id}` | Edición con `revision` actual |
| POST | `/api/importar/{kind}/preview` | Multipart `file`, revisión sin escribir |
| POST | `/api/importar/{kind}/confirmar` | JSON `{"filas":[...]}`, alta atómica |
| GET / POST | `/api/documentos` | Lista paginada / borrador |
| GET | `/api/documentos/{id}` | Detalle y archivos serializados |
| PUT | `/api/documentos/{id}?revision=0` | Editar borrador con control de revisión |
| GET | `/api/documentos/{id}/preview` | Calcular y validar; identificadores provisionales |
| POST | `/api/documentos/{id}/preparar` | Fijar JSON, código y correlativo |
| POST | `/api/documentos/{id}/transmitir` | Firmar y transmitir a pruebas MH |
| POST | `/api/documentos/{id}/consultar` | Consultar DTE, no eventos |
| GET | `/api/documentos/{id}/intentos` | Historial de solicitudes/respuestas |
| GET | `/api/documentos/{id}/pdf` | PDF de pruebas |
| GET | `/api/documentos/{id}/archivos` | ZIP con JSON, JWS, respuesta y PDF disponibles |
| POST | `/api/documentos/{id}/correo` | Envío al correo del receptor; SMTP configurado |
| GET | `/api/esquemas` y `/api/esquemas/{type}` | Esquemas oficiales incluidos |
| POST | `/api/validar/{type}` | Validación estructural sin persistencia |
| GET / POST | `/api/usuarios` | Lista / alta, ADMIN; máximo tres |
| POST | `/api/password` | Cambio propio: `actual`, `nueva` |
| GET | `/api/auditoria?page=0` | Últimas acciones, ADMIN |

`kind` solo permite `clientes` o `productos`. `type` incluye `01`, `03`, `05`, `06`, `11`, `14`, `INVALIDACION`, `CONTINGENCIA`.

## Borrador de una factura

`POST /api/documentos` exige **Idempotency-Key** (UUID recomendado). Reutilice la misma clave si reintenta la misma solicitud por una respuesta perdida. Cambiar sus datos exige una clave nueva. Una colisión concurrente puede responder 409: vuelva a consultar/reintentar la misma clave y cuerpo, no una clave distinta.

```json
{
  "tipo": "01",
  "clienteId": 1,
  "condicionOperacion": 1,
  "formaPago": "01",
  "items": [
    {
      "codigo": "PROD-001",
      "descripcion": "Producto de prueba",
      "cantidad": "2",
      "precio": "10.00",
      "descuento": "0.00",
      "tipoItem": 1,
      "uniMedida": 59,
      "operacion": "GRAVADA"
    }
  ]
}
```

El clienteId debe existir. Alternativamente se acepta `cliente` con la instantánea completa de sus datos. `precio` y `descuento` de entrada son SIN IVA. En la factura consumidor final el generador convierte los valores gravados a IVA incluido. En CCF y notas mantiene base neta e IVA separado. No use `double` para reconstruir montos desde otro cliente API.

La preparación es idempotente: repetirla sobre un documento preparado devuelve el mismo documento. Nunca edite el JSON preparado ni la firma. La información de la vista previa no es un DTE listo para enviar.

## Notas y eventos

Notas 05/06: use `documentoId` del CCF aceptado; su receptor se toma del documento original. No se admiten retenciones en notas en este flujo de prueba. Las líneas son importes positivos; el tipo de nota determina el efecto, no cantidades negativas.

Invalidación: crear un borrador con `tipo: INVALIDACION`, `documentoId`, `codigoGeneracionR` cuando corresponda y objeto `motivo` según `invalidacion-schema-v3.json`. El DTE original solo cambia a INVALIDADO después de respuesta aceptada del MH. No existe borrado físico.

Contingencia: genere los DTE con `contingencia:true`, `tipoContingencia` y `motivoContin`. Luego cree evento `CONTINGENCIA` con `documentos:[id,...]`, `responsable` y `motivo` según el esquema. El intervalo debe contener las fechas de emisión. Transmita el evento y, después de aceptación, los documentos individualmente.

## Estados

BORRADOR → PREPARADO → FIRMANDO → ENVIANDO → ACEPTADO / RECHAZADO / INCIERTO.

Un timeout posterior al registro del envío se conserva como INCIERTO. No se permite volver a llamar a transmitir sobre ese documento. La consulta puede confirmar aceptación. Un resultado de consulta no satisfactorio no prueba que un envío nunca existió y no habilita un reenvío automático.

En un reinicio, ENVIANDO pasa a INCIERTO y FIRMANDO vuelve a PREPARADO. Una invalidación aceptada cambia su original a INVALIDADO. El rechazo conserva el documento y su evidencia.

## Errores y concurrencia

- 400: datos inválidos.
- 401: sesión ausente o vencida.
- 403: permiso o CSRF.
- 409: duplicado, versión concurrente, estado incompatible o integración no configurada.
- 413: archivo excede 5 MB.
- 422: JSON no cumple esquema; `errores` contiene ubicaciones/mensajes.
- 500: fallo interno; no se devuelve SQL, credenciales ni trazas al cliente.

La transacción de base de datos no permanece abierta mientras se espera al MH. La intención de envío y el JWS se guardan antes de la llamada HTTP. Esta es una salvaguarda de recuperación, no una garantía distribuida de entrega “exactamente una vez”.
