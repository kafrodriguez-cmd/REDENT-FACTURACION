'use strict';
const $ = s => document.querySelector(s), esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const TYPES = {
    '01': '01 · Factura consumidor final',
    '03': '03 · Comprobante de crédito fiscal',
    '05': '05 · Nota de crédito',
    '06': '06 · Nota de débito',
    '11': '11 · Factura de exportación',
    '14': '14 · Factura sujeto excluido',
    INVALIDACION: 'Evento de invalidación (anulación)',
    CONTINGENCIA: 'Evento de contingencia'
};

function uuid() {
    if (crypto.randomUUID) return crypto.randomUUID().toUpperCase();
    const b = crypto.getRandomValues(new Uint8Array(16));
    b[6] = (b[6] & 15) | 64; b[8] = (b[8] & 63) | 128;
    const h = [...b].map(n => n.toString(16).padStart(2, '0')).join('');
    return (h.slice(0, 8) + '-' + h.slice(8, 12) + '-' + h.slice(12, 16) + '-' + h.slice(16, 20) + '-' + h.slice(20)).toUpperCase();
}

let csrf, user, active = 'home', page = 0, clients = [], products = [], departamentos = [], importRows = null, editing = null, draftKey = uuid();
const fmt = n => new Intl.NumberFormat('es-SV', { style: 'currency', currency: 'USD' }).format(Number(n || 0));

function tell(msg, bad = false) {
    $('#notice').textContent = msg;
    $('#notice').className = 'show' + (bad ? ' error' : '');
    clearTimeout(tell.timer);
    tell.timer = setTimeout(() => $('#notice').className = '', bad ? 18000 : 6500);
}

async function token() {
    const r = await fetch('/api/csrf');
    csrf = await r.json();
}

async function api(path, method = 'GET', data, extra = {}) {
    const headers = { ...extra };
    if (method !== 'GET') {
        if (!csrf) await token();
        headers[csrf.header] = csrf.token;
    }
    let body;
    if (data instanceof FormData) body = data;
    else if (data !== undefined) {
        headers['Content-Type'] = 'application/json';
        body = JSON.stringify(data);
    }
    const r = await fetch('/api' + path, { method, headers, body });
    let j;
    try { j = await r.json(); } catch { j = { mensaje: 'La operación no devolvió una respuesta válida' }; }
    if (!r.ok) {
        if (r.status === 401) { login(); }
        throw Error((j.mensaje || j.message || 'Error ' + r.status) + (j.errores ? '\n• ' + j.errores.join('\n• ') : ''));
    }
    return j;
}

async function run(fn) {
    try { await fn(); } catch (e) { tell(e.message, true); }
}

function bind(id, fn) {
    const e = document.getElementById(id);
    if (e) e.onclick = () => run(async () => {
        e.disabled = true;
        try { await fn(); } finally { e.disabled = false; }
    });
}

function field(name, label, value = '', type = 'text', required = false) {
    return `<label>${esc(label)}<input name="${esc(name)}" type="${type}" value="${esc(value)}" ${required ? 'required' : ''}></label>`;
}

function select(name, label, options, value = '') {
    return `<label>${esc(label)}<select name="${name}">${Object.entries(options).map(([k, v]) => `<option value="${esc(k)}" ${String(value) === String(k) ? 'selected' : ''}>${esc(v)}</option>`).join('')}</select></label>`;
}

function vals(form) {
    return Object.fromEntries(new FormData(form));
}

function badge(s) {
    return `<span class="pill ${['INCIERTO', 'RECHAZADO', 'INVALIDADO'].includes(s) ? 'bad' : ['BORRADOR', 'PREPARADO', 'FIRMANDO', 'ENVIANDO'].includes(s) ? 'warn' : ''}">${esc(s)}</span>`;
}

function login() {
    user = null;
    $('#app').innerHTML = `
        <div class="card login">
            <div class="brand">REDENT<small>FACTURACIÓN ELECTRÓNICA SV</small></div>
            <h2>Ingresar al sistema</h2>
            <p class="muted">Gestión oficial de Documentos Tributarios Electrónicos (DTE) de El Salvador.</p>
            <form id="login">
                ${field('username', 'Usuario', '', 'text', true)}
                ${field('password', 'Contraseña', '', 'password', true)}
                <button>Ingresar a la oficina</button>
            </form>
            <p><span class="pill warn">Cumplimiento DTE Hacienda SV V2.0</span></p>
        </div>`;
    $('#login').onsubmit = e => {
        e.preventDefault();
        run(async () => {
            await token();
            const r = await fetch('/login', {
                method: 'POST',
                headers: { [csrf.header]: csrf.token, 'Content-Type': 'application/x-www-form-urlencoded' },
                body: new URLSearchParams(new FormData(e.target))
            });
            if (!r.ok) throw Error('Usuario o contraseña incorrectos');
            await token();
            await boot();
        });
    };
}

function shell() {
    const admin = user.permisos.some(x => x.authority === 'ROLE_ADMIN');
    const isProd = user.ambienteCodigo === '01';
    $('#app').innerHTML = `
        <div class="shell">
            <aside>
                <div class="brand">REDENT<small>FACTURACIÓN DTE MH</small></div>
                <nav>
                    ${[['home', 'Resumen'], ['new', 'Nueva operación'], ['docs', 'Documentos'], ['clientes', 'Clientes'], ['productos', 'Productos'], ...(admin ? [['company', 'Empresa / MH'], ['users', 'Usuarios'], ['audit', 'Auditoría']] : [])]
                        .map(([k, v]) => `<button data-nav="${k}" class="${active === k ? 'active' : ''}">${v}</button>`).join('')}
                </nav>
                <footer>
                    Ministerio de Hacienda SV<br>
                    Modo: <b>${esc(user.ambiente)}</b><br>
                    REDENT Local 1.0 (DTE V2.0)
                </footer>
            </aside>
            <main>
                <div class="top">
                    <span>OFICINA / <b>${esc(user.usuario)}</b></span>
                    <span>
                        <span class="pill ${isProd ? '' : 'warn'}">${esc(user.ambiente)}</span>
                        <button class="secondary" id="logout">Cerrar sesión</button>
                    </span>
                </div>
                <div id="view"></div>
            </main>
        </div>`;
    document.querySelectorAll('[data-nav]').forEach(b => b.onclick = () => run(() => navigate(b.dataset.nav)));
    bind('logout', async () => {
        await fetch('/logout', { method: 'POST', headers: { [csrf.header]: csrf.token } });
        await token();
        login();
    });
}

