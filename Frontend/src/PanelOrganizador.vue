<script setup>
import { computed, onMounted, ref } from 'vue'
import { apiRequest } from './api'
import { session } from './session'

// Panel del organizador (rol ORGANIZADOR). Usa MS1 por el Gateway:
//   GET /eventos?organizadorId= · GET /eventos/{id} · GET /lugares · POST /eventos
//   PUT /eventos/{id} { fechaHora, lugarId } · PUT /eventos/{id}/sectores/{sectorId}/precio
//   PUT /eventos/{id}/limite-entradas-por-cliente · PUT /eventos/{id}/publicar
//   GET|POST /eventos/{id}/personal-acceso · DELETE /eventos/{id}/personal-acceso/{usuarioId}
// MS1 no lista borradores: la búsqueda solo trae eventos publicados o agotados. Por eso los ids de los
// borradores creados desde esta pantalla se recuerdan en el navegador y se consultan de a uno.

const EVENT_TYPES = ['MUSICA', 'TEATRO', 'DEPORTE']
const STATE_LABELS = {
  borrador: 'Borrador', publicado: 'Publicado', agotado: 'Agotado', cancelado: 'Cancelado', finalizado: 'Finalizado',
}

const view = ref('list')
const events = ref([])
const venues = ref([])
const detail = ref(null)
const staff = ref([])
const loading = ref(false)
const busy = ref('')
const errorMessage = ref('')
const successMessage = ref('')

const form = ref(emptyForm())
const prices = ref({})
const limit = ref('')
const editWhen = ref('')
const editVenue = ref('')
const staffEmail = ref('')
// Fila virtual (MS7): null = sin consultar, 'none' = el evento todavía no tiene fila configurada.
const queue = ref(null)
const queueCupo = ref(10)
const queueMinutes = ref(10)
const QUEUE_STATE_LABELS = { ACTIVA: 'Activa', PAUSADA: 'Pausada', DESACTIVADA: 'Desactivada' }
const canQueue = computed(() => ['publicado', 'agotado'].includes(detail.value?.estado))

const storageKey = computed(() => `ticketya.borradores.${session.value?.userId}`)
const isDraft = computed(() => detail.value?.estado === 'borrador')
const canEditEvent = computed(() => ['borrador', 'publicado', 'agotado'].includes(detail.value?.estado))
const sortedEvents = computed(() => [...events.value].sort((a, b) => {
  const draft = Number(b.estado === 'borrador') - Number(a.estado === 'borrador')
  return draft || Date.parse(a.fechaHora) - Date.parse(b.fechaHora)
}))
const formValid = computed(() => {
  const f = form.value
  return Boolean(f.nombre.trim() && f.descripcion.trim() && f.tipo.trim() && f.participantes.trim()
    && f.fechaHora && f.lugarId && Number(f.limite) >= 1)
})
const pricesValid = computed(() => (detail.value?.sectores || []).every((s) => {
  const value = prices.value[s.sectorId]
  return value !== '' && value !== null && value !== undefined && Number(value) >= 0
}))

function emptyForm() {
  return { nombre: '', descripcion: '', tipo: 'MUSICA', participantes: '', fechaHora: '', lugarId: '', limite: 4 }
}

function formatDate(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function formatPrice(value) {
  return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 2 }).format(value || 0)
}

/** Valor de un input datetime-local ("2027-03-15T21:00", hora local) a ISO UTC. */
function toIsoUtc(localValue) {
  return new Date(localValue).toISOString()
}

