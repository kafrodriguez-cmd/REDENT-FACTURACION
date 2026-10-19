"""Regenera los contratos legibles de API; no conecta con Hacienda."""
import json
from pathlib import Path
root=Path(__file__).resolve().parents[1]
paths={}
routes=[
 ('get','/api/csrf','Obtener CSRF',False),('post','/login','Iniciar sesión',False),('post','/logout','Cerrar sesión',True),
 ('get','/api/me','Identidad y configuración',True),('get','/api/dashboard','Resumen',True),
 ('get','/api/empresa','Leer emisor',True),('put','/api/empresa','Configurar emisor (ADMIN)',True),
 ('get','/api/catalogos/{kind}','Listar catálogo',True),('post','/api/catalogos/{kind}','Crear registro',True),('put','/api/catalogos/{kind}/{id}','Editar con revisión',True),
 ('post','/api/importar/{kind}/preview','Revisar Excel multipart file',True),('post','/api/importar/{kind}/confirmar','Importar filas atómicamente',True),
 ('get','/api/documentos','Listar documentos',True),('post','/api/documentos','Crear borrador con Idempotency-Key',True),
 ('get','/api/documentos/{id}','Leer documento',True),('put','/api/documentos/{id}','Editar borrador con revisión',True),
 ('get','/api/documentos/{id}/preview','Vista previa validada',True),('post','/api/documentos/{id}/preparar','Congelar documento y asignar serie',True),
 ('post','/api/documentos/{id}/transmitir','Transmitir a MH pruebas',True),('post','/api/documentos/{id}/consultar','Consultar DTE en MH',True),
 ('post','/api/documentos/{id}/correo','Enviar correo al receptor',True),('get','/api/documentos/{id}/pdf','Representación PDF',True),
 ('get','/api/documentos/{id}/archivos','Descargar ZIP',True),('get','/api/documentos/{id}/intentos','Historial transmisión',True),
 ('get','/api/esquemas','Versiones de esquemas',True),('get','/api/esquemas/{type}','Esquema oficial',True),('post','/api/validar/{type}','Validar JSON sin persistir',True),
 ('get','/api/usuarios','Listar usuarios ADMIN',True),('post','/api/usuarios','Crear usuario ADMIN',True),('post','/api/password','Cambiar clave propia',True),('get','/api/auditoria','Listar auditoría ADMIN',True)]
for method,path,title,auth in routes:
 op={'summary':title,'operationId':method+'_'+path.strip('/').replace('/','_').replace('{','').replace('}',''),'responses':{'200':{'description':'Resultado de la operación; ver API.md'},'400':{'description':'Solicitud inválida'},'401':{'description':'Sesión requerida'},'403':{'description':'CSRF o permiso insuficiente'},'409':{'description':'Conflicto o integración no habilitada'},'422':{'description':'Errores de esquema MH'}},'parameters':[]}
 if auth:op['security']=[{'session':[]}]
 for name in ['kind','id','type']:
  if '{'+name+'}' in path:op['parameters'].append({'name':name,'in':'path','required':True,'schema':{'type':'integer' if name=='id' else 'string'}})
 if method in ['post','put']:op['parameters'].append({'name':'X-CSRF-TOKEN','in':'header','required':True,'schema':{'type':'string'}})
 if path=='/api/documentos' and method=='post':op['parameters'].append({'name':'Idempotency-Key','in':'header','required':True,'schema':{'type':'string','minLength':8,'maxLength':100}})
 if path in ['/api/documentos','/api/auditoria','/api/catalogos/{kind}'] and method=='get':op['parameters'].append({'name':'page','in':'query','schema':{'type':'integer','minimum':0,'default':0}})
 if path=='/api/catalogos/{kind}' and method=='get':op['parameters'].append({'name':'q','in':'query','schema':{'type':'string'}})
 if path=='/api/documentos/{id}' and method=='put':op['parameters'].append({'name':'revision','in':'query','required':True,'schema':{'type':'integer','minimum':0}})
 if path=='/login':op['requestBody']={'required':True,'content':{'application/x-www-form-urlencoded':{'schema':{'type':'object','required':['username','password'],'properties':{'username':{'type':'string'},'password':{'type':'string','format':'password'}}}}}}
 elif path.endswith('/preview') and '/importar/' in path:op['requestBody']={'required':True,'content':{'multipart/form-data':{'schema':{'type':'object','required':['file'],'properties':{'file':{'type':'string','format':'binary'}}}}}}
 elif method in ['post','put'] and path in ['/api/empresa','/api/catalogos/{kind}','/api/catalogos/{kind}/{id}','/api/documentos','/api/documentos/{id}','/api/usuarios','/api/password','/api/importar/{kind}/confirmar','/api/validar/{type}']:
  op['requestBody']={'required':True,'description':'Campos y ejemplos en API.md. El contrato de entrada detallado está en los servicios y los esquemas MH disponibles por GET /api/esquemas/{type}.','content':{'application/json':{'schema':{'type':'object','additionalProperties':True}}}}
 if path.endswith('/pdf'):op['responses']['200']={'description':'PDF','content':{'application/pdf':{'schema':{'type':'string','format':'binary'}}}}
 if path.endswith('/archivos'):op['responses']['200']={'description':'ZIP','content':{'application/zip':{'schema':{'type':'string','format':'binary'}}}}
 if path=='/logout':op['responses']['204']={'description':'Sesión cerrada'};op['responses'].pop('200')
 paths.setdefault(path,{})[method]=op