async function boot() {
    try {
        user = await api('/me');
        try { departamentos = await api('/mh-catalogos/departamentos'); } catch { departamentos = []; }
        await navigate('home');
    } catch {
        login();
    }
}

async function navigate(dest) {
    active = dest;
    page = 0;
    shell();
    if (dest === 'home') await home();
    else if (dest === 'company') await company();
    else if (dest === 'new') { editing = null; draftKey = uuid(); await invoiceForm(); }
    else if (dest === 'docs') await documents();
    else if (dest === 'users') await users();
    else if (dest === 'audit') await audit();
    else await catalog(dest);
}

async function home() {
    const [d, c] = await Promise.all([api('/dashboard'), api('/empresa')]);
    const count = s => d.estados.find(x => x.status === s)?.cantidad || 0;
    $('#view').innerHTML = `
        <div class="title">
            <div>
                <h1>Gestión y Emisión de Documentos Tributarios Electrónicos</h1>
                <p class="muted">Cumplimiento al pie de la letra con la normativa oficial del Ministerio de Hacienda de El Salvador.</p>
            </div>
            <button id="new">Nueva operación DTE</button>
        </div>
        <div class="metrics">
            <div class="metric">Clientes registrados<strong>${d.clientes}</strong></div>
            <div class="metric">Productos en catálogo<strong>${d.productos}</strong></div>
            <div class="metric">DTE Aceptados por MH<strong>${count('ACEPTADO')}</strong></div>
            <div class="metric">Pendientes de consulta<strong>${count('INCIERTO')}</strong></div>
        </div>
        <div class="card">
            <h2>Estado del emisor y conexión con Hacienda</h2>
            <p><b>Emisor:</b> ${c.nombre ? esc(c.nombre) + ' · NIT ' + esc(c.nit) + ' · NRC ' + esc(c.nrc) : 'Complete los datos fiscales en Empresa / MH.'}</p>
            <p><b>Ambiente fiscal:</b> <span class="pill ${user.ambienteCodigo === '01' ? '' : 'warn'}">${esc(user.ambiente)}</span> (Servicio MH: <code>${esc(user.mhBaseUrl)}</code>)</p>
            <p><b>Transmisión al MH:</b> <b>${user.mhHabilitado ? 'Habilitada y activa' : 'Desactivada hasta configurar credenciales y firmador'}</b></p>
            <p><b>Base de datos:</b> <b>${user.perfil.includes('demo') ? 'Demostración local (H2)' : 'Servidor PostgreSQL de oficina'}</b></p>
            <div class="note info">
                El sistema valida cada documento contra los <b>JSON Schemas oficiales</b> del MH (Factura V2, CCF V4, Nota de Crédito V4, Nota de Débito V4, Factura de Exportación V3, Sujeto Excluido V2, Eventos de Invalidación y Contingencia).
            </div>
        </div>
        <div class="card">
            <h2>Ciclo oficial de facturación electrónica</h2>
            <div class="steps">
                <span>1. Borrador & Validación</span>
                <span>2. Fijar Correlativo & Congelar</span>
                <span>3. Firma JWS local</span>
                <span>4. Transmisión & Sello MH</span>
            </div>
            <p class="muted">El número de control oficial (DTE-XX-XXXX-XXXX-XXXXXXXXXXXXXXX) y el código de generación UUID son asignados y bloqueados transaccionalmente al preparar el documento.</p>
        </div>`;
    bind('new', () => navigate('new'));
}

async function company() {
    const c = await api('/empresa');
    const a = c.direccion || {};
    const depOptions = { '': 'Seleccione departamento...', ...Object.fromEntries(departamentos.map(d => [d.codigo, d.codigo + ' - ' + d.descripcion])) };

    $('#view').innerHTML = `
        <div class="title">
            <div>
                <h1>Datos fiscales de la empresa (Emisor)</h1>
                <p class="muted">Configuración oficial según resolución de autorización de facturación electrónica del MH.</p>
            </div>
        </div>
        <form id="company" class="card">
            <div class="grid">
                ${field('nombre', 'Razón social (Emisor)', c.nombre, 'text', true)}
                ${field('nombreComercial', 'Nombre comercial', c.nombreComercial || 'REDENT')}
                ${field('nit', 'NIT (sin guiones)', c.nit, 'text', true)}
                ${field('nrc', 'NRC (Registro de contribuyente)', c.nrc, 'text', true)}
                ${field('codActividad', 'Código de actividad económica (CAT-019)', c.codActividad, 'text', true)}
                ${field('descActividad', 'Descripción de actividad', c.descActividad, 'text', true)}
                ${field('telefono', 'Teléfono principal', c.telefono, 'text', true)}
                ${field('correo', 'Correo de contacto fiscal', c.correo, 'email', true)}
                ${select('tipoEstablecimiento', 'Tipo de establecimiento (CAT-007)', { '01': '01 - Sucursal / Agencia', '02': '02 - Casa Matriz', '04': '04 - Bodega', '20': '20 - Otro' }, c.tipoEstablecimiento || '01')}
                ${field('codEstableMH', 'Código establecimiento MH (4 car.)', c.codEstableMH || 'M001', 'text', true)}
                ${field('codPuntoVentaMH', 'Código punto de venta MH (4 car.)', c.codPuntoVentaMH || 'P001', 'text', true)}
                ${field('codEstable', 'Código interno establecimiento', c.codEstable || 'M001')}
                ${field('codPuntoVenta', 'Código interno punto de venta', c.codPuntoVenta || 'P001')}
                ${select('departamento', 'Departamento (CAT-012)', depOptions, a.departamento || '')}
                ${field('municipio', 'Municipio (código 2 car.)', a.municipio || '', 'text', true)}
                ${field('distrito', 'Distrito (código 2 car.)', a.distrito || '', 'text', true)}
                ${field('complemento', 'Dirección física completa', a.complemento || '', 'text', true)}
                ${select('ambiente', 'Ambiente fiscal de emisión', { '00': '00 - Pruebas', '01': '01 - Producción' }, c.ambiente || '00')}
            </div>
            <div class="note info">
                Las claves privadas del firmador MH y contraseñas de API se configuran de forma segura en las variables de entorno del servidor.
            </div>
            <details>
                <summary>Últimos correlativos autorizados</summary>
                <p class="muted">Configuración de inicio de series para este establecimiento y punto de venta.</p>
                <div class="grid">
                    ${Object.keys(TYPES).filter(x => x.length === 2).map(t => field('seq' + t, TYPES[t], c.ultimoCorrelativo?.[t] || 0, 'number')).join('')}
                </div>
            </details>
            <div class="actions">
                <button>Guardar datos fiscales del emisor</button>
            </div>
        </form>`;

    $('#company').onsubmit = e => {
        e.preventDefault();
        run(async () => {
            const v = vals(e.target);
            const n = {
                ...c, ...v,
                direccion: { departamento: v.departamento, municipio: v.municipio, distrito: v.distrito, complemento: v.complemento },
                ultimoCorrelativo: {}
            };
            for (const t of Object.keys(TYPES).filter(t => t.length === 2)) {
                n.ultimoCorrelativo[t] = Number(v['seq' + t]);
                delete n['seq' + t];
            }
            for (const k of ['departamento', 'municipio', 'distrito', 'complemento']) delete n[k];
            await api('/empresa', 'PUT', n);
            tell('Datos fiscales del emisor guardados y validados correctamente');
        });
    };
}

