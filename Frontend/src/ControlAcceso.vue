<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { apiRequest } from './api'

// Control de acceso (rol PERSONAL_ACCESO, o ADMIN para ingresos excepcionales). Usa MS5 por el Gateway:
//   GET /eventos (público, para elegir el evento) · POST /eventos/{eventoId}/ingresos { codigoQr }
// MS5 comprueba en cada validación que el personal tenga una asignación activa al evento (la crea el
// organizador desde Mis eventos). Un lector de QR USB funciona como teclado: escribe el código y envía Enter.

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const STORAGE_KEY = 'ticketya.acceso.evento'
const REASON_TITLES = {
  ENTRADA_YA_UTILIZADA: 'Entrada ya utilizada',
  ENTRADA_DE_OTRO_EVENTO: 'Entrada de otro evento',
  ENTRADA_NO_ENCONTRADA: 'Código no reconocido',
  PERSONAL_NO_AUTORIZADO: 'No estás asignado a este evento',
  EVENTO_CERRADO: 'Evento cerrado',
  ENTRADA_NO_VIGENTE: 'Entrada no vigente',
  VALIDACION: 'Código inválido',
}

const events = ref([])
const eventId = ref('')
const code = ref('')
const result = ref(null)
const history = ref([])
const loading = ref(false)
const validating = ref(false)
const loadError = ref('')
const codeInput = ref(null)

const selectedEvent = computed(() => events.value.find((event) => event.id === eventId.value) || null)

