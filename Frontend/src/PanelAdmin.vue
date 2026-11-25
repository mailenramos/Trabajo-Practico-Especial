<script setup>
import { computed, onMounted, ref } from 'vue'
import { apiRequest } from './api'

// Panel de administración (rol ADMIN). Usa MS3 por el Gateway:
//   GET /devoluciones?estado= · POST /devoluciones/{id}/aprobacion · POST /devoluciones/{id}/rechazo { motivo }
//   GET /reintegros?estado=   · POST /reintegros/{id}/reintento

const section = ref('devoluciones')
const refundFilter = ref('PENDIENTE')
const reimbursementFilter = ref('')
const refunds = ref([])
const reimbursements = ref([])
const loading = ref(false)
const acting = ref('')
const rejecting = ref('')
const rejectReason = ref('')
const errorMessage = ref('')
const successMessage = ref('')

const REFUND_FILTERS = [
  { value: 'PENDIENTE', label: 'Pendientes' },
  { value: 'APROBADA', label: 'Aprobadas' },
  { value: 'RECHAZADA', label: 'Rechazadas' },
  { value: '', label: 'Todas' },
]
const REIMBURSEMENT_FILTERS = [
  { value: '', label: 'Todos' },
  { value: 'FALLIDO', label: 'Fallidos' },
  { value: 'PENDIENTE', label: 'Pendientes' },
  { value: 'EXITOSO', label: 'Exitosos' },
]
const STEP_LABELS = {
  ENTRADA_EN_REINTEGRO: 'marcar la entrada en reintegro',
  LIBERAR_LUGAR: 'liberar el lugar',
  REINTEGRO: 'hacer el reintegro',
  ENTRADA_DEVUELTA: 'marcar la entrada como devuelta',
}
const ORIGIN_LABELS = {
  DEVOLUCION: 'Devolución de entrada',
  CANCELACION_EVENTO: 'Cancelación de evento',
  VENTA_RECHAZADA: 'Venta rechazada',
}

const reasonValid = computed(() => rejectReason.value.trim().length > 0 && rejectReason.value.trim().length <= 255)

function formatDate(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function formatPrice(value) {
  return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 2 }).format(value || 0)
}

function seatLabel(place) {
  return place?.fila && place?.numero ? `Fila ${place.fila} · Asiento ${place.numero}` : 'Sin numerar'
}

function reportError(error, fallback) {
  errorMessage.value = error instanceof Error ? error.message : fallback
}

async function loadRefunds() {
  const query = refundFilter.value ? `?estado=${refundFilter.value}` : ''
  const result = await apiRequest(`/devoluciones${query}`)
  refunds.value = Array.isArray(result) ? result : []
}

async function loadReimbursements() {
  const query = reimbursementFilter.value ? `?estado=${reimbursementFilter.value}` : ''
  const result = await apiRequest(`/reintegros${query}`)
  reimbursements.value = Array.isArray(result) ? result : []
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    await (section.value === 'devoluciones' ? loadRefunds() : loadReimbursements())
  } catch (error) {
    reportError(error, 'No se pudo cargar la información.')
  } finally {
    loading.value = false
  }
}

function switchSection(next) {
  section.value = next
  rejecting.value = ''
  successMessage.value = ''
  load()
}

async function approve(refund) {
  acting.value = refund.id
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const result = await apiRequest(`/devoluciones/${encodeURIComponent(refund.id)}/aprobacion`, { method: 'POST' })
    successMessage.value = result?.coordinacion?.completa === false
      ? 'Devolución aprobada, pero un paso quedó pendiente. Podés reintentarlo desde la sección Reintegros.'
      : 'Devolución aprobada y reintegrada.'
    await loadRefunds()
  } catch (error) {
    reportError(error, 'No se pudo aprobar la devolución.')
  } finally {
    acting.value = ''
  }
}

function startReject(refund) {
  rejecting.value = refund.id
  rejectReason.value = ''
}

async function reject(refund) {
  if (!reasonValid.value) return
  acting.value = refund.id
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await apiRequest(`/devoluciones/${encodeURIComponent(refund.id)}/rechazo`, {
      method: 'POST',
      body: JSON.stringify({ motivo: rejectReason.value.trim() }),
    })
    rejecting.value = ''
    successMessage.value = 'Devolución rechazada. El cliente ve el motivo en Mis compras.'
    await loadRefunds()
  } catch (error) {
    reportError(error, 'No se pudo rechazar la devolución.')
  } finally {
    acting.value = ''
  }
}

async function retry(reimbursementId) {
  acting.value = reimbursementId
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const result = await apiRequest(`/reintegros/${encodeURIComponent(reimbursementId)}/reintento`, { method: 'POST' })
    successMessage.value = result?.estado === 'EXITOSO'
      ? 'Reintegro realizado.'
      : 'Se reintentó, pero el reintegro sigue sin completarse. Revisá el último error.'
    await load()
  } catch (error) {
    reportError(error, 'No se pudo reintentar el reintegro.')
  } finally {
    acting.value = ''
  }
}