async function catalog(kind, q = '') {
    const rows = await api('/catalogos/' + kind + '?q=' + encodeURIComponent(q) + '&page=' + page);
    $('#view').innerHTML = `
        <div class="title">
            <div>
                <h1>${kind === 'clientes' ? 'Catálogo de Clientes' : 'Catálogo de Productos y Servicios'}</h1>
                <p class="muted">Página ${page + 1} · hasta 100 registros por página</p>
            </div>
            <button id="add">Agregar ${kind === 'clientes' ? 'cliente' : 'producto'}</button>
        </div>
        <div class="card">
            <form id="search" class="inline">
                <label>Buscar por nombre o descripción<input name="q" value="${esc(q)}"></label>
                <button>Buscar</button>
                <button type="button" class="secondary" id="excel">Importar Excel</button>
            </form>
        </div>
        <div id="editor"></div>
        <div class="card table-wrap">
            <table>
                <thead>
                    <tr>
                        <th>Código</th>
                        <th>Nombre / Razón Social</th>
                        <th>${kind === 'productos' ? 'Precio neto (sin IVA)' : 'Documento / NIT'}</th>
                        <th>Estado</th>
                        <th></th>
                    </tr>
                </thead>
                <tbody>
                    ${rows.map(r => {
                        const n = JSON.parse(r.json);
                        return `<tr>
                            <td>${esc(r.code)}</td>
                            <td>${esc(r.name)}</td>
                            <td>${kind === 'productos' ? fmt(n.precio) + ' (' + (n.operacion || 'GRAVADA') + ')' : esc(n.numDocumento || n.nit || 'Consumidor Final')}</td>
                            <td><span class="pill ${r.active ? '' : 'warn'}">${r.active ? 'Activo' : 'Inactivo'}</span></td>
                            <td><button class="secondary" data-edit="${r.id}">Editar</button></td>
                        </tr>`;
                    }).join('') || '<tr><td colspan="5" class="empty">Todavía no hay registros en este catálogo.</td></tr>'}
                </tbody>
            </table>
            <div class="actions">
                <button class="secondary" id="prev">Anterior</button>
                <button class="secondary" id="next">Siguiente</button>
            </div>
        </div>`;
    bind('prev', async () => { if (page > 0) page--; await catalog(kind, q); });
    bind('next', async () => { if (rows.length === 100) page++; await catalog(kind, q); });
    $('#search').onsubmit = e => { e.preventDefault(); page = 0; run(() => catalog(kind, vals(e.target).q)); };
    bind('add', () => catalogEditor(kind));
    document.querySelectorAll('[data-edit]').forEach(b => b.onclick = () => catalogEditor(kind, rows.find(r => r.id === Number(b.dataset.edit))));
    bind('excel', () => importer(kind));
}