function formatDate(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function formatTime(value) {
  return new Intl.DateTimeFormat('es-AR', { timeStyle: 'medium' }).format(new Date(value))
}

function seatLabel(entry) {
  return entry.fila && entry.numero ? `Fila ${entry.fila} · Asiento ${entry.numero}` : 'Sin numerar'
}

async function loadEvents() {
  loading.value = true
  loadError.value = ''
  try {
    const query = new URLSearchParams({ size: '100', orden: 'fecha', direccion: 'asc' })
    const response = await apiRequest(`/eventos?${query}`)
    events.value = response?.contenido || []
    const remembered = window.localStorage.getItem(STORAGE_KEY)
    if (remembered && events.value.some((event) => event.id === remembered)) eventId.value = remembered
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : 'No se pudieron cargar los eventos.'
  } finally {
    loading.value = false
  }
}

function rememberEvent() {
  try { window.localStorage.setItem(STORAGE_KEY, eventId.value) } catch { /* sin almacenamiento */ }
  result.value = null
  focusCode()
}

function focusCode() {
  nextTick(() => codeInput.value?.focus())
}

function record(entry) {
  result.value = entry
  history.value = [entry, ...history.value].slice(0, 10)
}

async function validate() {
  const value = code.value.trim()
  if (!eventId.value || !value || validating.value) return
  if (!UUID.test(value)) {
    record({ ok: false, at: new Date(), title: 'Código inválido', detail: 'El código QR no tiene el formato esperado. Volvé a escanearlo.' })
    code.value = ''
    focusCode()
    return
  }
  validating.value = true
  try {
    const entry = await apiRequest(`/eventos/${encodeURIComponent(eventId.value)}/ingresos`, {
      method: 'POST', body: JSON.stringify({ codigoQr: value }),
    })
    record({
      ok: true, at: new Date(entry.validadoEl), title: 'Ingreso válido',
      detail: `${entry.sector?.nombre || 'Sector'} · ${seatLabel(entry)}`,
      exceptional: entry.excepcional, ticket: entry.idEntrada,
    })
  } catch (error) {
    // MS5 incluye la hora del primer ingreso en ISO UTC: se muestra en hora local.
    const message = error instanceof Error
      ? error.message
        .replace(/ \(HTTP \d+\)$/, '')
        .replace(/\d{4}-\d{2}-\d{2}T[\d:.]+Z/g, (iso) => formatDate(iso))
      : 'No se pudo validar la entrada.'
    record({ ok: false, at: new Date(), title: REASON_TITLES[error.code] || 'No se pudo validar', detail: message })
  } finally {
    validating.value = false
    code.value = ''
    focusCode()
  }
}

onMounted(async () => {
  await loadEvents()
  focusCode()
})
</script>

<template>
  <section class="tickets-page gate-page">
    <div class="tickets-heading">
      <div>
        <p class="eyebrow">PERSONAL DE ACCESO</p>
        <h1>Control de acceso<span>.</span></h1>
        <p>Validá las entradas en la puerta del evento.</p>
      </div>
      <button class="button button-outline" :disabled="loading" @click="loadEvents">↻ Actualizar eventos</button>
    </div>

    <div v-if="loadError" class="notice notice-error" role="alert">
      <span class="notice-icon">!</span><span>{{ loadError }}</span>
    </div>

    <div class="gate-panel">
      <label class="gate-field">
        <span class="field-caption">EVENTO</span>
        <select v-model="eventId" @change="rememberEvent">
          <option value="" disabled>Elegí el evento que estás controlando</option>
          <option v-for="event in events" :key="event.id" :value="event.id">{{ event.nombre }} · {{ formatDate(event.fechaHora) }}</option>
        </select>
        <small v-if="selectedEvent">{{ selectedEvent.ciudad }} · Solo podés validar si el organizador te asignó a este evento.</small>
      </label>

      <form class="gate-scan" @submit.prevent="validate">
        <label class="gate-field">
          <span class="field-caption">CÓDIGO QR</span>
          <input ref="codeInput" v-model="code" :disabled="!eventId || validating" autocomplete="off" spellcheck="false"
            placeholder="Escaneá el QR o pegá el código y presioná Enter" />
        </label>
        <button class="button button-dark" :disabled="!eventId || !code.trim() || validating">{{ validating ? 'Validando...' : 'Validar entrada' }}</button>
      </form>
    </div>

    <div v-if="result" class="gate-result" :class="result.ok ? 'gate-ok' : 'gate-bad'" :role="result.ok ? 'status' : 'alert'">
      <span class="gate-mark" aria-hidden="true">{{ result.ok ? '✓' : '✕' }}</span>
      <div>
        <h2>{{ result.title }}</h2>
        <p>{{ result.detail }}</p>
        <small v-if="result.exceptional">Ingreso excepcional registrado por un administrador.</small>
      </div>
    </div>

    <div v-if="history.length" class="gate-history">
      <p class="eyebrow">ÚLTIMAS VALIDACIONES</p>
      <ul>
        <li v-for="(entry, index) in history" :key="`${entry.at}-${index}`" :class="entry.ok ? 'gate-hist-ok' : 'gate-hist-bad'">
          <span class="gate-hist-dot" aria-hidden="true"></span>
          <strong>{{ entry.title }}</strong>
          <small>{{ entry.detail }}</small>
          <time>{{ formatTime(entry.at) }}</time>
        </li>
      </ul>
    </div>
  </section>
</template>

<style scoped>
.gate-panel { display: flex; flex-direction: column; gap: 16px; margin-top: 24px; padding: 22px 24px; border: 1px solid var(--line); border-radius: 14px; background: var(--paper); box-shadow: 0 7px 22px rgb(22 39 29 / 4%); }
.gate-field { display: flex; flex-direction: column; gap: 6px; flex: 1; }
.gate-field select, .gate-field input { width: 100%; padding: 12px 14px; border: 1px solid #dfe5dc; border-radius: 8px; background: white; font: inherit; font-size: 14px; }
.gate-field input { font-family: ui-monospace, Consolas, monospace; font-size: 15px; }
.gate-field small { color: var(--muted); font-size: 12px; }
.gate-scan { display: flex; align-items: flex-end; gap: 12px; }
.gate-scan .button { min-height: 46px; }
.gate-result { display: flex; align-items: center; gap: 20px; margin-top: 20px; padding: 24px 28px; border-radius: 14px; border: 1px solid; }
.gate-result h2 { margin: 0 0 4px; font-size: 26px; }
.gate-result p { margin: 0; font-size: 15px; line-height: 1.5; }
.gate-result small { display: block; margin-top: 6px; font-size: 12px; opacity: .8; }
.gate-mark { display: grid; place-items: center; flex: 0 0 64px; height: 64px; border-radius: 50%; color: white; font-size: 34px; font-weight: 700; }
.gate-ok { border-color: #bfdc8a; background: #f2fae3; color: #2f5233; }
.gate-ok .gate-mark { background: #4e8a3e; }
.gate-bad { border-color: #f1c5bb; background: #fff3ef; color: #8a3524; }
.gate-bad .gate-mark { background: #c5503a; }
.gate-history { margin-top: 26px; }
.gate-history ul { display: flex; flex-direction: column; margin: 8px 0 0; padding: 0; list-style: none; }
.gate-history li { display: grid; grid-template-columns: 12px auto 1fr auto; align-items: center; gap: 12px; padding: 10px 0; border-top: 1px solid var(--line); font-size: 13px; }
.gate-history small { color: var(--muted); font-size: 12px; }
.gate-history time { color: var(--muted); font-size: 12px; font-variant-numeric: tabular-nums; }
.gate-hist-dot { width: 9px; height: 9px; border-radius: 50%; }
.gate-hist-ok .gate-hist-dot { background: #4e8a3e; }
.gate-hist-bad .gate-hist-dot { background: #c5503a; }
@media (max-width: 640px) {
  .gate-scan { flex-direction: column; align-items: stretch; }
  .gate-result { flex-direction: column; align-items: flex-start; }
  .gate-history li { grid-template-columns: 12px 1fr auto; }
  .gate-history small { grid-column: 2 / -1; }
}
</style>
