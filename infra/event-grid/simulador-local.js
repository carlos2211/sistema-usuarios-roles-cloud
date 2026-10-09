// ---------------------------------------------------------------------------
// Simulador local de Azure Event Grid (solo para desarrollo).
//
// Hace de topic en http://localhost:7199/api/events: recibe los eventos que
// publican las funciones (mismo formato y clave aeg-sas-key que el topic real)
// y los entrega a las funciones locales (func start en el puerto 7071) con las
// mismas suscripciones y filtros que crea configurar-event-grid.sh.
//
// Uso:   node infra/event-grid/simulador-local.js
// En local.settings.json:
//   "EVENTGRID_TOPIC_ENDPOINT": "http://localhost:7199/api/events",
//   "EVENTGRID_TOPIC_KEY": "clave-local"
// ---------------------------------------------------------------------------
const http = require('http');

const PUERTO = Number(process.env.PUERTO || 7199);
const CLAVE = process.env.CLAVE || 'clave-local';
const FUNCIONES = (process.env.FUNCIONES_URL || 'http://localhost:7071')
  + '/runtime/webhooks/EventGrid?functionName=';
const REINTENTOS = 3;

// Debe coincidir con las suscripciones de configurar-event-grid.sh
const SUSCRIPCIONES = [
  { nombre: 'sub-auditoria', funcion: 'auditoria-eventos', tipos: null },
  {
    nombre: 'sub-notificaciones',
    funcion: 'notificaciones-eventos',
    tipos: ['Usuario.Creado', 'Usuario.Desactivado', 'UsuarioRol.Asignado', 'UsuarioRol.Revocado', 'Alerta.Seguridad'],
  },
  { nombre: 'sub-seguridad', funcion: 'procesador-seguridad', tipos: ['UsuarioRol.Asignado'] },
  { nombre: 'sub-desactivacion', funcion: 'procesador-desactivacion', tipos: ['Usuario.Desactivado'] },
  { nombre: 'sub-rol-por-defecto', funcion: 'procesador-rol-por-defecto', tipos: ['Usuario.Creado'] },
  { nombre: 'sub-rol-eliminado', funcion: 'procesador-rol-eliminado', tipos: ['Rol.Eliminado'] },
];

function hora() {
  return new Date().toTimeString().slice(0, 8);
}

function entregar(sub, evento, intento = 1) {
  const cuerpo = JSON.stringify([evento]);

  const peticion = http.request(FUNCIONES + sub.funcion, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'aeg-event-type': 'Notification',
      'Content-Length': Buffer.byteLength(cuerpo),
    },
  }, respuesta => {
    respuesta.resume();
    const ok = respuesta.statusCode < 300;
    console.log(`${hora()}   ${ok ? 'entregado' : 'ERROR ' + respuesta.statusCode} -> ${sub.funcion}`
      + (intento > 1 ? ` (intento ${intento})` : ''));

    if (!ok && intento < REINTENTOS) {
      setTimeout(() => entregar(sub, evento, intento + 1), 2000);
    }
  });

  peticion.on('error', error => {
    console.log(`${hora()}   ERROR -> ${sub.funcion}: ${error.message} (¿está corriendo func start?)`);
  });

  peticion.end(cuerpo);
}

http.createServer((peticion, respuesta) => {
  let datos = '';
  peticion.on('data', parte => (datos += parte));
  peticion.on('end', () => {
    if (peticion.url !== '/api/events' || peticion.headers['aeg-sas-key'] !== CLAVE) {
      console.log(`${hora()} RECHAZADO ${peticion.url}: clave aeg-sas-key ausente o incorrecta`);
      respuesta.writeHead(401);
      return respuesta.end();
    }

    let eventos;
    try {
      eventos = JSON.parse(datos);
    } catch (error) {
      respuesta.writeHead(400);
      return respuesta.end();
    }

    respuesta.writeHead(200);
    respuesta.end();

    for (const evento of eventos) {
      console.log(`${hora()} ${evento.eventType} (${evento.subject})`);
      for (const sub of SUSCRIPCIONES) {
        if (!sub.tipos || sub.tipos.includes(evento.eventType)) {
          entregar(sub, evento);
        }
      }
    }
  });
}).listen(PUERTO, () => {
  console.log(`Event Grid local escuchando en http://localhost:${PUERTO}/api/events`);
  console.log(`Entrega a ${FUNCIONES.replace('?functionName=', '')}`);
});