function catalogEditor(kind, row) {
    const n = row ? JSON.parse(row.json) : {}, a = n.direccion || {};
    const depOptions = { '': 'Seleccione departamento...', ...Object.fromEntries(departamentos.map(d => [d.codigo, d.codigo + ' - ' + d.descripcion])) };

    $('#editor').innerHTML = `
        <form id="entry" class="card">
            <h2>${row ? 'Editar' : 'Nuevo'} ${kind === 'clientes' ? 'Cliente' : 'Producto o Servicio'}</h2>
            <div class="grid">
                ${field('codigo', 'Código interno', n.codigo, 'text', true)}
                ${field('nombre', 'Nombre o razón social', n.nombre, 'text', true)}
                ${kind === 'productos' ? `
                    ${field('precio', 'Precio unitario NETO (sin IVA)', n.precio || 0, 'number', true)}
                    ${field('uniMedida', 'Unidad de medida (CAT-014: 59=Unidad, etc.)', n.uniMedida || 59, 'number')}
                    ${select('tipoItem', 'Tipo de ítem (CAT-011)', { '1': '1 - Bien', '2': '2 - Servicio' }, n.tipoItem || 1)}
                    ${select('operacion', 'Tratamiento tributario', { 'GRAVADA': 'Gravada (IVA 13%)', 'EXENTA': 'Exenta', 'NO_SUJETA': 'No sujeta' }, n.operacion || 'GRAVADA')}
                ` : `
                    ${select('tipoDocumento', 'Tipo de identificación (CAT-022)', { '': 'Sin documento (Consumidor Final)', '36': '36 - NIT', '13': '13 - DUI', '03': '03 - Pasaporte', '37': '37 - Otro' }, n.tipoDocumento || '')}
                    ${field('numDocumento', 'Número de documento (DUI o NIT)', n.numDocumento || '')}
                    ${field('nit', 'NIT (si es CCF o persona jurídica)', n.nit || '')}
                    ${field('nrc', 'NRC (obligatorio para Crédito Fiscal)', n.nrc || '')}
                    ${field('codActividad', 'Código de actividad económica (CAT-019)', n.codActividad || '')}
                    ${field('descActividad', 'Descripción de actividad', n.descActividad || '')}
                    ${field('nombreComercial', 'Nombre comercial', n.nombreComercial || '')}
                    ${field('telefono', 'Teléfono', n.telefono || '')}
                    ${field('correo', 'Correo para envío de DTE', n.correo || '', 'email')}
                    ${select('departamento', 'Departamento', depOptions, a.departamento || '')}
                    ${field('municipio', 'Municipio (código 2 car.)', a.municipio || '')}
                    ${field('distrito', 'Distrito (código 2 car.)', a.distrito || '')}
                    ${field('direccionComplemento', 'Dirección completa', a.complemento || '')}
                    ${field('codPais', 'País (exportación: ej. 9300)', n.codPais || '')}
                    ${field('nombrePais', 'Nombre país (exportación)', n.nombrePais || '')}
                    ${field('complemento', 'Dirección en el extranjero', n.complemento || '')}
                    ${select('tipoPersona', 'Tipo persona (exportación)', { '1': '1 - Natural', '2': '2 - Jurídica' }, n.tipoPersona || 1)}
                `}
                ${row ? select('activo', 'Estado', { 'true': 'Activo', 'false': 'Inactivo' }, String(row.active)) : ''}
            </div>
            <div class="actions">
                <button>Guardar registro</button>
                <button type="button" class="secondary" id="cancel">Cancelar</button>
            </div>
        </form>`;
    $('#entry').querySelectorAll('[type=number]').forEach(x => { x.step = 'any'; x.min = '0'; });
    bind('cancel', () => $('#editor').innerHTML = '');
    $('#entry').onsubmit = e => {
        e.preventDefault();
        run(async () => {
            const v = vals(e.target), data = { ...n, ...v, revision: row?.revision, activo: v.activo !== 'false' };
            if (kind === 'clientes') {
                data.direccion = { departamento: v.departamento, municipio: v.municipio, distrito: v.distrito, complemento: v.direccionComplemento };
                if (v.tipoPersona) data.tipoPersona = Number(v.tipoPersona);
            }
            await api('/catalogos/' + kind + (row ? '/' + row.id : ''), row ? 'PUT' : 'POST', data);
            tell('Registro guardado y validado');
            await catalog(kind);
        });
    };
    $('#editor').scrollIntoView({ behavior: 'smooth' });
}

function importer(kind) {
    importRows = null;
    $('#editor').innerHTML = `
        <div class="card">
            <h2>Importación oficial desde Excel</h2>
            <p class="muted">Suba el archivo con los datos de ${esc(kind)}. Asegúrese de que las columnas numéricas con ceros iniciales (NIT, NRC, DUI, códigos geográficos) estén en formato de texto.</p>
            <input type="file" id="file" accept=".xlsx,.xls">
            <div class="actions">
                <button id="preview-import">Revisar y validar archivo</button>
                <button id="commit-import" class="secondary" disabled>Importar filas válidas</button>
            </div>
            <div id="import-result"></div>
        </div>`;
    bind('preview-import', async () => {
        const f = $('#file').files[0];
        if (!f) throw Error('Seleccione un archivo Excel');
        const form = new FormData();
        form.append('file', f);
        const r = await api('/importar/' + kind + '/preview', 'POST', form);
        importRows = r.puedeImportar ? r.filas : null;
        $('#import-result').innerHTML = `
            <p><b>${r.filas.length}</b> filas válidas; <b>${r.errores.length}</b> errores encontrados.</p>
            ${r.errores.length ? `<div class="note error"><pre>${esc(r.errores.map(x => 'Fila ' + x.fila + ': ' + x.mensaje).join('\n'))}</pre></div>` : ''}
            <div class="table-wrap">
                <table>
                    <tr><th>Código</th><th>Nombre</th></tr>
                    ${r.filas.slice(0, 15).map(x => `<tr><td>${esc(x.codigo)}</td><td>${esc(x.nombre)}</td></tr>`).join('')}
                </table>
            </div>`;
        $('#commit-import').disabled = !importRows;
    });
    bind('commit-import', async () => {
        if (!importRows) return;
        const r = await api('/importar/' + kind + '/confirmar', 'POST', { filas: importRows });
        tell(r.importados + ' registros importados atómicamente');
        await catalog(kind);
    });
}

