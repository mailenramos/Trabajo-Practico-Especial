<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { apiRequest, gatewayUrl } from './api'

const userId = ref(localStorage.getItem('ticketya.userId') || 'cliente-demo')
const activeTab = ref('eventos')
const search = ref({ q: '', tipo: '', ciudad: '' })
const events = ref([])
const eventPage = ref(0)
const totalPages = ref(0)
const selectedEvent = ref(null)
const selectedSectorId = ref('')
const selectedSeats = ref([])
const generalQuantity = ref(1)
const reservation = ref(null)
const reservationRequestId = ref('')
const purchase = ref(null)
const paymentKey = ref('')
const paymentMethod = ref('CREDIT_CARD')
const tickets = ref([])
const ticketPage = ref(0)
const ticketTotalPages = ref(0)
const busy = ref('')
const errorMessage = ref('')
const successMessage = ref('')

const selectedSector = computed(() =>
  selectedEvent.value?.sectores?.find((sector) => sector.sectorId === selectedSectorId.value) || null,
)
const availableSeats = computed(() => selectedSector.value?.disponibilidad?.asientosDisponibles || [])
const isNumbered = computed(() => selectedSector.value?.tipo === 'NUMERADO')
const selectionCount = computed(() => isNumbered.value ? selectedSeats.value.length : Number(generalQuantity.value || 0))
const canReserve = computed(() => Boolean(
  selectedEvent.value
  && selectedSector.value?.disponibilidad
  && !selectedEvent.value.ventaSuspendida
  && !reservation.value
  && (isNumbered.value ? selectedSeats.value.length > 0 : generalQuantity.value > 0),
))

function clearNotices() {
  errorMessage.value = ''
  successMessage.value = ''
}

function reportError(error) {
  errorMessage.value = error instanceof Error ? error.message : 'Ocurrio un error inesperado.'
}

function createRequestId() {
  return crypto.randomUUID()
}

async function loadEvents(page = 0) {
  clearNotices()
  busy.value = 'events'
  try {
    const query = new URLSearchParams({ page: String(page), size: '12', orden: 'fecha', direccion: 'asc' })
    for (const [key, value] of Object.entries(search.value)) {
      if (value.trim()) query.set(key, value.trim())
    }
    const result = await apiRequest(`/eventos?${query}`, userId.value)
    events.value = result?.contenido || []
    eventPage.value = result?.pagina ?? page
    totalPages.value = result?.totalPaginas ?? 0
  } catch (error) {
    reportError(error)
  } finally {
    busy.value = ''
  }
}

async function openEvent(event) {
  clearNotices()
  busy.value = 'detail'
  try {
    selectedEvent.value = await apiRequest(`/eventos/${encodeURIComponent(event.id)}`, userId.value)
    selectedSectorId.value = ''
    selectedSeats.value = []
    generalQuantity.value = 1
    reservation.value = null
    reservationRequestId.value = ''
    purchase.value = null
    paymentKey.value = ''
    activeTab.value = 'eventos'
  } catch (error) {
    reportError(error)
  } finally {
    busy.value = ''
  }
}

function selectSector(sector) {
  selectedSectorId.value = sector.sectorId
  selectedSeats.value = []
  generalQuantity.value = 1
  reservation.value = null
  reservationRequestId.value = ''
  purchase.value = null
  paymentKey.value = ''
}

function toggleSeat(seat) {
  const key = `${seat.fila}-${seat.numero}`
  selectedSeats.value = selectedSeats.value.includes(key)
    ? selectedSeats.value.filter((selected) => selected !== key)
    : [...selectedSeats.value, key]
  reservationRequestId.value = ''
}

function isSeatSelected(seat) {
  return selectedSeats.value.includes(`${seat.fila}-${seat.numero}`)
}

