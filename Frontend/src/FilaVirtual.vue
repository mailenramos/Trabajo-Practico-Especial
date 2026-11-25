<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { apiRequest } from './api'

// Fila virtual del cliente dentro del detalle de un evento (MS7 por el Gateway):
//   GET /filas/eventos/{id}/mi-turno · POST /filas/eventos/{id}/ingresos
// Si el evento tiene fila activa, MS2 solo deja reservar con un turno HABILITADO (lo consume al reservar).
// El detalle público del evento no dice si hay fila: se detecta cuando la reserva falla (prop `required`)
// o cuando el cliente ya tiene un turno.

const props = defineProps({
  eventId: { type: String, required: true },
  required: { type: Boolean, default: false },
})
const emit = defineEmits(['enabled'])

const POLL_MS = 5000
const turn = ref(null)
const busy = ref(false)
const errorMessage = ref('')
const now = ref(Date.now())
let pollTimer = 0
let clockTimer = 0

const active = computed(() => turn.value && ['ESPERANDO', 'HABILITADO'].includes(turn.value.estado))
const visible = computed(() => active.value || props.required || (turn.value && turn.value.estado !== 'CONSUMIDO'))
const remainingMs = computed(() => (turn.value?.expiraEn ? Math.max(0, Date.parse(turn.value.expiraEn) - now.value) : 0))
const countdown = computed(() => {
  const total = Math.floor(remainingMs.value / 1000)
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`
})
const canJoin = computed(() => !active.value)

function formatTime(value) {
  return new Intl.DateTimeFormat('es-AR', { timeStyle: 'medium' }).format(new Date(value))
}

async function loadTurn() {
  const previous = turn.value?.estado
  try {
    turn.value = await apiRequest(`/filas/eventos/${encodeURIComponent(props.eventId)}/mi-turno`)
    if (turn.value.estado === 'HABILITADO' && previous !== 'HABILITADO') emit('enabled')
  } catch (error) {
    if (error.status === 404) turn.value = null
    else errorMessage.value = error instanceof Error ? error.message : 'No se pudo consultar tu turno.'
  }
}

async function join() {
  busy.value = true
  errorMessage.value = ''
  try {
    turn.value = await apiRequest(`/filas/eventos/${encodeURIComponent(props.eventId)}/ingresos`, { method: 'POST' })
    if (turn.value.estado === 'HABILITADO') emit('enabled')
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'No se pudo entrar a la fila.'
  } finally {
    busy.value = false
  }
}

function startTimers() {
  stopTimers()
  clockTimer = window.setInterval(() => { now.value = Date.now() }, 1000)
  pollTimer = window.setInterval(() => { if (active.value) loadTurn() }, POLL_MS)
}

function stopTimers() {
  window.clearInterval(pollTimer)
  window.clearInterval(clockTimer)
}

watch(() => props.eventId, () => {
  turn.value = null
  errorMessage.value = ''
  loadTurn()
})

onMounted(() => {
  loadTurn()
  startTimers()
})
onBeforeUnmount(stopTimers)
</script>

<template>
  <section v-if="visible" class="queue-card" :class="`queue-${(turn?.estado || 'sin').toLowerCase()}`" aria-live="polite">
    <div class="queue-head">
      <span class="queue-badge">FILA VIRTUAL</span>
      <strong v-if="turn?.estado === 'ESPERANDO'">Estás en la fila</strong>
      <strong v-else-if="turn?.estado === 'HABILITADO'">¡Es tu turno!</strong>
      <strong v-else-if="turn?.estado === 'VENCIDO'">Tu turno venció</strong>
      <strong v-else>Este evento tiene fila virtual</strong>
    </div>

    <template v-if="turn?.estado === 'ESPERANDO'">
      <p class="queue-big">Posición {{ turn.posicion }}</p>
      <p>Entraste a las {{ formatTime(turn.ingresadoEn) }}. Esta pantalla se actualiza sola: cuando te toque, vas a poder reservar.</p>
    </template>
    <template v-else-if="turn?.estado === 'HABILITADO'">
      <p class="queue-big">{{ countdown }}</p>
      <p>Tenés hasta las {{ formatTime(turn.expiraEn) }} para elegir tus lugares y reservar. Pasado ese tiempo, el turno vence.</p>
    </template>
    <p v-else-if="turn?.estado === 'VENCIDO'">No reservaste a tiempo. Podés volver a entrar a la fila.</p>
    <p v-else>Para reservar tenés que esperar tu turno. Entrá a la fila y volvé a esta pantalla cuando esté habilitado.</p>

    <p v-if="errorMessage" class="queue-error" role="alert">{{ errorMessage }}</p>
    <button v-if="canJoin" class="button button-dark" :disabled="busy" @click="join">
      {{ busy ? 'Entrando...' : turn?.estado === 'VENCIDO' ? 'Volver a entrar a la fila' : 'Entrar a la fila' }}
    </button>
  </section>
</template>

<style scoped>
.queue-card { display: flex; flex-direction: column; align-items: flex-start; gap: 10px; margin-top: 18px; padding: 20px 24px; border: 1px solid #e3d7a8; border-radius: 14px; background: #fffaea; color: #5b4a12; }
.queue-card p { margin: 0; font-size: 13px; line-height: 1.6; }
.queue-head { display: flex; align-items: center; gap: 12px; }
.queue-head strong { font-size: 17px; }
.queue-badge { padding: 4px 10px; border-radius: 12px; background: #f1e3a8; font-size: 10px; font-weight: 800; letter-spacing: .08em; }
.queue-big { font-size: 34px !important; font-weight: 800; line-height: 1.1 !important; font-variant-numeric: tabular-nums; }
.queue-habilitado { border-color: #bfdc8a; background: #f2fae3; color: #2f5233; }
.queue-habilitado .queue-badge { background: #d3ebaa; }
.queue-vencido { border-color: #f1c5bb; background: #fff3ef; color: #8a3524; }
.queue-vencido .queue-badge { background: #f6d6cd; }
.queue-error { color: #914634; font-weight: 600; }
</style>
