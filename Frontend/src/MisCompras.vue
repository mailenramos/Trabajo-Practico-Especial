<script setup>
import { computed, onMounted, ref } from 'vue'
import { apiRequest } from './api'

// Mis compras y devoluciones del cliente (MS3 por el Gateway):
//   GET /compras · GET /devoluciones · POST /devoluciones { entradaId }
// La devolución es por entrada, hasta 72 horas antes del evento; MS3 valida todas las reglas y
// la pantalla solo adelanta el plazo para no ofrecer un botón que va a fallar.

const PLAZO_DEVOLUCION_MS = 72 * 60 * 60 * 1000

const purchases = ref([])
const refunds = ref([])
const loading = ref(false)
const requesting = ref('')
const confirming = ref('')
const errorMessage = ref('')
const successMessage = ref('')

const PURCHASE_LABELS = { CONFIRMADA: 'Confirmada', PENDIENTE: 'Pago pendiente', FALLIDA: 'Fallida' }

// Última solicitud de cada entrada (la más reciente gana: una rechazada se puede volver a pedir).
const refundByTicket = computed(() => {
  const byTicket = new Map()
  const sorted = [...refunds.value].sort((a, b) => Date.parse(a.creadaEn) - Date.parse(b.creadaEn))
  for (const refund of sorted) byTicket.set(refund.entradaId, refund)
  return byTicket
})

const sortedPurchases = computed(() =>
  [...purchases.value].sort((a, b) => Date.parse(b.fecha) - Date.parse(a.fecha)),
)

function formatDate(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function formatPrice(value) {
  return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 2 }).format(value || 0)
}

function seatLabel(place) {
  return place.fila && place.numero ? `Fila ${place.fila} · Asiento ${place.numero}` : 'Sin numerar'
}

function insideRefundWindow(purchase) {
  return Date.parse(purchase.evento?.fechaInicio) - Date.now() > PLAZO_DEVOLUCION_MS
}

/** Estado de devolución de un lugar comprado, listo para mostrar. */
function refundState(purchase, place) {
  const refund = refundByTicket.value.get(place.entradaId)
  if (refund?.estado === 'PENDIENTE') {
    return { tone: 'pending', text: 'Devolución pendiente de revisión' }
  }
  if (refund?.estado === 'APROBADA') {
    if (refund.coordinacion?.completa) {
      return { tone: 'done', text: `Devuelta · reintegro ${formatPrice(refund.monto)} (${refund.reintegro?.estado || 'EXITOSO'})` }
    }
    return { tone: 'pending', text: 'Devolución aprobada, procesando el reintegro' }
  }
  if (refund?.estado === 'RECHAZADA') {
    return { tone: 'rejected', text: `Devolución rechazada: ${refund.motivoRechazo || 'sin motivo'}`, retry: true }
  }
  if (purchase.estado !== 'CONFIRMADA' || !place.entradaId) return { tone: 'none', text: '' }
  if (!insideRefundWindow(purchase)) {
    return { tone: 'none', text: 'Fuera de plazo: se puede devolver hasta 72 h antes del evento' }
  }
  return { tone: 'available', text: '', retry: true }
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const [purchaseList, refundList] = await Promise.all([apiRequest('/compras'), apiRequest('/devoluciones')])
    purchases.value = Array.isArray(purchaseList) ? purchaseList : []
    refunds.value = Array.isArray(refundList) ? refundList : []
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'No se pudieron cargar tus compras.'
  } finally {
    loading.value = false
  }
}

async function requestRefund(place) {
  requesting.value = place.entradaId
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await apiRequest('/devoluciones', { method: 'POST', body: JSON.stringify({ entradaId: place.entradaId }) })
    confirming.value = ''
    await load()
    successMessage.value = 'Pedimos la devolución. Un administrador la va a revisar.'
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'No se pudo pedir la devolución.'
  } finally {
    requesting.value = ''
  }
}

onMounted(load)
</script>

