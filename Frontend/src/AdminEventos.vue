<script setup>
import { onMounted, ref } from 'vue'
import { apiRequest } from './api'

// Gestión de eventos del administrador (MS1 por el Gateway):
//   GET /eventos · PUT /eventos/{id}/venta/suspender · PUT /eventos/{id}/venta/reactivar
//   PUT /eventos/{id}/cancelar { motivo? }
// La búsqueda solo trae publicados y agotados: un evento cancelado deja de aparecer.

const events = ref([])
const loading = ref(false)
const acting = ref('')
const cancelling = ref('')
const reason = ref('')
const errorMessage = ref('')
const successMessage = ref('')

function formatDate(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await apiRequest('/eventos?size=100&orden=fecha&direccion=asc')
    events.value = result?.contenido || []
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    loading.value = false
  }
}

async function run(event, action, options, success) {
  acting.value = `${event.id}-${action}`
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await apiRequest(`/eventos/${encodeURIComponent(event.id)}/${action}`, { method: 'PUT', ...options })
    cancelling.value = ''
    await load()
    successMessage.value = success
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    acting.value = ''
  }
}

const suspend = (event) => run(event, 'venta/suspender', {}, `Se suspendió la venta de ${event.nombre}.`)
const resume = (event) => run(event, 'venta/reactivar', {}, `Se reactivó la venta de ${event.nombre}.`)

function cancelEvent(event) {
  const motivo = reason.value.trim()
  run(event, 'cancelar', motivo ? { body: JSON.stringify({ motivo }) } : {},
    `${event.nombre} quedó cancelado. Los reintegros de sus entradas se procesan automáticamente (ver Reintegros).`)
}

function startCancel(event) {
  cancelling.value = event.id
  reason.value = ''
}

onMounted(load)
</script>

<template>
  <div>
    <div v-if="errorMessage" class="notice notice-error" role="alert"><span class="notice-icon">!</span><span>{{ errorMessage }}</span></div>
    <div v-if="successMessage" class="notice notice-success" role="status"><span class="notice-icon">✓</span><span>{{ successMessage }}</span></div>
    <p v-if="!events.length && !loading" class="px-card px-muted">No hay eventos publicados.</p>

    <article v-for="event in events" :key="event.id" class="px-card">
      <div class="px-row">
        <div>
          <span class="px-badge" :class="event.ventaSuspendida ? 'px-warn' : ''">{{ event.estado }}<template v-if="event.ventaSuspendida"> · venta suspendida</template></span>
          <h3>{{ event.nombre }}</h3>
          <small>{{ event.tipo }} · {{ event.ciudad }} · {{ formatDate(event.fechaHora) }}</small>
        </div>
        <div class="px-actions">
          <button v-if="event.ventaSuspendida" class="button button-green" :disabled="Boolean(acting)" @click="resume(event)">
            {{ acting === `${event.id}-venta/reactivar` ? 'Reactivando...' : 'Reactivar venta' }}
          </button>
          <button v-else class="button button-outline" :disabled="Boolean(acting)" @click="suspend(event)">
            {{ acting === `${event.id}-venta/suspender` ? 'Suspendiendo...' : 'Suspender venta' }}
          </button>
          <button v-if="cancelling !== event.id" class="button button-outline px-bad" :disabled="Boolean(acting)" @click="startCancel(event)">Cancelar evento</button>
        </div>
      </div>

      <div v-if="cancelling === event.id" class="px-danger">
        <p><strong>Cancelar es definitivo.</strong> Se cierra la venta y se reintegran todas las entradas vendidas de {{ event.nombre }}. No se puede deshacer.</p>
        <label class="px-field"><span class="field-caption">MOTIVO (opcional)</span><input v-model="reason" maxlength="255" placeholder="Ej.: se suspendió por lluvia" /></label>
        <div class="px-actions">
          <button class="button button-dark" :disabled="Boolean(acting)" @click="cancelEvent(event)">{{ acting === `${event.id}-cancelar` ? 'Cancelando...' : 'Sí, cancelar el evento' }}</button>
          <button class="button button-outline" :disabled="Boolean(acting)" @click="cancelling = ''">Volver</button>
        </div>
      </div>
    </article>
  </div>
</template>