function toLocalInput(iso) {
  const date = new Date(iso)
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function seatSummary(sector) {
  if (sector.tipo === 'NUMERADO') return `Numerado · ${sector.filas} filas × ${sector.asientosPorFila} asientos`
  return `Sin numerar · capacidad ${sector.capacidad}`
}

function reportError(error, fallback) {
  errorMessage.value = error instanceof Error ? error.message : fallback
}

function clearNotices() {
  errorMessage.value = ''
  successMessage.value = ''
}

function readDraftIds() {
  try {
    const ids = JSON.parse(window.localStorage.getItem(storageKey.value) || '[]')
    return Array.isArray(ids) ? ids : []
  } catch {
    return []
  }
}

function writeDraftIds(ids) {
  try {
    window.localStorage.setItem(storageKey.value, JSON.stringify([...new Set(ids)]))
  } catch {
    // Sin almacenamiento: el borrador sigue existiendo en MS1 pero no se recuerda acá.
  }
}

async function loadEvents() {
  loading.value = true
  clearNotices()
  try {
    const query = new URLSearchParams({ organizadorId: session.value.userId, size: '100', orden: 'fecha', direccion: 'asc' })
    const result = await apiRequest(`/eventos?${query}`)
    const published = (result?.contenido || []).map((event) => ({ ...event }))
    const known = new Set(published.map((event) => event.id))
    const drafts = []
    const keep = []
    for (const id of readDraftIds()) {
      if (known.has(id)) continue // ya publicado: figura en la búsqueda
      try {
        const event = await apiRequest(`/eventos/${encodeURIComponent(id)}`)
        if (event.estado === 'borrador') {
          keep.push(id)
          drafts.push({
            id: event.id, nombre: event.nombre, tipo: event.tipo, fechaHora: event.fechaHora,
            ciudad: event.lugar?.ciudad, estado: 'borrador', ventaSuspendida: false, precioMinimo: null,
          })
        } else if (event.estado === 'cancelado' || event.estado === 'finalizado') {
          drafts.push({ ...event, ciudad: event.lugar?.ciudad })
        }
      } catch {
        // Un borrador que ya no existe se olvida.
      }
    }
    writeDraftIds(keep)
    events.value = [...drafts, ...published]
  } catch (error) {
    reportError(error, 'No se pudieron cargar tus eventos.')
  } finally {
    loading.value = false
  }
}

async function loadVenues() {
  if (venues.value.length) return
  try {
    const result = await apiRequest('/lugares')
    venues.value = Array.isArray(result) ? result : []
  } catch (error) {
    reportError(error, 'No se pudieron cargar los lugares.')
  }
}

function startNew() {
  clearNotices()
  form.value = emptyForm()
  view.value = 'new'
  loadVenues()
}

async function createEvent() {
  if (!formValid.value) return
  busy.value = 'create'
  clearNotices()
  try {
    const f = form.value
    const created = await apiRequest('/eventos', {
      method: 'POST',
      body: JSON.stringify({
        nombre: f.nombre.trim(),
        descripcion: f.descripcion.trim(),
        tipo: f.tipo.trim().toUpperCase(),
        participantes: f.participantes.split(',').map((p) => p.trim()).filter(Boolean),
        fechaHora: toIsoUtc(f.fechaHora),
        lugarId: f.lugarId,
        limiteEntradasPorCliente: Number(f.limite),
      }),
    })
    writeDraftIds([...readDraftIds(), created.id])
    await openEvent(created.id)
    successMessage.value = 'Borrador creado. Ahora poné el precio de cada sector y publicalo.'
  } catch (error) {
    reportError(error, 'No se pudo crear el evento.')
  } finally {
    busy.value = ''
  }
}

async function openEvent(id) {
  loading.value = true
  clearNotices()
  try {
    detail.value = await apiRequest(`/eventos/${encodeURIComponent(id)}`)
    prices.value = Object.fromEntries((detail.value.sectores || []).map((s) => [s.sectorId, s.precio ?? '']))
    limit.value = ''
    editWhen.value = toLocalInput(detail.value.fechaHora)
    editVenue.value = detail.value.lugar?.id || ''
    staff.value = []
    queue.value = null
    view.value = 'detail'
    loadVenues()
    loadStaff()
    loadQueue()
  } catch (error) {
    reportError(error, 'No se pudo abrir el evento.')
  } finally {
    loading.value = false
  }
}

async function refreshDetail() {
  detail.value = await apiRequest(`/eventos/${encodeURIComponent(detail.value.id)}`)
  prices.value = Object.fromEntries((detail.value.sectores || []).map((s) => [s.sectorId, s.precio ?? '']))
}

async function loadStaff() {
  try {
    const result = await apiRequest(`/eventos/${encodeURIComponent(detail.value.id)}/personal-acceso`)
    staff.value = Array.isArray(result) ? result : []
  } catch {
    staff.value = []
  }
}

async function loadQueue() {
  if (!canQueue.value) return
  try {
    queue.value = await apiRequest(`/filas/eventos/${encodeURIComponent(detail.value.id)}`)
    queueCupo.value = queue.value.cupo
    queueMinutes.value = queue.value.duracionMinutos
  } catch (error) {
    if (error.status === 404) queue.value = 'none'
    else reportError(error, 'No se pudo consultar la fila virtual.')
  }
}

async function saveQueueConfig() {
  busy.value = 'queue-config'
  clearNotices()
  try {
    await apiRequest(`/filas/eventos/${encodeURIComponent(detail.value.id)}/configuracion`, {
      method: 'PUT', body: JSON.stringify({ cupo: Number(queueCupo.value), duracionMinutos: Number(queueMinutes.value) }),
    })
    await loadQueue()
    successMessage.value = 'Fila configurada. Activala para que los clientes tengan que hacer fila.'
  } catch (error) {
    reportError(error, 'No se pudo configurar la fila.')
  } finally {
    busy.value = ''
  }
}

async function setQueueState(state) {
  busy.value = `queue-${state}`
  clearNotices()
  try {
    await apiRequest(`/filas/eventos/${encodeURIComponent(detail.value.id)}/estado`, {
      method: 'PUT', body: JSON.stringify({ estado: state }),
    })
    await loadQueue()
    successMessage.value = `Fila ${QUEUE_STATE_LABELS[state].toLowerCase()}.`
  } catch (error) {
    reportError(error, 'No se pudo cambiar el estado de la fila.')
  } finally {
    busy.value = ''
  }
}

async function savePricesAndPublish() {
  if (!pricesValid.value) return
  busy.value = 'publish'
  clearNotices()
  try {
    const id = encodeURIComponent(detail.value.id)
    for (const sector of detail.value.sectores) {
      await apiRequest(`/eventos/${id}/sectores/${encodeURIComponent(sector.sectorId)}/precio`, {
        method: 'PUT', body: JSON.stringify({ precio: Number(prices.value[sector.sectorId]) }),
      })
    }
    await apiRequest(`/eventos/${id}/publicar`, { method: 'PUT' })
    await refreshDetail()
    loadQueue()
    successMessage.value = 'Evento publicado: ya se puede reservar y comprar.'
  } catch (error) {
    reportError(error, 'No se pudo publicar el evento.')
    try { await refreshDetail() } catch { /* se conserva lo que había */ }
  } finally {
    busy.value = ''
  }
}

async function savePricesOnly() {
  if (!pricesValid.value) return
  busy.value = 'prices'
  clearNotices()
  try {
    const id = encodeURIComponent(detail.value.id)
    for (const sector of detail.value.sectores) {
      await apiRequest(`/eventos/${id}/sectores/${encodeURIComponent(sector.sectorId)}/precio`, {
        method: 'PUT', body: JSON.stringify({ precio: Number(prices.value[sector.sectorId]) }),
      })
    }
    await refreshDetail()
    successMessage.value = 'Precios guardados.'
  } catch (error) {
    reportError(error, 'No se pudieron guardar los precios.')
  } finally {
    busy.value = ''
  }
}

async function saveLimit() {
  busy.value = 'limit'
  clearNotices()
  try {
    await apiRequest(`/eventos/${encodeURIComponent(detail.value.id)}/limite-entradas-por-cliente`, {
      method: 'PUT', body: JSON.stringify({ limiteEntradasPorCliente: Number(limit.value) }),
    })
    successMessage.value = `Límite por cliente actualizado a ${Number(limit.value)}.`
  } catch (error) {
    reportError(error, 'No se pudo actualizar el límite.')
  } finally {
    busy.value = ''
  }
}

async function saveWhenAndVenue() {
  busy.value = 'edit'
  clearNotices()
  try {
    const body = {}
    if (toIsoUtc(editWhen.value) !== new Date(detail.value.fechaHora).toISOString()) body.fechaHora = toIsoUtc(editWhen.value)
    if (editVenue.value && editVenue.value !== detail.value.lugar?.id) body.lugarId = editVenue.value
    if (!Object.keys(body).length) {
      successMessage.value = 'No hay cambios para guardar.'
      return
    }
    await apiRequest(`/eventos/${encodeURIComponent(detail.value.id)}`, { method: 'PUT', body: JSON.stringify(body) })
    await refreshDetail()
    editWhen.value = toLocalInput(detail.value.fechaHora)
    successMessage.value = 'Evento modificado. Los clientes con entradas reciben un aviso por mail.'
  } catch (error) {
    reportError(error, 'No se pudo modificar el evento.')
  } finally {
    busy.value = ''
  }
}

async function addStaff() {
  if (!staffEmail.value.trim()) return
  busy.value = 'staff'
  clearNotices()
  try {
    await apiRequest(`/eventos/${encodeURIComponent(detail.value.id)}/personal-acceso`, {
      method: 'POST', body: JSON.stringify({ correo: staffEmail.value.trim() }),
    })
    staffEmail.value = ''
    await loadStaff()
    successMessage.value = 'Personal de acceso asignado.'
  } catch (error) {
    reportError(error, 'No se pudo asignar al personal de acceso.')
  } finally {
    busy.value = ''
  }
}

async function revokeStaff(member) {
  busy.value = `revoke-${member.usuarioId}`
  clearNotices()
  try {
    await apiRequest(`/eventos/${encodeURIComponent(detail.value.id)}/personal-acceso/${encodeURIComponent(member.usuarioId)}`, { method: 'DELETE' })
    await loadStaff()
    successMessage.value = 'Acceso revocado.'
  } catch (error) {
    reportError(error, 'No se pudo revocar el acceso.')
  } finally {
    busy.value = ''
  }
}

function backToList() {
  view.value = 'list'
  detail.value = null
  loadEvents()
}

onMounted(loadEvents)
</script>

<template>
  <section class="tickets-page org-page">
    <div class="tickets-heading">
      <div>
        <p class="eyebrow">ORGANIZADOR</p>
        <h1>Mis eventos<span>.</span></h1>
        <p>Creá, publicá y gestioná tus eventos.</p>
      </div>
      <div class="org-heading-actions">
        <button v-if="view !== 'list'" class="button button-outline" @click="backToList">← Volver a mis eventos</button>
        <template v-else>
          <button class="button button-outline" :disabled="loading" @click="loadEvents">↻ Actualizar</button>
          <button class="button button-dark" @click="startNew">+ Nuevo evento</button>
        </template>
      </div>
    </div>

    <div v-if="errorMessage" class="notice notice-error" role="alert">
      <span class="notice-icon">!</span><span>{{ errorMessage }}</span>
      <button class="notice-close" aria-label="Cerrar" @click="errorMessage = ''">×</button>
    </div>
    <div v-if="successMessage" class="notice notice-success" role="status">
      <span class="notice-icon">✓</span><span>{{ successMessage }}</span>
      <button class="notice-close" aria-label="Cerrar" @click="successMessage = ''">×</button>
    </div>

    <!-- Lista -->
    <template v-if="view === 'list'">
      <div v-if="!events.length && !loading && !errorMessage" class="empty-state">
        <span class="empty-illustration">✦</span>
        <h3>Todavía no tenés eventos</h3>
        <p>Creá tu primer evento: elegís el lugar, ponés los precios y lo publicás.</p>
        <button class="button button-dark" @click="startNew">+ Nuevo evento</button>
      </div>
      <div v-else class="org-list">
        <article v-for="event in sortedEvents" :key="event.id" class="org-card">
          <div class="org-card-main">
            <span class="org-status" :class="`org-${event.estado}`"><i></i>{{ STATE_LABELS[event.estado] || event.estado }}<template v-if="event.ventaSuspendida"> · venta suspendida</template></span>
            <h3>{{ event.nombre }}</h3>
            <small>{{ event.tipo }} · {{ event.ciudad || '—' }} · {{ formatDate(event.fechaHora) }}</small>
          </div>
          <div class="org-card-side">
            <span v-if="event.precioMinimo != null"><small>Desde</small><strong>{{ formatPrice(event.precioMinimo) }}</strong></span>
            <button class="button button-outline" @click="openEvent(event.id)">Gestionar</button>
          </div>
        </article>
      </div>
    </template>

    <!-- Nuevo evento -->
    <form v-else-if="view === 'new'" class="org-panel" @submit.prevent="createEvent">
      <p class="eyebrow">1 — DATOS DEL EVENTO</p>
      <div class="org-grid">
        <label class="org-field org-wide"><span class="field-caption">NOMBRE</span><input v-model="form.nombre" maxlength="120" required /></label>
        <label class="org-field org-wide"><span class="field-caption">DESCRIPCIÓN</span><textarea v-model="form.descripcion" rows="2" required></textarea></label>
        <label class="org-field"><span class="field-caption">TIPO</span>
          <input v-model="form.tipo" list="org-types" required /><datalist id="org-types"><option v-for="type in EVENT_TYPES" :key="type" :value="type" /></datalist>
        </label>
        <label class="org-field"><span class="field-caption">PARTICIPANTES (separados por coma)</span><input v-model="form.participantes" placeholder="Banda Alfa, Banda Beta" required /></label>
        <label class="org-field"><span class="field-caption">FECHA Y HORA</span><input v-model="form.fechaHora" type="datetime-local" required /></label>
        <label class="org-field"><span class="field-caption">LÍMITE DE ENTRADAS POR CLIENTE</span><input v-model="form.limite" type="number" min="1" required /></label>
        <label class="org-field org-wide"><span class="field-caption">LUGAR</span>
          <select v-model="form.lugarId" required>
            <option value="" disabled>Elegí un lugar</option>
            <option v-for="venue in venues" :key="venue.id" :value="venue.id">{{ venue.nombre }} · {{ venue.ciudad }} · {{ venue.direccion }}</option>
          </select>
          <small class="org-hint">Los lugares y sus sectores los crea un administrador.</small>
        </label>
      </div>
      <div class="org-actions">
        <button class="button button-green" :disabled="!formValid || busy === 'create'">{{ busy === 'create' ? 'Creando...' : 'Crear borrador y seguir' }} <span aria-hidden="true">→</span></button>
        <button type="button" class="button button-outline" @click="backToList">Cancelar</button>
      </div>
    </form>

    <!-- Gestión -->
    <template v-else-if="view === 'detail' && detail">
      <div class="org-panel">
        <header class="org-detail-head">
          <div>
            <span class="org-status" :class="`org-${detail.estado}`"><i></i>{{ STATE_LABELS[detail.estado] || detail.estado }}<template v-if="detail.ventaSuspendida"> · venta suspendida</template></span>
            <h2>{{ detail.nombre }}</h2>
            <small>{{ detail.tipo }} · {{ detail.lugar?.nombre }} ({{ detail.lugar?.ciudad }}) · {{ formatDate(detail.fechaHora) }}</small>
          </div>
        </header>

        <p class="eyebrow">{{ isDraft ? '2 — PRECIOS POR SECTOR' : 'SECTORES' }}</p>
        <div class="org-sectors">
          <div v-for="sector in detail.sectores" :key="sector.sectorId" class="org-sector">
            <div><strong>{{ sector.nombre }}</strong><small>{{ seatSummary(sector) }}</small></div>
            <label v-if="isDraft" class="org-price"><span class="field-caption">PRECIO (ARS)</span><input v-model="prices[sector.sectorId]" type="number" min="0" step="0.01" /></label>
            <div v-else class="org-price-fixed">
              <strong>{{ formatPrice(sector.precio) }}</strong>
              <small v-if="sector.disponibilidad">{{ sector.disponibilidad.cantidadDisponible }} / {{ sector.disponibilidad.capacidadTotal }} disponibles</small>
            </div>
          </div>
        </div>
        <div v-if="isDraft" class="org-actions">
          <button class="button button-green" :disabled="!pricesValid || Boolean(busy)" @click="savePricesAndPublish">{{ busy === 'publish' ? 'Publicando...' : 'Guardar precios y publicar' }}</button>
          <button class="button button-outline" :disabled="!pricesValid || Boolean(busy)" @click="savePricesOnly">{{ busy === 'prices' ? 'Guardando...' : 'Guardar precios' }}</button>
        </div>
        <p v-else class="org-hint">Los precios no se pueden modificar después de publicar.</p>
      </div>

      <div v-if="canEditEvent" class="org-panel">
        <p class="eyebrow">{{ isDraft ? 'FECHA, LUGAR Y LÍMITE' : 'FECHA Y LUGAR' }}</p>
        <div class="org-grid">
          <label class="org-field"><span class="field-caption">FECHA Y HORA</span><input v-model="editWhen" type="datetime-local" /></label>
          <label class="org-field"><span class="field-caption">LUGAR</span>
            <select v-model="editVenue"><option v-for="venue in venues" :key="venue.id" :value="venue.id">{{ venue.nombre }} · {{ venue.ciudad }}</option></select>
          </label>
        </div>
        <div class="org-actions">
          <button class="button button-outline" :disabled="Boolean(busy) || !editWhen" @click="saveWhenAndVenue">{{ busy === 'edit' ? 'Guardando...' : 'Guardar fecha y lugar' }}</button>
        </div>
        <div v-if="isDraft" class="org-grid org-limit">
          <label class="org-field"><span class="field-caption">LÍMITE DE ENTRADAS POR CLIENTE (solo antes de publicar)</span><input v-model="limit" type="number" min="1" placeholder="Escribí un valor para cambiarlo" /></label>
          <button class="button button-outline" :disabled="Boolean(busy) || !(Number(limit) >= 1)" @click="saveLimit">{{ busy === 'limit' ? 'Guardando...' : 'Actualizar límite' }}</button>
        </div>
        <p v-else class="org-hint">El límite de entradas por cliente no se puede modificar después de publicar.</p>
      </div>

      <div v-if="canEditEvent" class="org-panel">
        <p class="eyebrow">PERSONAL DE ACCESO</p>
        <form class="org-staff-form" @submit.prevent="addStaff">
          <label class="org-field"><span class="field-caption">CORREO DE LA PERSONA (con rol de personal de acceso)</span><input v-model="staffEmail" type="email" placeholder="personal@ticketya.test" /></label>
          <button class="button button-dark" :disabled="!staffEmail.trim() || Boolean(busy)">{{ busy === 'staff' ? 'Asignando...' : 'Asignar' }}</button>
        </form>
        <ul v-if="staff.length" class="org-staff">
          <li v-for="member in staff" :key="member.usuarioId">
            <div><strong>{{ member.usuarioId }}</strong><small>{{ member.estado }} · desde {{ formatDate(member.asignadaEn) }}</small></div>
            <button v-if="member.estado !== 'REVOCADA'" class="button button-outline" :disabled="Boolean(busy)" @click="revokeStaff(member)">{{ busy === `revoke-${member.usuarioId}` ? 'Revocando...' : 'Revocar' }}</button>
          </li>
        </ul>
        <p v-else class="org-hint">Todavía no asignaste personal de acceso a este evento.</p>
      </div>

      <div v-if="canQueue" class="org-panel">
        <p class="eyebrow">FILA VIRTUAL</p>
        <p v-if="queue === 'none'" class="org-hint">Este evento todavía no tiene fila. Configurala (cupo y duración del turno) y activala para ordenar la compra cuando haya mucha demanda.</p>
        <p v-else-if="queue" class="org-queue-state">
          <span class="org-status" :class="queue.estado === 'ACTIVA' ? 'org-publicado' : queue.estado === 'PAUSADA' ? 'org-borrador' : 'org-cancelado'"><i></i>{{ QUEUE_STATE_LABELS[queue.estado] || queue.estado }}</span>
          <span>{{ queue.cantidadEsperando }} esperando · {{ queue.cantidadHabilitados }} habilitados</span>
          <small v-if="queue.cancelado">Evento cancelado: la fila no se puede reactivar.</small>
        </p>
        <div class="org-grid">
          <label class="org-field"><span class="field-caption">CUPO (clientes habilitados a la vez)</span><input v-model="queueCupo" type="number" min="1" /></label>
          <label class="org-field"><span class="field-caption">DURACIÓN DEL TURNO (minutos)</span><input v-model="queueMinutes" type="number" min="1" /></label>
        </div>
        <div class="org-actions">
          <button class="button button-outline" :disabled="Boolean(busy) || !(Number(queueCupo) >= 1) || !(Number(queueMinutes) >= 1)" @click="saveQueueConfig">{{ busy === 'queue-config' ? 'Guardando...' : 'Guardar configuración' }}</button>
          <template v-if="queue && queue !== 'none' && !queue.cancelado">
            <button v-if="queue.estado !== 'ACTIVA'" class="button button-green" :disabled="Boolean(busy)" @click="setQueueState('ACTIVA')">{{ busy === 'queue-ACTIVA' ? 'Activando...' : 'Activar fila' }}</button>
            <button v-if="queue.estado === 'ACTIVA'" class="button button-outline" :disabled="Boolean(busy)" @click="setQueueState('PAUSADA')">Pausar</button>
            <button v-if="queue.estado !== 'DESACTIVADA'" class="button button-outline" :disabled="Boolean(busy)" @click="setQueueState('DESACTIVADA')">Desactivar</button>
          </template>
        </div>
        <p class="org-hint">Con la fila activa, los clientes solo pueden reservar cuando su turno está habilitado. Desactivarla invalida los turnos que esperan.</p>
      </div>
    </template>
  </section>
</template>

<style scoped>
.org-heading-actions { display: flex; gap: 10px; }
.org-list { display: flex; flex-direction: column; gap: 12px; margin-top: 24px; }
.org-card { display: flex; align-items: center; justify-content: space-between; gap: 18px; padding: 18px 24px; border: 1px solid var(--line); border-radius: 14px; background: var(--paper); box-shadow: 0 7px 22px rgb(22 39 29 / 4%); }
.org-card-main h3 { margin: 6px 0 4px; font-size: 19px; }
.org-card-main small, .org-detail-head small { color: var(--muted); font-size: 12px; }
.org-card-side { display: flex; align-items: center; gap: 18px; text-align: right; }
.org-card-side small { display: block; color: var(--muted); font-size: 11px; }
.org-status { display: inline-flex; align-items: center; gap: 6px; font-size: 11px; font-weight: 700; letter-spacing: .04em; text-transform: uppercase; color: #42633e; }
.org-status i { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
.org-borrador { color: #9a7a1f; }
.org-cancelado, .org-finalizado { color: #914634; }
.org-agotado { color: #5b6bb0; }
.org-panel { display: flex; flex-direction: column; gap: 14px; margin-top: 22px; padding: 22px 24px; border: 1px solid var(--line); border-radius: 14px; background: var(--paper); box-shadow: 0 7px 22px rgb(22 39 29 / 4%); }
.org-panel .eyebrow { margin: 0; }
.org-detail-head h2 { margin: 6px 0 4px; font-size: 24px; }
.org-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.org-wide { grid-column: 1 / -1; }
.org-field { display: flex; flex-direction: column; gap: 6px; }
.org-field input, .org-field select, .org-field textarea { width: 100%; padding: 10px 12px; border: 1px solid #dfe5dc; border-radius: 8px; background: white; font: inherit; font-size: 13px; }
.org-field textarea { resize: vertical; }
.org-hint { margin: 0; color: var(--muted); font-size: 12px; }
.org-actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 4px; }
.org-sectors { display: flex; flex-direction: column; gap: 10px; }
.org-sector { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 12px 14px; border: 1px solid var(--line); border-radius: 10px; }
.org-sector small { display: block; color: var(--muted); font-size: 12px; }
.org-price { display: flex; flex-direction: column; gap: 4px; width: 170px; }
.org-price input { padding: 9px 12px; border: 1px solid #dfe5dc; border-radius: 8px; font: inherit; font-size: 13px; }
.org-price-fixed { text-align: right; }
.org-limit { align-items: end; grid-template-columns: minmax(0, 1fr) auto; }
.org-staff-form { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: end; gap: 12px; }
.org-staff { display: flex; flex-direction: column; margin: 0; padding: 0; list-style: none; }
.org-staff li { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 0; border-top: 1px solid var(--line); }
.org-staff small { display: block; color: var(--muted); font-size: 12px; }
.org-staff strong { word-break: break-all; font-size: 12px; }
.org-queue-state { display: flex; flex-wrap: wrap; align-items: center; gap: 14px; margin: 0; font-size: 13px; }
.org-queue-state small { color: var(--muted); }
@media (max-width: 640px) {
  .org-grid, .org-limit, .org-staff-form { grid-template-columns: 1fr; }
  .org-card, .org-sector { flex-direction: column; align-items: flex-start; }
  .org-card-side { text-align: left; }
  .org-heading-actions { flex-wrap: wrap; }
}
</style>