<template>
  <section class="tickets-page purchases-page">
    <div class="tickets-heading">
      <div>
        <p class="eyebrow">TU HISTORIAL</p>
        <h1>Mis compras<span>.</span></h1>
        <p>Revisá tus compras y pedí la devolución de una entrada.</p>
      </div>
      <button class="button button-outline" :disabled="loading" @click="load">↻ Actualizar</button>
    </div>

    <div v-if="errorMessage" class="notice notice-error" role="alert">
      <span class="notice-icon">!</span><span>{{ errorMessage }}</span>
      <button class="notice-close" aria-label="Cerrar" @click="errorMessage = ''">×</button>
    </div>
    <div v-if="successMessage" class="notice notice-success" role="status">
      <span class="notice-icon">✓</span><span>{{ successMessage }}</span>
      <button class="notice-close" aria-label="Cerrar" @click="successMessage = ''">×</button>
    </div>

    <div v-if="!purchases.length && !loading && !errorMessage" class="empty-state">
      <span class="empty-illustration">✳</span>
      <h3>Todavía no hiciste compras</h3>
      <p>Cuando compres entradas, tu historial aparece acá.</p>
    </div>

    <div v-else class="purchase-list">
      <article v-for="purchase in sortedPurchases" :key="purchase.id" class="purchase-card">
        <header class="purchase-head">
          <div>
            <span class="purchase-status" :class="`purchase-${purchase.estado?.toLowerCase()}`">
              <i></i>{{ PURCHASE_LABELS[purchase.estado] || purchase.estado }}
            </span>
            <h3>{{ purchase.evento?.nombre || 'Evento' }}</h3>
            <small>Evento: {{ formatDate(purchase.evento?.fechaInicio) }} · Compra: {{ formatDate(purchase.fecha) }}</small>
          </div>
          <div class="purchase-total">
            <small>Total</small>
            <strong>{{ formatPrice(purchase.total) }}</strong>
          </div>
        </header>

        <ul class="purchase-places">
          <li v-for="place in purchase.lugares" :key="`${place.sectorId}-${place.fila}-${place.numero}-${place.entradaId}`">
            <div class="place-info">
              <strong>{{ place.sectorNombre }}</strong>
              <small>{{ seatLabel(place) }} · {{ formatPrice(place.precioUnitario) }}</small>
            </div>
            <div class="place-refund">
              <span v-if="refundState(purchase, place).text" class="refund-note" :class="`refund-${refundState(purchase, place).tone}`">
                {{ refundState(purchase, place).text }}
              </span>
              <template v-if="refundState(purchase, place).retry && purchase.estado === 'CONFIRMADA' && insideRefundWindow(purchase)">
                <template v-if="confirming === place.entradaId">
                  <button class="button button-dark" :disabled="requesting === place.entradaId" @click="requestRefund(place)">
                    {{ requesting === place.entradaId ? 'Enviando...' : `Confirmar devolución de ${formatPrice(place.precioUnitario)}` }}
                  </button>
                  <button class="button button-outline" :disabled="requesting === place.entradaId" @click="confirming = ''">Cancelar</button>
                </template>
                <button v-else class="button button-outline" :disabled="Boolean(requesting)" @click="confirming = place.entradaId">
                  {{ refundByTicket.has(place.entradaId) ? 'Pedir de nuevo' : 'Pedir devolución' }}
                </button>
              </template>
            </div>
          </li>
        </ul>

        <footer v-if="purchase.reintegros?.length" class="purchase-refunds">
          <small v-for="refund in purchase.reintegros" :key="refund.id">
            Reintegro {{ formatPrice(refund.monto) }} · {{ refund.estado }}
          </small>
        </footer>
      </article>
    </div>
  </section>
</template>

<style scoped>
.purchase-list { display: flex; flex-direction: column; gap: 14px; margin-top: 24px; }
.purchase-card { padding: 20px 24px; border: 1px solid var(--line); border-radius: 14px; background: var(--paper); box-shadow: 0 7px 22px rgb(22 39 29 / 4%); }
.purchase-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; }
.purchase-head h3 { margin: 6px 0 4px; font-size: 20px; }
.purchase-head small { color: var(--muted); font-size: 12px; }
.purchase-total { text-align: right; }
.purchase-total small { display: block; }
.purchase-total strong { font-size: 20px; }
.purchase-status { display: inline-flex; align-items: center; gap: 6px; font-size: 11px; font-weight: 700; letter-spacing: .04em; text-transform: uppercase; color: #5d7a36; }
.purchase-status i { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
.purchase-pendiente { color: #9a7a1f; }
.purchase-fallida { color: #914634; }
.purchase-places { margin: 16px 0 0; padding: 0; list-style: none; border-top: 1px solid var(--line); }
.purchase-places li { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 12px 0; border-bottom: 1px solid var(--line); }
.purchase-places li:last-child { border-bottom: 0; }
.place-info { display: flex; flex-direction: column; gap: 2px; }
.place-info small { color: var(--muted); font-size: 12px; }
.place-refund { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 10px; }
.place-refund .button { min-height: 36px; }
.refund-note { font-size: 12px; font-weight: 600; }
.refund-pending { color: #9a7a1f; }
.refund-done { color: #42633e; }
.refund-rejected { color: #914634; }
.refund-none { color: var(--muted); font-weight: 400; }
.purchase-refunds { display: flex; flex-direction: column; gap: 2px; margin-top: 10px; color: var(--muted); }
@media (max-width: 640px) {
  .purchase-head, .purchase-places li { flex-direction: column; align-items: flex-start; }
  .purchase-total { text-align: left; }
  .place-refund { justify-content: flex-start; }
}
</style>