onMounted(load)
</script>

<template>
  <section class="tickets-page admin-page">
    <div class="tickets-heading">
      <div>
        <p class="eyebrow">ADMINISTRACIÓN</p>
        <h1>Panel admin<span>.</span></h1>
        <p>Resolvé devoluciones y reintegros de los clientes.</p>
      </div>
      <button class="button button-outline" :disabled="loading" @click="load">↻ Actualizar</button>
    </div>

    <div class="admin-tabs" role="tablist">
      <button role="tab" :aria-selected="section === 'devoluciones'" :class="{ active: section === 'devoluciones' }" @click="switchSection('devoluciones')">Devoluciones</button>
      <button role="tab" :aria-selected="section === 'reintegros'" :class="{ active: section === 'reintegros' }" @click="switchSection('reintegros')">Reintegros</button>
    </div>

    <div v-if="errorMessage" class="notice notice-error" role="alert">
      <span class="notice-icon">!</span><span>{{ errorMessage }}</span>
      <button class="notice-close" aria-label="Cerrar" @click="errorMessage = ''">×</button>
    </div>
    <div v-if="successMessage" class="notice notice-success" role="status">
      <span class="notice-icon">✓</span><span>{{ successMessage }}</span>
      <button class="notice-close" aria-label="Cerrar" @click="successMessage = ''">×</button>
    </div>

    <!-- Devoluciones -->
    <template v-if="section === 'devoluciones'">
      <div class="admin-filters">
        <button v-for="filter in REFUND_FILTERS" :key="filter.label" :class="{ active: refundFilter === filter.value }"
          @click="refundFilter = filter.value; load()">{{ filter.label }}</button>
      </div>

      <div v-if="!refunds.length && !loading && !errorMessage" class="empty-state">
        <span class="empty-illustration">✓</span>
        <h3>No hay devoluciones en este estado</h3>
        <p>Cuando un cliente pida una devolución, aparece acá para revisarla.</p>
      </div>

      <div v-else class="admin-list">
        <article v-for="refund in refunds" :key="refund.id" class="admin-card">
          <header class="admin-card-head">
            <div>
              <span class="admin-status" :class="`admin-${refund.estado?.toLowerCase()}`"><i></i>{{ refund.estado }}</span>
              <h3>{{ refund.evento?.nombre || 'Evento' }}</h3>
              <small>{{ refund.lugar?.sectorNombre }} · {{ seatLabel(refund.lugar) }} · Evento: {{ formatDate(refund.evento?.fechaInicio) }}</small>
              <small>Pedida: {{ formatDate(refund.creadaEn) }}<template v-if="refund.resueltaEn"> · Resuelta: {{ formatDate(refund.resueltaEn) }}</template></small>
            </div>
            <div class="admin-amount"><small>Monto</small><strong>{{ formatPrice(refund.monto) }}</strong></div>
          </header>

          <p v-if="refund.estado === 'RECHAZADA'" class="admin-note admin-note-bad">Motivo: {{ refund.motivoRechazo }}</p>
          <template v-if="refund.estado === 'APROBADA'">
            <p v-if="refund.coordinacion?.completa" class="admin-note admin-note-ok">
              Completa · reintegro {{ refund.reintegro?.estado }} ({{ refund.reintegro?.intentos }} intento{{ refund.reintegro?.intentos === 1 ? '' : 's' }})
            </p>
            <div v-else class="admin-note admin-note-warn">
              <span>Falta: {{ STEP_LABELS[refund.coordinacion?.pasoPendiente] || refund.coordinacion?.pasoPendiente }}.
                <template v-if="refund.coordinacion?.ultimoError">Último error: {{ refund.coordinacion.ultimoError }}</template></span>
              <button v-if="refund.reintegro?.id" class="button button-outline" :disabled="Boolean(acting)" @click="retry(refund.reintegro.id)">
                {{ acting === refund.reintegro.id ? 'Reintentando...' : 'Reintentar' }}
              </button>
            </div>
          </template>

          <div v-if="refund.estado === 'PENDIENTE'" class="admin-actions">
            <template v-if="rejecting === refund.id">
              <label class="admin-reason">
                <span class="field-caption">MOTIVO DEL RECHAZO (obligatorio, máximo 255 caracteres)</span>
                <textarea v-model="rejectReason" rows="2" maxlength="255" placeholder="Explicale al cliente por qué se rechaza"></textarea>
              </label>
              <button class="button button-dark" :disabled="!reasonValid || acting === refund.id" @click="reject(refund)">
                {{ acting === refund.id ? 'Rechazando...' : 'Confirmar rechazo' }}
              </button>
              <button class="button button-outline" :disabled="acting === refund.id" @click="rejecting = ''">Cancelar</button>
            </template>
            <template v-else>
              <button class="button button-green" :disabled="Boolean(acting)" @click="approve(refund)">
                {{ acting === refund.id ? 'Aprobando...' : `Aprobar y reintegrar ${formatPrice(refund.monto)}` }}
              </button>
              <button class="button button-outline" :disabled="Boolean(acting)" @click="startReject(refund)">Rechazar</button>
            </template>
          </div>
        </article>
      </div>
    </template>

    <!-- Reintegros -->
    <template v-else>
      <div class="admin-filters">
        <button v-for="filter in REIMBURSEMENT_FILTERS" :key="filter.label" :class="{ active: reimbursementFilter === filter.value }"
          @click="reimbursementFilter = filter.value; load()">{{ filter.label }}</button>
      </div>

      <div v-if="!reimbursements.length && !loading && !errorMessage" class="empty-state">
        <span class="empty-illustration">↺</span>
        <h3>No hay reintegros en este estado</h3>
        <p>Los reintegros aparecen cuando se aprueba una devolución o se cancela un evento.</p>
      </div>

      <div v-else class="admin-list">
        <article v-for="item in reimbursements" :key="item.id" class="admin-card">
          <header class="admin-card-head">
            <div>
              <span class="admin-status" :class="`admin-${item.estado?.toLowerCase()}`"><i></i>{{ item.estado }}</span>
              <h3>{{ ORIGIN_LABELS[item.origen] || item.origen }}</h3>
              <small>Compra {{ item.compraId }}</small>
              <small>{{ item.intentos }} intento{{ item.intentos === 1 ? '' : 's' }} · Actualizado: {{ formatDate(item.actualizadoEn) }}</small>
            </div>
            <div class="admin-amount"><small>Monto</small><strong>{{ formatPrice(item.monto) }}</strong></div>
          </header>
          <p v-if="item.ultimoError" class="admin-note admin-note-bad">Último error: {{ item.ultimoError }}</p>
          <div v-if="item.estado !== 'EXITOSO'" class="admin-actions">
            <button class="button button-green" :disabled="Boolean(acting)" @click="retry(item.id)">
              {{ acting === item.id ? 'Reintentando...' : 'Reintentar reintegro' }}
            </button>
          </div>
        </article>
      </div>
    </template>
  </section>