async function invoiceForm(input = null) {
    [clients, products] = await Promise.all([api('/catalogos/clientes'), api('/catalogos/productos')]);
    clients = clients.filter(x => x.active);
    products = products.filter(x => x.active);
    const n = input || {}, type = n.tipo || '01';

    $('#view').innerHTML = `
        <div class="title">
            <div>
                <h1>${editing ? 'Editar borrador de operación' : 'Nueva operación tributaria'}</h1>
                <p class="muted">Cumple con la estructura de JSON Schemas y catálogos oficiales del Ministerio de Hacienda.</p>
            </div>
        </div>
        <div class="card">
            <div class="actions tabs">
                <button class="secondary" id="invoice-tab">DTE Principal</button>
                <button class="secondary" id="invalid-tab">Evento de Invalidación</button>
                <button class="secondary" id="cont-tab">Evento de Contingencia</button>
            </div>
            <form id="invoice">
                <div class="grid">
                    ${select('tipo', 'Tipo de DTE oficial', TYPES, type)}
                    ${select('clienteId', 'Cliente receptor', { '': 'Consumidor Final (Venta mostrador / sin cliente)', ...Object.fromEntries(clients.map(c => [c.id, c.name])) }, n.clienteId || '')}
                    ${field('documentoId', 'ID del DTE original (para Notas 05 y 06)', n.documentoId || '', 'number')}
                    ${select('condicionOperacion', 'Condición de la operación (CAT-016)', { '1': '1 - Contado', '2': '2 - Crédito' }, n.condicionOperacion || 1)}
                    ${select('formaPago', 'Forma de pago (CAT-014)', {
                        '01': '01 - Billetes y monedas (Efectivo)',
                        '02': '02 - Tarjeta de Débito',
                        '03': '03 - Tarjeta de Crédito',
                        '04': '04 - Cheque',
                        '05': '05 - Transferencia / Depósito bancario',
                        '08': '08 - Vale o cupón',
                        '14': '14 - Dinero electrónico',
                        '99': '99 - Otro'
                    }, n.formaPago || '01')}
                    ${field('diasCredito', 'Días de crédito (si condición es crédito)', n.diasCredito || 0, 'number')}
                    ${field('retencionIva', 'Retención IVA 1% (Gran Contribuyente)', n.retencionIva || 0, 'number')}
                    ${field('retencionRenta', 'Retención Renta 10% (Sujeto Excluido)', n.retencionRenta || 0, 'number')}
                </div>
                <p class="muted">Factura (01) permite venta a Consumidor Final sin registro previo. Comprobante de Crédito Fiscal (03) requiere cliente con NIT, NRC y actividad económica.</p>
                <div class="inline">
                    <label>Agregar producto desde catálogo
                        <select id="product">
                            <option value="">Línea manual...</option>
                            ${products.map(p => `<option value="${p.id}">${esc(p.code + ' · ' + p.name + ' ($' + p.price + ')')}</option>`).join('')}
                        </select>
                    </label>
                    <button type="button" id="add-line">Agregar línea</button>
                </div>
                <div class="table-wrap">
                    <table id="lines">
                        <thead>
                            <tr>
                                <th>Descripción / Código</th>
                                <th>Cantidad</th>
                                <th>Precio neto</th>
                                <th>Descuento neto</th>
                                <th>Operación fiscal</th>
                                <th></th>
                            </tr>
                        </thead>
                        <tbody></tbody>
                    </table>
                </div>
                <details>
                    <summary>Datos complementarios para Factura de Exportación (11)</summary>
                    <div class="grid">
                        ${select('tipoItemExpor', 'Tipo de exportación', { '1': '1 - Bienes nacionales', '2': '2 - Servicios', '3': '3 - Ambos' }, n.exportacion?.tipoItemExpor || 1)}
                        ${field('recintoFiscal', 'Recinto fiscal (CAT-027)', n.exportacion?.recintoFiscal || '01')}
                        ${field('tipoRegimen', 'Tipo de régimen (CAT-028)', n.exportacion?.tipoRegimen || 'EX')}
                        ${field('regimen', 'Descripción del régimen', n.exportacion?.regimen || 'EX-1.1000.000')}
                        ${select('codIncoterms', 'Incoterm (CAT-018)', {
                            '01': '01 - EXW', '02': '02 - FCA', '03': '03 - FAS', '04': '04 - FOB',
                            '05': '05 - CFR', '06': '06 - CIF', '07': '07 - CPT', '08': '08 - CIP',
                            '09': '09 - DAP', '10': '10 - DPU', '11': '11 - DDP'
                        }, n.exportacion?.codIncoterms || '01')}
                        ${field('descIncoterms', 'Descripción Incoterm', n.exportacion?.descIncoterms || 'EXW')}
                        ${field('flete', 'Flete de exportación ($)', n.exportacion?.flete || 0, 'number')}
                        ${field('seguro', 'Seguro de exportación ($)', n.exportacion?.seguro || 0, 'number')}
                    </div>
                    <label class="check">
                        <input type="checkbox" name="permitirFleteSeguro" ${n.exportacion?.permitirFleteSeguro ? 'checked' : ''}>
                        Confirmar flete y seguro según Incoterm pactado
                    </label>
                </details>
                <details>
                    <summary>Emisión durante contingencia (Modelo diferido)</summary>
                    <p class="muted">Para emitir documentos fuera de línea cuando hay falla de energía, internet o caída del servicio MH.</p>
                    <label class="check">
                        <input type="checkbox" name="contingencia" ${n.contingencia ? 'checked' : ''}> Emitir en modo contingencia
                    </label>
                    <div class="grid two">
                        ${select('tipoContingencia', 'Motivo de contingencia (CAT-005)', {
                            '1': '1 - No disponibilidad de sistema del MH',
                            '2': '2 - No disponibilidad de sistema del emisor',
                            '3': '3 - Falla en suministro de energía eléctrica',
                            '4': '4 - Falla en conexión a internet',
                            '5': '5 - Otro'
                        }, n.tipoContingencia || 4)}
                        ${field('motivoContin', 'Explicación detallada del motivo', n.motivoContin || '')}
                    </div>
                </details>
                <label>Observaciones del documento<textarea name="observaciones" rows="2">${esc(n.observaciones)}</textarea></label>
                <div class="actions">
                    <button>Guardar borrador</button>
                </div>
            </form>
        </div>`;

    $('#invoice').querySelectorAll('[type=number]').forEach(e => { e.step = 'any'; e.min = 0; });
    bind('invoice-tab', () => invoiceForm());
    bind('invalid-tab', () => eventForm('INVALIDACION'));
    bind('cont-tab', () => eventForm('CONTINGENCIA'));

    bind('add-line', () => {
        const p = products.find(p => p.id === Number($('#product').value));
        addLine(p ? { ...JSON.parse(p.json), descripcion: p.name, cantidad: 1, descuento: 0 } : {});
    });

    for (const item of n.items || [{}]) addLine(item);

    $('#invoice').onsubmit = e => {
        e.preventDefault();
        run(async () => {
            const v = vals(e.target);
            const items = [...document.querySelectorAll('#lines tbody tr')].map(r => {
                const get = k => r.querySelector('[data-field=' + k + ']').value;
                return {
                    descripcion: get('descripcion'),
                    codigo: get('codigo'),
                    cantidad: get('cantidad'),
                    precio: get('precio'),
                    descuento: get('descuento'),
                    operacion: get('operacion'),
                    uniMedida: Number(r.dataset.unit || 59),
                    tipoItem: Number(r.dataset.type || 1)
                };
            });

            const data = {
                ...n,
                tipo: v.tipo,
                clienteId: v.clienteId ? Number(v.clienteId) : null,
                documentoId: Number(v.documentoId) || null,
                condicionOperacion: Number(v.condicionOperacion),
                formaPago: v.formaPago,
                diasCredito: Number(v.diasCredito),
                retencionIva: v.retencionIva,
                retencionRenta: v.retencionRenta,
                observaciones: v.observaciones || null,
                contingencia: v.contingencia === 'on',
                tipoContingencia: Number(v.tipoContingencia),
                motivoContin: v.motivoContin || null,
                exportacion: {
                    tipoItemExpor: Number(v.tipoItemExpor),
                    recintoFiscal: v.recintoFiscal || null,
                    tipoRegimen: v.tipoRegimen || null,
                    regimen: v.regimen || null,
                    codIncoterms: v.codIncoterms || null,
                    descIncoterms: v.descIncoterms || null,
                    flete: Number(v.flete || 0),
                    seguro: Number(v.seguro || 0),
                    permitirFleteSeguro: v.permitirFleteSeguro === 'on'
                },
                items
            };

            if (editing && data.clienteId) {
                const client = clients.find(c => c.id === data.clienteId);
                if (client) data.cliente = JSON.parse(client.json);
            }

            const d = editing ?
                await api('/documentos/' + editing.id + '?revision=' + editing.revision, 'PUT', data) :
                await api('/documentos', 'POST', data, { 'Idempotency-Key': draftKey });

            editing = null;
            await detail(d.id);
        });
    };
}