spec={'openapi':'3.0.3','info':{'title':'REDENT Local — API de pruebas','version':'0.9.0','description':'Inventario de rutas. Solo ambiente MH 00. Lea API.md y los límites de LEEME-PRIMERO.md.'},'servers':[{'url':'http://localhost:8080'}],'paths':paths,'components':{'securitySchemes':{'session':{'type':'apiKey','in':'cookie','name':'JSESSIONID'}}}}
(root/'docs/openapi.json').write_text(json.dumps(spec,ensure_ascii=False,indent=2),encoding='utf-8')
items=[]
def request(name,method,path,body=None,form=False,csrf=False,test=None,headers=None):
 h=headers or []
 if csrf:h.append({'key':'X-CSRF-TOKEN','value':'{{csrf}}'})
 r={'method':method,'header':h,'url':'{{baseUrl}}'+path}
 if body is not None:
  if form:r['body']={'mode':'urlencoded','urlencoded':[{'key':k,'value':v,'type':'text'} for k,v in body.items()]}
  else:r['body']={'mode':'raw','raw':json.dumps(body,ensure_ascii=False,indent=2),'options':{'raw':{'language':'json'}}};h.append({'key':'Content-Type','value':'application/json'})
 i={'name':name,'request':r}
 if test:i['event']=[{'listen':'test','script':{'type':'text/javascript','exec':[test]}}]
 items.append(i)
request('1. CSRF antes de login','GET','/api/csrf',test="pm.collectionVariables.set('csrf', pm.response.json().token);")
request('2. Login (cookie jar habilitado)','POST','/login',{'username':'{{usuario}}','password':'{{password}}'},form=True,csrf=True)
request('3. Renovar CSRF después del login','GET','/api/csrf',test="pm.collectionVariables.set('csrf', pm.response.json().token);")
request('4. Mi cuenta','GET','/api/me')
request('5. Empresa','GET','/api/empresa')
request('6. Clientes','GET','/api/catalogos/clientes')
request('7. Borrador de prueba (configure clienteId)','POST','/api/documentos',{'tipo':'01','clienteId':1,'condicionOperacion':1,'formaPago':'01','items':[{'codigo':'PRUEBA','descripcion':'Producto ficticio','cantidad':'1','precio':'10.00','descuento':'0.00','operacion':'GRAVADA','tipoItem':1,'uniMedida':59}]},csrf=True,headers=[{'key':'Idempotency-Key','value':'{{requestKey}}'}],test="if(pm.response.code===200) pm.collectionVariables.set('documentoId', pm.response.json().id);")
request('8. Revisar','GET','/api/documentos/{{documentoId}}/preview')
request('9. Preparar','POST','/api/documentos/{{documentoId}}/preparar',csrf=True)
request('10. Transmitir SOLO con MH pruebas configurado','POST','/api/documentos/{{documentoId}}/transmitir',csrf=True)
request('11. Consultar estado','POST','/api/documentos/{{documentoId}}/consultar',csrf=True)
request('12. Cerrar sesión','POST','/logout',csrf=True)
collection={'info':{'name':'REDENT Local 0.9 — PRUEBAS','schema':'https://schema.getpostman.com/json/collection/v2.1.0/collection.json','description':'Complete sus variables localmente. No exporte contraseñas. requestKey debe ser una UUID estable por operación; cámbiela para una venta diferente.'},'variable':[{'key':k,'value':v} for k,v in {'baseUrl':'http://localhost:8080','usuario':'admin','password':'','csrf':'','documentoId':'','requestKey':'CAMBIE-POR-UNA-UUID-POR-OPERACION'}.items()],'item':items}
(root/'docs/REDENT.postman_collection.json').write_text(json.dumps(collection,ensure_ascii=False,indent=2),encoding='utf-8')
print('OpenAPI y colección Postman generados.')