async function reservePlaces() {
  if (!canReserve.value) return
  clearNotices()
  busy.value = 'reserve'
  try {
    const detalles = isNumbered.value
      ? selectedSeats.value.map((seat) => {
        const [fila, numero] = seat.split('-').map(Number)
        return { sectorId: selectedSector.value.sectorId, fila, numero }
      })
      : [{ sectorId: selectedSector.value.sectorId, cantidad: Number(generalQuantity.value) }]
    const seatsToReserve = [...selectedSeats.value]
    const quantityToReserve = Number(generalQuantity.value)
    if (!reservationRequestId.value) reservationRequestId.value = createRequestId()
    reservation.value = await apiRequest(
      `/disponibilidad/eventos/${encodeURIComponent(selectedEvent.value.id)}/reservas`,
      userId.value,
      {
        method: 'POST',
        body: JSON.stringify({ solicitudId: reservationRequestId.value, detalles }),
      },
    )
    if (selectedSector.value?.disponibilidad) {
      selectedSector.value.disponibilidad.cantidadDisponible = Math.max(
        0,
        selectedSector.value.disponibilidad.cantidadDisponible - (isNumbered.value ? seatsToReserve.length : quantityToReserve),
      )
      if (isNumbered.value) {
        const reserved = new Set(seatsToReserve)
        selectedSector.value.disponibilidad.asientosDisponibles =
          selectedSector.value.disponibilidad.asientosDisponibles.filter(
            (seat) => !reserved.has(`${seat.fila}-${seat.numero}`),
          )
      }
    }
    successMessage.value = `Reserva creada. Vence ${formatDate(reservation.value.expiraEn)}.`
  } catch (error) {
    reportError(error)
  } finally {
    busy.value = ''
  }
}

async function startPurchase() {
  if (!reservation.value?.reservaId) return
  clearNotices()
  busy.value = 'purchase'
  try {
    purchase.value = await apiRequest('/compras', userId.value, {
      method: 'POST',
      body: JSON.stringify({ reservaId: reservation.value.reservaId }),
    })
    paymentKey.value = ''
    successMessage.value = `Compra ${purchase.value.id} iniciada.`
  } catch (error) {
    reportError(error)
  } finally {
    busy.value = ''
  }
}

async function payPurchase() {
  if (!purchase.value?.id) return
  clearNotices()
  busy.value = 'payment'
  if (!paymentKey.value) paymentKey.value = createRequestId()
  try {
    purchase.value = await apiRequest(`/compras/${encodeURIComponent(purchase.value.id)}/pago`, userId.value, {
      method: 'POST',
      headers: { 'Idempotency-Key': paymentKey.value },
      body: JSON.stringify({ medioDePago: paymentMethod.value }),
    })
    paymentKey.value = ''
    if (purchase.value.estado === 'CONFIRMADA') {
      activeTab.value = 'entradas'
      await loadTickets(0)
      successMessage.value = 'Pago aprobado por la simulacion local. Tu entrada ya esta emitida.'
    } else {
      successMessage.value = `Respuesta del pago: ${purchase.value.estado}.`
    }
  } catch (error) {
    if (error.status && error.status < 500) paymentKey.value = ''
    reportError(error)
  } finally {
    busy.value = ''
  }
}

async function loadTickets(page = 0) {
  clearNotices()
  busy.value = 'tickets'
  try {
    const query = new URLSearchParams({ page: String(page), size: '50' })
    const result = await apiRequest(`/entradas?${query}`, userId.value)
    tickets.value = result?.content || []
    ticketPage.value = result?.page ?? page
    ticketTotalPages.value = result?.totalPages ?? 0
  } catch (error) {
    reportError(error)
  } finally {
    busy.value = ''
  }
}

function resetSearch() {
  selectedEvent.value = null
  reservation.value = null
  purchase.value = null
  loadEvents(0)
}

function formatDate(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function formatPrice(value) {
  return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 2 }).format(value || 0)
}

watch(userId, (value) => {
  localStorage.setItem('ticketya.userId', value.trim())
})