function addLine(n) {
    const row = document.createElement('tr');
    row.dataset.unit = n.uniMedida || 59;
    row.dataset.type = n.tipoItem || 1;
    row.innerHTML = `
        <td class="line-name">
            <input data-field="descripcion" value="${esc(n.descripcion || '')}" placeholder="Descripción oficial del bien o servicio" required>
            <input data-field="codigo" value="${esc(n.codigo || '')}" placeholder="Código">
        </td>
        <td><input data-field="cantidad" type="number" min="0.00000001" step="any" value="${esc(n.cantidad || 1)}" required></td>
        <td><input data-field="precio" type="number" min="0" step="any" value="${esc(n.precio || 0)}" required></td>
        <td><input data-field="descuento" type="number" min="0" step="0.01" value="${esc(n.descuento || 0)}"></td>
        <td>
            <select data-field="operacion">
                ${['GRAVADA', 'EXENTA', 'NO_SUJETA'].map(t => `<option ${n.operacion === t ? 'selected' : ''}>${t}</option>`).join('')}
            </select>
            <small>Unidad CAT-014: ${esc(row.dataset.unit)} · ${row.dataset.type === '1' ? 'Bien' : 'Servicio'}</small>
        </td>
        <td><button type="button" class="secondary">Quitar</button></td>`;
    row.querySelector('button').onclick = () => row.remove();
    $('#lines tbody').appendChild(row);
}

async function documents() {
    const rows = await api('/documentos?page=' + page);
    $('#view').innerHTML = `
        <div class="title">
            <div>
                <h1>Documentos Tributarios y Eventos</h1>
                <p class="muted">Página ${page + 1} · Modo ${esc(user.ambiente)}</p>
            </div>
            <button id="new">Nueva operación DTE</button>
        </div>
        <div class="card table-wrap">
            <table>
                <thead>
                    <tr>
                        <th>ID / Fecha</th>
                        <th>Tipo de DTE</th>
                        <th>Número de Control</th>
                        <th>Estado</th>
                        <th>Total</th>
                        <th></th>
                    </tr>
                </thead>
                <tbody>
                    ${rows.map(d => `<tr>
                        <td><b>#${d.id}</b><br><small>${esc(String(d.created_at).slice(0, 10))}</small></td>
                        <td>${esc(TYPES[d.kind] || d.kind)}</td>
                        <td class="code">${esc(d.control_number || 'Borrador sin número')}</td>
                        <td>${badge(d.status)}</td>
                        <td><b>${fmt(d.total)}</b></td>
                        <td><button data-doc="${d.id}" class="secondary">Ver detalle</button></td>
                    </tr>`).join('') || '<tr><td colspan="6" class="empty">No hay documentos registrados todavía.</td></tr>'}
                </tbody>
            </table>
            <div class="actions">
                <button id="prev" class="secondary">Anterior</button>
                <button id="next" class="secondary">Siguiente</button>
            </div>
        </div>`;
    document.querySelectorAll('[data-doc]').forEach(b => b.onclick = () => run(() => detail(Number(b.dataset.doc))));
    bind('new', () => navigate('new'));
    bind('prev', async () => { if (page > 0) page--; await documents(); });
    bind('next', async () => { if (rows.length === 100) page++; await documents(); });
}