</template>

<style scoped>
.admin-tabs { display: flex; gap: 28px; margin-top: 22px; border-bottom: 1px solid var(--line); }
.admin-tabs button { position: relative; padding: 0 0 12px; border: 0; background: none; color: #79827b; font-size: 13px; font-weight: 600; }
.admin-tabs button.active { color: var(--ink); }
.admin-tabs button.active::after { position: absolute; content: ''; height: 2px; bottom: -1px; left: 0; right: 0; background: var(--green-dark); }
.admin-filters { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 18px; }
.admin-filters button { padding: 7px 14px; border: 1px solid var(--line); border-radius: 20px; background: var(--paper); color: #4b5a52; font-size: 12px; font-weight: 600; }
.admin-filters button.active { border-color: var(--green-dark); background: var(--green-dark); color: white; }
.admin-list { display: flex; flex-direction: column; gap: 14px; margin-top: 20px; }
.admin-card { padding: 20px 24px; border: 1px solid var(--line); border-radius: 14px; background: var(--paper); box-shadow: 0 7px 22px rgb(22 39 29 / 4%); }
.admin-card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; }
.admin-card-head h3 { margin: 6px 0 4px; font-size: 19px; }
.admin-card-head small { display: block; color: var(--muted); font-size: 12px; line-height: 1.6; word-break: break-all; }
.admin-amount { text-align: right; }
.admin-amount strong { font-size: 20px; }
.admin-status { display: inline-flex; align-items: center; gap: 6px; font-size: 11px; font-weight: 700; letter-spacing: .04em; text-transform: uppercase; color: #9a7a1f; }
.admin-status i { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
.admin-aprobada, .admin-exitoso { color: #42633e; }
.admin-rechazada, .admin-fallido { color: #914634; }
.admin-note { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin: 14px 0 0; padding: 10px 13px; border-radius: 8px; font-size: 12px; line-height: 1.6; }
.admin-note-ok { background: #f5faed; color: #42633e; }
.admin-note-bad { background: #fff6f3; color: #914634; }
.admin-note-warn { background: #fffaee; color: #8a6a14; }
.admin-note .button { min-height: 34px; flex: none; }
.admin-actions { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 10px; margin-top: 16px; padding-top: 16px; border-top: 1px solid var(--line); }
.admin-reason { display: flex; flex: 1 1 100%; flex-direction: column; gap: 6px; }
.admin-reason textarea { width: 100%; padding: 10px 12px; border: 1px solid #dfe5dc; border-radius: 8px; font: inherit; font-size: 13px; resize: vertical; }
@media (max-width: 640px) {
  .admin-card-head { flex-direction: column; }
  .admin-amount { text-align: left; }
}
</style>