onMounted(async () => {
  await loadEvents()
  await loadTickets()
})
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <a class="brand" href="#" @click.prevent="resetSearch" aria-label="TicketYa inicio">
        <span class="brand-mark">t</span>
        <span>ticket<span class="brand-light">ya</span></span>
      </a>
      <nav class="main-nav" aria-label="Navegacion principal">
        <button :class="{ active: activeTab === 'eventos' }" @click="activeTab = 'eventos'">Explorar</button>
        <button :class="{ active: activeTab === 'entradas' }" @click="activeTab = 'entradas'; loadTickets(0)">
          Mis entradas
          <span v-if="tickets.length" class="nav-count">{{ tickets.length }}</span>
        </button>
      </nav>
      <label class="identity-control">
        <span class="identity-dot"></span>
        <span class="identity-label">Usuario demo</span>
        <input v-model="userId" aria-label="Identificador de usuario demo" maxlength="255" />
      </label>
    </header>

    <main>
      <section v-if="activeTab === 'eventos'" class="events-page">
        <div v-if="!selectedEvent" class="hero">
          <div class="hero-copy">
            <p class="eyebrow"><span class="live-dot"></span> VIVI LO QUE VIENE</p>
            <h1>Tu proxima<br /><span>gran noche</span> empieza aca.</h1>
            <p class="hero-description">Descubri eventos, elegi tu lugar y asegura tu entrada en unos pocos pasos.</p>
            <div class="hero-proof">
              <span class="proof-icon">✳</span>
              <span>Compra segura, entrada al instante</span>
            </div>
          </div>
          <div class="hero-art" aria-hidden="true">
            <div class="art-glow"></div>
            <div class="ticket-art">
              <div class="ticket-art-top"><span>TICKETYA</span><span>EN VIVO</span></div>
              <div class="ticket-art-symbol">♫</div>
              <div class="ticket-art-title">UNA NOCHE<br />INOLVIDABLE</div>
              <div class="ticket-art-line"></div>
              <div class="ticket-art-meta"><span>BUENOS AIRES</span><span>20:30 HS</span></div>
            </div>
            <span class="spark spark-one">✦</span><span class="spark spark-two">✳</span>
          </div>
          <div class="hero-index"><span>01</span> / 03</div>
        </div>

        <section class="search-section">
          <div class="section-heading">
            <div>
              <p class="eyebrow">AGENDA ABIERTA</p>
              <h2>Encontra tu plan</h2>
            </div>
            <span class="result-count">{{ totalPages ? `${events.length} eventos` : 'Descubri algo nuevo' }}</span>
          </div>
          <form class="search-bar" @submit.prevent="loadEvents(0)">
            <label class="search-field query-field">
              <span class="field-icon">⌕</span>
              <input v-model="search.q" placeholder="Artista, evento o palabra clave" />
            </label>
            <label class="search-field">
              <span class="field-caption">TIPO</span>
              <input v-model="search.tipo" placeholder="Todos" />
            </label>
            <label class="search-field city-field">
              <span class="field-caption">CIUDAD</span>
              <input v-model="search.ciudad" placeholder="Todas las ciudades" />
            </label>
            <button class="button button-dark search-button" :disabled="busy === 'events'">
              {{ busy === 'events' ? 'Buscando...' : 'Buscar eventos' }}
              <span aria-hidden="true">→</span>
            </button>
          </form>

          <div v-if="errorMessage" class="notice notice-error" role="alert">
            <span class="notice-icon">!</span><span>{{ errorMessage }}</span>
            <button class="notice-close" aria-label="Cerrar" @click="errorMessage = ''">×</button>
          </div>
          <div v-if="successMessage && activeTab === 'eventos'" class="notice notice-success" role="status">
            <span class="notice-icon">✓</span><span>{{ successMessage }}</span>
            <button class="notice-close" aria-label="Cerrar" @click="successMessage = ''">×</button>
          </div>

          <div v-if="!events.length && busy !== 'events' && !errorMessage" class="empty-state">
            <span class="empty-illustration">♫</span>
            <h3>No encontramos eventos por ahora</h3>
            <p>Proba con otra busqueda o revisa que MS1 este disponible a traves del Gateway.</p>
          </div>
          <div v-else class="event-grid">
            <article v-for="(event, index) in events" :key="event.id" class="event-card">
              <div class="event-cover" :class="`cover-${index % 4}`">
                <span class="cover-type">{{ event.tipo || 'EVENTO' }}</span>
                <span class="cover-icon" aria-hidden="true">{{ ['♫', '✳', '◉', '✦'][index % 4] }}</span>
                <span v-if="event.ventaSuspendida" class="cover-status">VENTA SUSPENDIDA</span>
              </div>
              <div class="event-card-body">
                <div class="event-date">{{ formatDate(event.fechaHora) }}</div>
                <h3>{{ event.nombre }}</h3>
                <p class="event-meta">{{ event.participantes?.join(' · ') || event.ciudad }}</p>
                <div class="event-card-bottom">
                  <span class="event-price">
                    <small>Desde</small>{{ formatPrice(event.precioMinimo) }}
                  </span>
                  <button class="button button-outline" :disabled="busy === 'detail'" @click="openEvent(event)">
                    Ver evento <span aria-hidden="true">↗</span>
                  </button>
                </div>
              </div>
            </article>
          </div>
          <div v-if="totalPages > 1" class="pagination">
            <button class="button button-outline" :disabled="eventPage <= 0 || busy !== ''" @click="loadEvents(eventPage - 1)">Anterior</button>
            <span>Pagina {{ eventPage + 1 }} de {{ totalPages }}</span>
            <button class="button button-outline" :disabled="eventPage + 1 >= totalPages || busy !== ''" @click="loadEvents(eventPage + 1)">Siguiente</button>
          </div>
        </section>

        <section v-if="selectedEvent" class="detail-backdrop" aria-label="Detalle del evento">
          <div class="detail-panel">
            <div class="detail-heading">
              <button class="back-link" @click="selectedEvent = null; reservation = null; purchase = null">← Volver a eventos</button>
              <span class="event-date">{{ formatDate(selectedEvent.fechaHora) }}</span>
            </div>
            <div class="detail-title-row">
              <div>
                <p class="eyebrow">{{ selectedEvent.tipo }} <span v-if="selectedEvent.lugar?.ciudad">· {{ selectedEvent.lugar.ciudad }}</span></p>
                <h2>{{ selectedEvent.nombre }}</h2>
                <p class="detail-description">{{ selectedEvent.descripcion }}</p>
              </div>
              <div class="venue-pill"><span>⌖</span>{{ selectedEvent.lugar?.nombre }}</div>
            </div>

            <div v-if="errorMessage" class="notice notice-error" role="alert">
              <span class="notice-icon">!</span><span>{{ errorMessage }}</span>
              <button class="notice-close" aria-label="Cerrar" @click="errorMessage = ''">×</button>
            </div>
            <div v-if="successMessage" class="notice notice-success" role="status">
              <span class="notice-icon">✓</span><span>{{ successMessage }}</span>
              <button class="notice-close" aria-label="Cerrar" @click="successMessage = ''">×</button>
            </div>
            <div v-if="selectedEvent.ventaSuspendida" class="notice notice-error">La venta de este evento esta suspendida.</div>
            <div v-else class="booking-layout">
              <div class="sector-list">
                <p class="eyebrow">1 — ELEGÍ TU SECTOR</p>
                <button
                  v-for="sector in selectedEvent.sectores"
                  :key="sector.sectorId"
                  class="sector-option"
                  :class="{ chosen: selectedSectorId === sector.sectorId }"
                  :disabled="Boolean(reservation) || !sector.disponibilidad || sector.disponibilidad.cantidadDisponible < 1"
                  @click="selectSector(sector)"
                >
                  <span class="sector-radio"><span></span></span>
                  <span class="sector-info">
                    <strong>{{ sector.nombre }}</strong>
                    <small>{{ sector.tipo === 'NUMERADO' ? 'Asiento numerado' : 'Sector general' }} · {{ sector.disponibilidad?.cantidadDisponible ?? 0 }} disponibles</small>
                  </span>
                  <strong class="sector-price">{{ formatPrice(sector.precio) }}</strong>
                </button>
                <p v-if="!selectedEvent.sectores?.length" class="muted-copy">Este evento no tiene sectores disponibles.</p>
              </div>

              <div class="seat-selection">
                <p class="eyebrow">2 — ELEGÍ TU LUGAR</p>
                <div v-if="!selectedSector" class="seat-placeholder">
                  <span>▦</span><p>Selecciona un sector<br />para ver los lugares</p>
                </div>
                <template v-else-if="isNumbered">
                  <div class="stage-label">ESCENARIO</div>
                  <div v-if="availableSeats.length" class="seat-grid">
                    <button
                      v-for="seat in availableSeats"
                      :key="`${seat.fila}-${seat.numero}`"
                      class="seat"
                      :class="{ selected: isSeatSelected(seat) }"
                      :aria-label="`Fila ${seat.fila}, asiento ${seat.numero}`"
                      :title="`Fila ${seat.fila} · Asiento ${seat.numero}`"
                      :disabled="Boolean(reservation)"
                      @click="toggleSeat(seat)"
                    >
                      {{ seat.numero }}
                    </button>
                  </div>
                  <p v-else class="muted-copy">No hay asientos disponibles en este sector.</p>
                  <div class="seat-legend"><span><i class="legend-free"></i>Disponible</span><span><i class="legend-selected"></i>Tu seleccion</span></div>
                  <p v-if="selectedSeats.length" class="selection-summary">{{ selectedSeats.length }} asiento{{ selectedSeats.length === 1 ? '' : 's' }} seleccionado{{ selectedSeats.length === 1 ? '' : 's' }}</p>
                </template>
                <div v-else class="quantity-picker">
                  <p>Entradas para {{ selectedSector.nombre }}</p>
                  <div class="quantity-control">
                    <button aria-label="Restar una entrada" :disabled="Boolean(reservation) || generalQuantity <= 1" @click="generalQuantity--; reservationRequestId = ''">−</button>
                    <strong>{{ generalQuantity }}</strong>
                    <button aria-label="Sumar una entrada" :disabled="Boolean(reservation) || generalQuantity >= selectedSector.disponibilidad.cantidadDisponible" @click="generalQuantity++; reservationRequestId = ''">+</button>
                  </div>
                  <small>Hasta {{ selectedSector.disponibilidad.cantidadDisponible }} disponibles</small>
                </div>
              </div>
            </div>

            <div v-if="selectedSector" class="order-summary">
              <div><small>{{ selectionCount }} lugar{{ selectionCount === 1 ? '' : 'es' }}</small><strong>{{ formatPrice(Number(selectedSector.precio || 0) * selectionCount) }}</strong></div>
              <button class="button button-green" :disabled="!canReserve || busy !== ''" @click="reservePlaces">
                {{ busy === 'reserve' ? 'Reservando...' : 'Reservar lugar' }} <span aria-hidden="true">→</span>
              </button>
            </div>

            <div v-if="reservation" class="flow-card">
              <div class="flow-step"><span class="step-number done">✓</span><div><strong>Reserva lista</strong><small>Vence {{ formatDate(reservation.expiraEn) }} · {{ reservation.reservaId }}</small></div></div>
              <button v-if="!purchase" class="button button-dark" :disabled="busy !== ''" @click="startPurchase">
                {{ busy === 'purchase' ? 'Iniciando compra...' : 'Continuar a la compra' }} <span aria-hidden="true">→</span>
              </button>
              <template v-else>
                <div class="flow-step"><span class="step-number done">✓</span><div><strong>Compra {{ purchase.estado }}</strong><small>{{ purchase.id }} · Total {{ formatPrice(purchase.total) }}</small></div></div>
                <div v-if="purchase.estado !== 'CONFIRMADA'" class="payment-row">
                  <label>
                    <span class="field-caption">MEDIO DE PAGO DE PRUEBA</span>
                    <select v-model="paymentMethod">
                      <option value="CREDIT_CARD">Tarjeta de credito</option>
                      <option value="DEBIT_CARD">Tarjeta de debito</option>
                      <option value="BANK_TRANSFER">Transferencia bancaria</option>
                    </select>
                  </label>
                  <button class="button button-green" :disabled="busy !== ''" @click="payPurchase">
                    {{ busy === 'payment' ? 'Procesando...' : paymentKey ? 'Reintentar pago' : `Pagar ${formatPrice(purchase.total)}` }}
                  </button>
                </div>
                <p class="simulation-note">Pago de demostracion: MS4 usa una simulacion local. No se realiza un cobro real.</p>
              </template>
            </div>
          </div>
        </section>
      </section>

      <section v-else class="tickets-page">
        <div class="tickets-heading">
          <div><p class="eyebrow">TUS PROXIMAS HISTORIAS</p><h1>Mis entradas<span>.</span></h1><p>Todo listo para vivirlo.</p></div>
          <button class="button button-outline" :disabled="busy !== ''" @click="loadTickets(ticketPage)">↻ Actualizar</button>
        </div>
        <div v-if="errorMessage" class="notice notice-error" role="alert"><span class="notice-icon">!</span>{{ errorMessage }}</div>
        <div v-if="successMessage" class="notice notice-success" role="status"><span class="notice-icon">✓</span>{{ successMessage }}</div>
        <div v-if="!tickets.length && busy !== 'tickets' && !errorMessage" class="empty-state ticket-empty">
          <span class="empty-illustration">✳</span><h3>Todavia no tenes entradas</h3>
          <p>Cuando completes una compra, tus entradas van a aparecer aca.</p>
          <button class="button button-dark" @click="activeTab = 'eventos'">Explorar eventos <span>→</span></button>
        </div>
        <div v-else class="ticket-list">
          <article v-for="ticket in tickets" :key="ticket.idEntrada" class="ticket-card">
            <div class="ticket-date-block"><span>{{ new Date(ticket.evento?.comienzaEn).toLocaleDateString('es-AR', { day: '2-digit' }) }}</span><small>{{ new Date(ticket.evento?.comienzaEn).toLocaleDateString('es-AR', { month: 'short' }) }}</small></div>
            <div class="ticket-main">
              <div class="ticket-status" :class="`status-${ticket.estado?.toLowerCase()}`"><i></i>{{ ticket.estado }}</div>
              <h3>{{ ticket.evento?.nombre || 'Evento' }}</h3>
              <p>{{ ticket.sector?.nombre || 'Sector' }}<span v-if="ticket.fila && ticket.numero"> · Fila {{ ticket.fila }}, asiento {{ ticket.numero }}</span></p>
              <small>{{ formatDate(ticket.evento?.comienzaEn) }}</small>
            </div>
            <div class="ticket-code"><span class="ticket-code-label">CODIGO DE ACCESO</span><code>{{ ticket.codigoQr }}</code><small>{{ ticket.idEntrada }}</small></div>
          </article>
        </div>
        <div v-if="ticketTotalPages > 1" class="pagination">
          <button class="button button-outline" :disabled="ticketPage <= 0 || busy !== ''" @click="loadTickets(ticketPage - 1)">Anterior</button>
          <span>Pagina {{ ticketPage + 1 }} de {{ ticketTotalPages }}</span>
          <button class="button button-outline" :disabled="ticketPage + 1 >= ticketTotalPages || busy !== ''" @click="loadTickets(ticketPage + 1)">Siguiente</button>
        </div>
      </section>
    </main>

    <footer class="footer">
      <a class="brand footer-brand" href="#" @click.prevent="resetSearch"><span class="brand-mark">t</span><span>ticket<span class="brand-light">ya</span></span></a>
      <span>Una demo conectada a tus servicios TicketYa</span>
      <span class="gateway-status"><i></i> API Gateway <code>{{ gatewayUrl }}</code></span>
    </footer>
  </div>
</template>