async function detail(id) {
    const d = await api('/documentos/' + id), input = JSON.parse(d.input_json);
    let j = d.json ? JSON.parse(d.json) : null;
    const isAccepted = d.status === 'ACEPTADO';
    const isPrepared = d.status === 'PREPARADO';

    $('#view').innerHTML = `
        <div class="title">
            <div>
                <h1>${esc(TYPES[d.kind] || d.kind)} #${d.id}</h1>
                <p class="muted">${esc(d.control_number || 'Borrador editable')} · ${badge(d.status)}</p>
            </div>
            <button id="back" class="secondary">Volver al listado</button>
        </div>
        <div class="card">
            <h2>${esc(j?.receptor?.nombre || input.cliente?.nombre || 'Consumidor Final / Cliente General')}</h2>
            <p>Emisión por <b>${esc(d.created_by)}</b> · Total de la operación: <b>${fmt(d.total)}</b></p>
            <div class="actions">
                ${d.status === 'BORRADOR' ? `
                    <button id="review">Revisar cálculo y validar JSON Schema</button>
                    <button id="prepare" class="secondary">Preparar y fijar correlativo oficial</button>
                    ${d.kind.length === 2 ? '<button id="edit" class="secondary">Editar borrador</button>' : ''}
                ` : ''}
                ${isPrepared ? '<button id="send">Firmar y transmitir al MH</button>' : ''}
                ${['INCIERTO', 'ACEPTADO', 'RECHAZADO'].includes(d.status) && d.kind.length === 2 ? '<button id="query" class="secondary">Consultar estado en Hacienda</button>' : ''}
                ${j ? `
                    <a href="/api/documentos/${id}/pdf" target="_blank" rel="noopener" class="button">Ver Representación Gráfica (PDF)</a>
                    <a href="/api/documentos/${id}/archivos">Descargar paquete oficial (.ZIP)</a>
                ` : ''}
                ${isAccepted && d.kind.length === 2 ? '<button id="mail" class="secondary">Enviar DTE por correo</button>' : ''}
            </div>
            ${d.status === 'INCIERTO' ? `
                <div class="note warn">
                    <b>Respuesta incierta:</b> La transmisión no recibió confirmación inmediata del MH.
                    El sistema bloquea reenvíos automáticos para evitar duplicados. Utilice el botón "Consultar estado en Hacienda".
                </div>` : ''}
            <div id="review-result"></div>
        </div>
        ${j ? `
            <div class="card">
                <h2>Identificación oficial del DTE</h2>
                <p><b>Código de Generación (UUID):</b> <span class="code">${esc(d.generation)}</span></p>
                <p><b>Número de Control:</b> <span class="code">${esc(d.control_number || 'Provisional')}</span></p>
                <p><b>Sello de Recepción MH:</b> ${d.seal ? `<span class="pill">${esc(d.seal)}</span>` : '<span class="pill warn">Pendiente</span>'}</p>
                <details>
                    <summary>Ver JSON electrónico oficial generado</summary>
                    <pre>${esc(JSON.stringify(j, null, 2))}</pre>
                </details>
            </div>` : ''}
        ${d.response_json ? `
            <div class="card">
                <h2>Respuesta del Ministerio de Hacienda</h2>
                <pre>${esc(JSON.stringify(JSON.parse(d.response_json), null, 2))}</pre>
            </div>` : ''}`;

    bind('back', () => documents());
    bind('review', async () => {
        const p = await api('/documentos/' + id + '/preview');
        $('#review-result').innerHTML = `
            <div class="note info">
                ✓ <b>Estructura válida:</b> Cumple 100% con los esquemas oficiales del MH. Total calculado: <b>${fmt(p.resumen?.totalPagar)}</b>
            </div>
            <pre>${esc(JSON.stringify(p, null, 2))}</pre>`;
    });
    bind('prepare', async () => {
        await api('/documentos/' + id + '/preparar', 'POST');
        tell('Documento preparado con correlativo y UUID oficial congelado');
        await detail(id);
    });
    bind('send', async () => {
        await api('/documentos/' + id + '/transmitir', 'POST');
        await detail(id);
    });
    bind('query', async () => {
        await api('/documentos/' + id + '/consultar', 'POST');
        await detail(id);
    });
    bind('edit', async () => {
        editing = d;
        await invoiceForm(input);
    });
    bind('mail', async () => {
        if (!confirm('¿Desea enviar la representación gráfica y DTE firmado al correo del receptor?')) return;
        await api('/documentos/' + id + '/correo', 'POST');
        tell('Correo entregado satisfactoriamente al servidor SMTP');
    });
}

function eventForm(kind) {
    const inv = kind === 'INVALIDACION';
    const date = new Date().toLocaleDateString('en-CA', { timeZone: 'America/El_Salvador' });
    draftKey = uuid();

    $('#view').innerHTML = `
        <div class="title">
            <h1>${TYPES[kind]}</h1>
            <button class="secondary" id="back">Volver</button>
        </div>
        <form class="card" id="event">
            <p class="muted">Complete los datos requeridos por la normativa de Hacienda para este evento tributario.</p>
            <div class="grid">
                ${inv ? `
                    ${field('documentoId', 'ID interno del DTE aceptado a anular', '', 'number', true)}
                    ${select('tipoAnulacion', 'Tipo de invalidación (CAT-024)', {
                        '1': '1 - Rescisión de la operación',
                        '2': '2 - Rescisión por error en datos del DTE',
                        '3': '3 - No se realizó la operación'
                    }, 2)}
                    ${field('codigoGeneracionR', 'Código generación del DTE de reemplazo (si aplica)')}
                    ${field('motivoAnulacion', 'Motivo detallado de la anulación', '', 'text', true)}
                    ${field('nombreSolicita', 'Nombre de quien solicita la anulación', '', 'text', true)}
                    ${select('tipDocSolicita', 'Tipo doc. solicitante', { '13': '13 - DUI', '36': '36 - NIT', '03': '03 - Pasaporte', '37': '37 - Otro' }, '13')}
                    ${field('numDocSolicita', 'Número doc. solicitante', '', 'text', true)}
                ` : `
                    ${field('documentos', 'IDs de DTEs emitidos en contingencia (separados por coma)', '', 'text', true)}
                    ${field('fInicio', 'Fecha de inicio de contingencia', date, 'date', true)}
                    ${field('hInicio', 'Hora de inicio (HH:mm:ss)', '08:00:00', 'text', true)}
                    ${field('fFin', 'Fecha fin de contingencia', date, 'date', true)}
                    ${field('hFin', 'Hora fin (HH:mm:ss)', '17:00:00', 'text', true)}
                    ${select('tipoContingencia', 'Motivo de contingencia (CAT-005)', {
                        '1': '1 - No disponibilidad de sistema del MH',
                        '2': '2 - No disponibilidad de sistema del emisor',
                        '3': '3 - Falla en suministro de energía eléctrica',
                        '4': '4 - Falla en conexión a internet',
                        '5': '5 - Otro'
                    }, 4)}
                    ${field('motivoContingencia', 'Descripción del motivo', '', 'text', true)}
                `}
                ${field('nombreResponsable', 'Nombre del responsable del emisor', '', 'text', true)}
                ${select('tipoResponsable', 'Tipo doc. responsable', { '13': '13 - DUI', '36': '36 - NIT', '03': '03 - Pasaporte', '37': '37 - Otro' }, '13')}
                ${field('numeroResponsable', 'Número doc. responsable', '', 'text', true)}
            </div>
            <div class="actions">
                <button>Guardar borrador del evento</button>
            </div>
        </form>`;

    bind('back', () => navigate('new'));

    $('#event').onsubmit = e => {
        e.preventDefault();
        run(async () => {
            const v = vals(e.target);
            let n;
            if (inv) {
                n = {
                    tipo: kind,
                    documentoId: Number(v.documentoId),
                    codigoGeneracionR: v.codigoGeneracionR || null,
                    motivo: {
                        tipoAnulacion: Number(v.tipoAnulacion),
                        motivoAnulacion: v.motivoAnulacion,
                        nombreResponsable: v.nombreResponsable,
                        tipDocResponsable: v.tipoResponsable,
                        numDocResponsable: v.numeroResponsable,
                        nombreSolicita: v.nombreSolicita,
                        tipDocSolicita: v.tipDocSolicita,
                        numDocSolicita: v.numDocSolicita
                    }
                };
            } else {
                n = {
                    tipo: kind,
                    documentos: v.documentos.split(',').map(x => Number(x.trim())),
                    responsable: {
                        nombreResponsable: v.nombreResponsable,
                        tipoDocResponsable: v.tipoResponsable,
                        numeroDocResponsable: v.numeroResponsable
                    },
                    motivo: {
                        fInicio: v.fInicio,
                        hInicio: v.hInicio,
                        fFin: v.fFin,
                        hFin: v.hFin,
                        tipoContingencia: Number(v.tipoContingencia),
                        motivoContingencia: v.motivoContingencia
                    }
                };
            }
            const d = await api('/documentos', 'POST', n, { 'Idempotency-Key': draftKey });
            await detail(d.id);
        });
    };
}

async function users() {
    const rows = await api('/usuarios');
    $('#view').innerHTML = `
        <h1>Control de Acceso y Usuarios</h1>
        <p class="muted">Gestión de cuentas locales de la oficina con control de permisos y contraseñas BCrypt.</p>
        <div class="card">
            <table>
                <tr><th>Usuario</th><th>Rol</th><th>Estado</th></tr>
                ${rows.map(x => `<tr>
                    <td><b>${esc(x.username)}</b></td>
                    <td>${esc(x.role)}</td>
                    <td><span class="pill">${x.enabled ? 'Activo' : 'Bloqueado'}</span></td>
                </tr>`).join('')}
            </table>
        </div>
        <form id="user-form" class="card">
            <h2>Crear nueva cuenta</h2>
            <div class="grid">
                ${field('usuario', 'Usuario', '', 'text', true)}
                ${field('password', 'Contraseña (mínimo 12 caracteres)', '', 'password', true)}
                ${select('rol', 'Permisos', { ADMIN: 'ADMIN - Administrador', OPERADOR: 'OPERADOR - Emisión y Catálogos', CONSULTA: 'CONSULTA - Solo lectura' }, 'OPERADOR')}
            </div>
            <div class="actions">
                <button ${rows.length >= 3 ? 'disabled' : ''}>Crear usuario</button>
            </div>
        </form>
        <form id="password-form" class="card">
            <h2>Actualizar mi contraseña</h2>
            <div class="grid two">
                ${field('actual', 'Contraseña actual', '', 'password', true)}
                ${field('nueva', 'Nueva contraseña', '', 'password', true)}
            </div>
            <div class="actions">
                <button>Cambiar contraseña</button>
            </div>
        </form>`;

    $('#user-form').onsubmit = e => {
        e.preventDefault();
        run(async () => {
            await api('/usuarios', 'POST', vals(e.target));
            await users();
        });
    };

    $('#password-form').onsubmit = e => {
        e.preventDefault();
        run(async () => {
            await api('/password', 'POST', vals(e.target));
            e.target.reset();
            tell('Contraseña actualizada correctamente');
        });
    };
}

async function audit() {
    const rows = await api('/auditoria?page=' + page);
    $('#view').innerHTML = `
        <h1>Registro de Auditoría</h1>
        <p class="muted">Trazabilidad completa de operaciones registradas. Página ${page + 1}.</p>
        <div class="card table-wrap">
            <table>
                <thead>
                    <tr><th>Fecha y Hora</th><th>Usuario</th><th>Acción</th><th>Entidad</th><th>Detalle</th></tr>
                </thead>
                <tbody>
                    ${rows.map(r => `<tr>
                        <td><small>${esc(r.occurred_at)}</small></td>
                        <td><b>${esc(r.actor)}</b></td>
                        <td><span class="pill">${esc(r.action)}</span></td>
                        <td>${esc(r.entity)}</td>
                        <td><small>${esc(r.detail)}</small></td>
                    </tr>`).join('') || '<tr><td colspan="5" class="empty">No hay registros de auditoría.</td></tr>'}
                </tbody>
            </table>
            <div class="actions">
                <button id="prev" class="secondary">Anterior</button>
                <button id="next" class="secondary">Siguiente</button>
            </div>
        </div>`;
    bind('prev', async () => { if (page > 0) page--; await audit(); });
    bind('next', async () => { if (rows.length === 100) page++; await audit(); });
}

run(async () => {
    await token();
    await boot();
});
