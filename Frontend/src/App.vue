<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import QRCode from 'qrcode'
import { apiRequest, gatewayUrl } from './api'
import LoginView from './LoginView.vue'
import MisCompras from './MisCompras.vue'
import PanelAdmin from './PanelAdmin.vue'
import PanelOrganizador from './PanelOrganizador.vue'
import ControlAcceso from './ControlAcceso.vue'
import { endSession, hasRole, isLoggedIn, session, setProfileName } from './session'

const ROLE_LABELS = { CLIENTE: 'Cliente', ORGANIZADOR: 'Organizador', ADMIN: 'Admin', PERSONAL_ACCESO: 'Personal de acceso' }
// Comprar es una operación de CLIENTE; consultar entradas, de CLIENTE o ADMIN (reglas del Gateway y de MS5).
const canBuy = computed(() => hasRole('CLIENTE'))
const canSeeTickets = computed(() => hasRole('CLIENTE') || hasRole('ADMIN'))
const isAdmin = computed(() => hasRole('ADMIN'))
const isOrganizer = computed(() => hasRole('ORGANIZADOR'))
const canValidate = computed(() => hasRole('PERSONAL_ACCESO') || hasRole('ADMIN'))
const displayName = computed(() => session.value?.nombre || session.value?.email || '')
const roleLabels = computed(() => (session.value?.roles || []).map((role) => ROLE_LABELS[role] || role).join(' · '))
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
const paymentAudio = ref(null)
const paymentSuccessVisible = ref(false)
const ticketQr = ref(null)
const ticketQrImage = ref('')
const ticketQrEvent = ref(null)
const ticketQrError = ref('')
const ticketQrLoading = ref(false)
const ticketQrRequest = ref(0)
const errorMessage = ref('')
const successMessage = ref('')
const eventTypes = ref([])
const eventCities = ref([])

const selectedSector = computed(() =>
  selectedEvent.value?.sectores?.find((sector) => sector.sectorId === selectedSectorId.value) || null,
)
const seatGridSeats = computed(() => {
  const sector = selectedSector.value
  const availability = sector?.disponibilidad
  if (!availability) return []

  const states = availability.asientos || availability.asientosDisponibles || []
  const seatStates = new Map(states.map((seat) => [`${seat.fila}-${seat.numero}`, seat.estado]))
  const freeSeats = new Set((availability.asientosDisponibles || [])
    .map((seat) => `${seat.fila}-${seat.numero}`))

  if (Number.isInteger(sector.filas) && Number.isInteger(sector.asientosPorFila)) {
    return Array.from({ length: sector.filas * sector.asientosPorFila }, (_, index) => {
      const fila = Math.floor(index / sector.asientosPorFila) + 1
      const numero = (index % sector.asientosPorFila) + 1
      const key = `${fila}-${numero}`
      const estado = seatStates.get(key) || (freeSeats.has(key) ? 'LIBRE' : 'OCUPADO')
      return { fila, numero, estado }
    })
  }

  return states
})
const isNumbered = computed(() => selectedSector.value?.tipo === 'NUMERADO')
const selectionCount = computed(() => isNumbered.value ? selectedSeats.value.length : Number(generalQuantity.value || 0))
const reservationSeatLabels = computed(() => selectedSeats.value.map((seat) => {
  const [fila, numero] = seat.split('-')
  return `Fila ${fila} · Asiento ${numero}`
}))
const canReserve = computed(() => Boolean(
  canBuy.value
  && selectedEvent.value
  && selectedSector.value?.disponibilidad
  && !selectedEvent.value.ventaSuspendida
  && !reservation.value
  && (isNumbered.value ? selectedSeats.value.length > 0 : generalQuantity.value > 0),
))

watch(() => [selectedEvent.value?.id, activeTab.value], ([eventId, tab], _previous, onCleanup) => {
  if (!eventId || tab !== 'eventos') return
  const timer = window.setInterval(() => refreshEventAvailability(eventId), 5000)
  onCleanup(() => window.clearInterval(timer))
})

function eventVisual(event) {
  const type = (event.tipo || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase()

  if (type.includes('musica')) return { cover: 'cover-music', icon: '♫' }
  if (type.includes('teatro')) return { cover: 'cover-theater', icon: '✳' }
  if (type.includes('deporte')) return { cover: 'cover-sport', icon: '◉' }
  return { cover: 'cover-default', icon: '✦' }
}

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
    const query = new URLSearchParams({
      page: String(page),
      size: '12',
      orden: search.value.q.trim() ? 'relevancia' : 'fecha',
      direccion: 'asc',
    })
    for (const [key, value] of Object.entries(search.value)) {
      if (value.trim()) query.set(key, value.trim())
    }
    const result = await apiRequest(`/eventos?${query}`)
    events.value = result?.contenido || []
    eventPage.value = result?.pagina ?? page
    totalPages.value = result?.totalPaginas ?? 0
  } catch (error) {
    reportError(error)
  } finally {
    busy.value = ''
  }
}

async function loadSearchOptions() {
  try {
    const query = new URLSearchParams({ page: '0', size: '100', orden: 'fecha', direccion: 'asc' })
    const result = await apiRequest(`/eventos?${query}`)
    if (!Array.isArray(result?.contenido)) {
      throw new Error('MS1 devolvió un catálogo de eventos con un formato inesperado.')
    }

    eventTypes.value = [...new Set(result.contenido.map((event) => event.tipo).filter(Boolean))]
      .sort((a, b) => a.localeCompare(b, 'es'))
    eventCities.value = [...new Set(result.contenido.map((event) => event.ciudad).filter(Boolean))]
      .sort((a, b) => a.localeCompare(b, 'es'))
  } catch (error) {
    reportError(error)
  }
}

async function openEvent(event) {
  clearNotices()
  busy.value = 'detail'
  try {
    selectedEvent.value = await apiRequest(`/eventos/${encodeURIComponent(event.id)}`)
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

async function refreshEventAvailability(eventId) {
  if (busy.value || selectedEvent.value?.id !== eventId || activeTab.value !== 'eventos') return
  try {
    const updatedEvent = await apiRequest(`/eventos/${encodeURIComponent(eventId)}`)
    if (selectedEvent.value?.id !== eventId) return

    if (reservation.value && Date.parse(reservation.value.expiraEn) <= Date.now()) {
      reservation.value = null
      reservationRequestId.value = ''
      purchase.value = null
      paymentKey.value = ''
      selectedSeats.value = []
      generalQuantity.value = 1
      successMessage.value = 'La reserva venció y los lugares vuelven a estar disponibles.'
    }

    selectedEvent.value = updatedEvent
    const updatedAvailability = updatedEvent.sectores
      ?.find((sector) => sector.sectorId === selectedSectorId.value)?.disponibilidad
    const seats = updatedAvailability?.asientos || updatedAvailability?.asientosDisponibles
    if (!reservation.value && seats) {
      const libre = new Set(seats.filter((seat) => !seat.estado || seat.estado === 'LIBRE')
        .map((seat) => `${seat.fila}-${seat.numero}`))
      const selectedSeatsStillFree = selectedSeats.value.filter((seat) => libre.has(seat))
      if (selectedSeatsStillFree.length !== selectedSeats.value.length) {
        selectedSeats.value = selectedSeatsStillFree
        reservationRequestId.value = ''
        errorMessage.value = 'Un asiento seleccionado dejó de estar disponible. Elegí otro lugar.'
      }
    }
  } catch (error) {
    reportError(error)
  }
}

function closeEvent() {
  selectedEvent.value = null
  selectedSectorId.value = ''
  selectedSeats.value = []
  generalQuantity.value = 1
  reservation.value = null
  reservationRequestId.value = ''
  purchase.value = null
  paymentKey.value = ''
  clearNotices()
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
        const allSeats = selectedSector.value.disponibilidad.asientos
          || selectedSector.value.disponibilidad.asientosDisponibles
        selectedSector.value.disponibilidad.asientos = allSeats.map((seat) =>
          reserved.has(`${seat.fila}-${seat.numero}`) ? { ...seat, estado: 'RESERVADO' } : seat,
        )
        selectedSector.value.disponibilidad.asientosDisponibles =
          selectedSector.value.disponibilidad.asientosDisponibles.filter(
            (seat) => !reserved.has(`${seat.fila}-${seat.numero}`),
          )
      }
    }
    successMessage.value = ''
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
    purchase.value = await apiRequest('/compras', {
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
  if (!purchase.value?.id || busy.value) return
  clearNotices()
  busy.value = 'payment'
  if (paymentAudio.value) {
    paymentAudio.value.currentTime = 0
    paymentAudio.value.play().catch((error) => {
      console.warn('No se pudo reproducir el sonido de pago.', error)
    })
  }
  if (!paymentKey.value) paymentKey.value = createRequestId()
  try {
    purchase.value = await apiRequest(`/compras/${encodeURIComponent(purchase.value.id)}/pago`, {
      method: 'POST',
      headers: { 'Idempotency-Key': paymentKey.value },
      body: JSON.stringify({ medioDePago: paymentMethod.value }),
    })
    paymentKey.value = ''
    if (purchase.value.estado === 'CONFIRMADA') {
      paymentSuccessVisible.value = true
      await new Promise((resolve) => window.setTimeout(resolve, 2300))
      activeTab.value = 'entradas'
      try {
        const ticketsLoaded = await loadTickets(0)
        if (ticketsLoaded) {
          successMessage.value = 'Pago aprobado por la simulacion local. Tu entrada ya esta emitida.'
        }
      } finally {
        paymentSuccessVisible.value = false
      }
    } else {
      if (purchase.value.estado === 'FALLIDA') {
        reservation.value = null
        reservationRequestId.value = ''
        purchase.value = null
        paymentKey.value = ''
        selectedSeats.value = []
        generalQuantity.value = 1
        const eventId = selectedEvent.value?.id
        if (eventId) {
          selectedEvent.value = await apiRequest(`/eventos/${encodeURIComponent(eventId)}`)
        }
        successMessage.value = 'El pago fue rechazado y los lugares ya están disponibles nuevamente.'
      } else {
        successMessage.value = `Respuesta del pago: ${purchase.value.estado}.`
      }
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
    const result = await apiRequest(`/entradas?${query}`)
    tickets.value = result?.content || []
    ticketPage.value = result?.page ?? page
    ticketTotalPages.value = result?.totalPages ?? 0
    return true
  } catch (error) {
    reportError(error)
    return false
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

async function showTicketQr(ticket) {
  const requestId = ++ticketQrRequest.value
  ticketQr.value = ticket
  ticketQrImage.value = ''
  ticketQrEvent.value = events.value.find((event) => event.id === ticket.evento?.id) || null
  ticketQrError.value = ''
  ticketQrLoading.value = true
  try {
    if (!ticket.codigoQr) {
      throw new Error('Esta entrada no tiene un código QR disponible.')
    }
    const [image, eventDetails] = await Promise.all([
      QRCode.toDataURL(ticket.codigoQr, {
        errorCorrectionLevel: 'H',
        margin: 1,
        width: 280,
        color: { dark: '#17231f', light: '#ffffff' },
      }),
      ticket.evento?.id
        ? apiRequest(`/eventos/${encodeURIComponent(ticket.evento.id)}`)
          .catch((error) => {
            console.warn('No se pudieron cargar los datos ampliados del evento para la entrada.', error)
            return null
          })
        : Promise.resolve(null),
    ])
    if (requestId !== ticketQrRequest.value) return
    ticketQrImage.value = image
    ticketQrEvent.value = eventDetails || ticketQrEvent.value
  } catch (error) {
    if (requestId !== ticketQrRequest.value) return
    ticketQrError.value = error instanceof Error ? error.message : 'No se pudo generar el QR de la entrada.'
    ticketQr.value = null
  } finally {
    if (requestId === ticketQrRequest.value) ticketQrLoading.value = false
  }
}

function closeTicketQr() {
  ticketQrRequest.value++
  ticketQr.value = null
  ticketQrImage.value = ''
  ticketQrEvent.value = null
  ticketQrError.value = ''
  ticketQrLoading.value = false
}

function printTicket() {
  window.print()
}

/** Nombre para la barra superior: el token trae el correo; el nombre sale del perfil de MS6. */
async function loadProfile() {
  try {
    const profile = await apiRequest('/usuarios/me')
    if (profile?.nombre) setProfileName(`${profile.nombre} ${profile.apellido || ''}`.trim())
  } catch (error) {
    console.warn('No se pudo cargar el perfil del usuario.', error)
  }
}

async function loadInitialData() {
  const pending = [loadEvents(), loadSearchOptions(), loadProfile()]
  if (canSeeTickets.value) pending.push(loadTickets())
  await Promise.all(pending)
}

/** Al cerrar sesión no queda nada del usuario anterior en pantalla. */
function resetUserState() {
  closeEvent()
  closeTicketQr()
  activeTab.value = 'eventos'
  events.value = []
  tickets.value = []
  ticketPage.value = 0
  ticketTotalPages.value = 0
}

function logout() {
  endSession()
}

watch(isLoggedIn, (loggedIn) => {
  resetUserState()
  if (loggedIn) loadInitialData()
})

onMounted(async () => {
  if (isLoggedIn.value) await loadInitialData()
})
</script>

<template>
  <div class="app-shell">
    <audio ref="paymentAudio" src="/payment-success.mp3" preload="auto" hidden></audio>
    <header class="topbar">
      <a class="brand" href="#" @click.prevent="resetSearch" aria-label="TicketYa inicio">
        <img class="brand-logo" src="/ticketya-logo.png" alt="" draggable="false" />
      </a>
      <nav v-if="isLoggedIn" class="main-nav" aria-label="Navegacion principal">
        <button :class="{ active: activeTab === 'eventos' }" @click="activeTab = 'eventos'">Explorar</button>
        <button v-if="canSeeTickets" :class="{ active: activeTab === 'entradas' }" @click="activeTab = 'entradas'; loadTickets(0)">
          Mis entradas
          <span v-if="tickets.length" class="nav-count">{{ tickets.length }}</span>
        </button>
        <button v-if="canBuy" :class="{ active: activeTab === 'compras' }" @click="activeTab = 'compras'">Mis compras</button>
        <button v-if="isOrganizer" :class="{ active: activeTab === 'organizador' }" @click="activeTab = 'organizador'">Mis eventos</button>
        <button v-if="canValidate" :class="{ active: activeTab === 'acceso' }" @click="activeTab = 'acceso'">Control de acceso</button>
        <button v-if="isAdmin" :class="{ active: activeTab === 'admin' }" @click="activeTab = 'admin'">Panel admin</button>
      </nav>
      <div v-if="isLoggedIn" class="identity-control" aria-label="Usuario con sesión iniciada">
        <span class="identity-dot"></span>
        <span class="identity-text">
          <strong>{{ displayName }}</strong>
          <small>{{ roleLabels }}</small>
        </span>
        <button class="logout-button" type="button" @click="logout">Cerrar sesión</button>
      </div>
    </header>

    <LoginView v-if="!isLoggedIn" />

    <main v-else>
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
              <select v-model="search.tipo" aria-label="Filtrar por tipo de evento">
                <option value="">Todos los tipos</option>
                <option v-for="type in eventTypes" :key="type" :value="type">{{ type }}</option>
              </select>
            </label>
            <label class="search-field city-field">
              <span class="field-caption">CIUDAD</span>
              <select v-model="search.ciudad" aria-label="Filtrar por ciudad">
                <option value="">Todas las ciudades</option>
                <option v-for="city in eventCities" :key="city" :value="city">{{ city }}</option>
              </select>
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
            <article v-for="event in events" :key="event.id" class="event-card">
              <div class="event-cover" :class="eventVisual(event).cover">
                <span class="cover-type">{{ event.tipo || 'EVENTO' }}</span>
                <span class="cover-icon" aria-hidden="true">{{ eventVisual(event).icon }}</span>
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
              <button class="back-link" @click="closeEvent">
                <span class="back-arrow" aria-hidden="true">←</span> Volver a eventos
              </button>
              <span class="event-date">{{ formatDate(selectedEvent.fechaHora) }}</span>
              <button class="detail-close" type="button" aria-label="Cerrar ventana de reserva" title="Cerrar" @click="closeEvent">×</button>
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
            <div v-else-if="!reservation" class="booking-layout">
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
                  <div v-if="seatGridSeats.length" class="seat-grid">
                    <button
                      v-for="seat in seatGridSeats"
                      :key="`${seat.fila}-${seat.numero}`"
                      class="seat"
                      :class="{ selected: isSeatSelected(seat), unavailable: seat.estado !== 'LIBRE' }"
                      :aria-label="`Fila ${seat.fila}, asiento ${seat.numero}${seat.estado !== 'LIBRE' ? `, ${seat.estado === 'RESERVADO' ? 'reservado temporalmente' : seat.estado === 'VENDIDO' ? 'vendido' : 'no disponible'}` : ''}`"
                      :title="`Fila ${seat.fila} · Asiento ${seat.numero}${seat.estado !== 'LIBRE' ? ` · ${seat.estado === 'RESERVADO' ? 'Reservado temporalmente' : seat.estado === 'VENDIDO' ? 'Vendido' : 'No disponible'}` : ''}`"
                      :disabled="Boolean(reservation) || (seat.estado && seat.estado !== 'LIBRE')"
                      @click="toggleSeat(seat)"
                    >
                      {{ seat.numero }}
                    </button>
                  </div>
                  <p v-else class="muted-copy">No hay asientos disponibles en este sector.</p>
                  <div class="seat-legend"><span><i class="legend-free"></i>Disponible</span><span><i class="legend-selected"></i>Tu seleccion</span><span><i class="legend-reserved"></i>Reservado</span></div>
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

            <div v-if="selectedSector && !reservation" class="order-summary">
              <div><small>{{ selectionCount }} lugar{{ selectionCount === 1 ? '' : 'es' }}</small><strong>{{ formatPrice(Number(selectedSector.precio || 0) * selectionCount) }}</strong></div>
              <p v-if="!canBuy" class="role-note">Solo una cuenta de cliente puede comprar entradas.</p>
              <button v-else class="button button-green" :disabled="!canReserve || busy !== ''" @click="reservePlaces">
                {{ busy === 'reserve' ? 'Reservando...' : 'Reservar lugar' }} <span aria-hidden="true">→</span>
              </button>
            </div>

            <section v-if="reservation" class="reservation-view">
              <div class="reservation-view-heading">
                <span class="reservation-confirmation-mark" aria-hidden="true">✓</span>
                <div><p class="eyebrow">TU PEDIDO</p><h3>Reserva lista</h3><p>Vence {{ formatDate(reservation.expiraEn) }} · Cerrar no cancela la reserva</p></div>
              </div>
              <div class="reservation-details">
                <div><span>Sector</span><strong>{{ selectedSector.nombre }}</strong></div>
                <div><span>Cantidad</span><strong>{{ selectionCount }} lugar{{ selectionCount === 1 ? '' : 'es' }}</strong></div>
                <div v-if="isNumbered" class="reservation-seats">
                  <span>Asientos</span>
                  <strong>{{ reservationSeatLabels.join(', ') }}</strong>
                </div>
                <div class="reservation-total"><span>Total</span><strong>{{ formatPrice(Number(selectedSector.precio || 0) * selectionCount) }}</strong></div>
              </div>
              <div v-if="!purchase" class="reservation-actions">
                <button class="button button-dark" :disabled="busy !== ''" @click="startPurchase">
                  {{ busy === 'purchase' ? 'Iniciando compra...' : 'Continuar con la compra' }} <span aria-hidden="true">→</span>
                </button>
              </div>
              <template v-else>
                <div class="flow-step"><span class="step-number done">✓</span><div><strong>Compra {{ purchase.estado }}</strong><small>{{ purchase.id }} · Total {{ formatPrice(purchase.total) }}</small></div></div>
                <div v-if="purchase.estado === 'PENDIENTE'" class="payment-row">
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
                <p v-if="purchase.estado === 'FALLIDA'" class="simulation-note">
                  El pago no se completó. La reserva se libera automáticamente al rechazarse el pago o vencer su plazo. Podés volver a eventos para elegir otra opción.
                </p>
                <p v-else class="simulation-note">Pago de demostracion: MS4 usa una simulacion local. No se realiza un cobro real.</p>
              </template>
            </section>
          </div>
        </section>
      </section>

      <MisCompras v-else-if="activeTab === 'compras' && canBuy" />

      <PanelAdmin v-else-if="activeTab === 'admin' && isAdmin" />

      <PanelOrganizador v-else-if="activeTab === 'organizador' && isOrganizer" />

      <ControlAcceso v-else-if="activeTab === 'acceso' && canValidate" />

      <section v-else-if="activeTab === 'entradas' && canSeeTickets" class="tickets-page">
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
            <div class="ticket-code">
              <span class="ticket-code-label">ENTRADA DIGITAL</span>
              <button class="button button-outline" @click="showTicketQr(ticket)">Ver QR</button>
              <small>{{ ticket.idEntrada }}</small>
            </div>
          </article>
        </div>
        <div v-if="ticketTotalPages > 1" class="pagination">
          <button class="button button-outline" :disabled="ticketPage <= 0 || busy !== ''" @click="loadTickets(ticketPage - 1)">Anterior</button>
          <span>Pagina {{ ticketPage + 1 }} de {{ ticketTotalPages }}</span>
          <button class="button button-outline" :disabled="ticketPage + 1 >= ticketTotalPages || busy !== ''" @click="loadTickets(ticketPage + 1)">Siguiente</button>
        </div>
      </section>
    </main>

    <footer class="site-footer">
      <div class="footer-main">
        <section class="footer-brand-column">
          <div class="footer-logo">
            <img src="/ticketya-logo.png" alt="TicketYa" draggable="false" />
          </div>
          <p>Tu próximo show empieza acá. Descubrí eventos, elegí tu lugar y preparate para vivirlo.</p>
          <div class="footer-socials" aria-label="Redes sociales">
            <span>Instagram</span><span>TikTok</span><span>Facebook</span>
          </div>
        </section>
        <nav class="footer-column" aria-label="Explorar">
          <h2>Explorá</h2>
          <span>Conciertos</span>
          <span>Festivales</span>
          <span>Eventos destacados</span>
          <span>Próximos shows</span>
        </nav>
        <nav class="footer-column" aria-label="Ayuda">
          <h2>¿Necesitás ayuda?</h2>
          <span>Preguntas frecuentes</span>
          <span>Cómo comprar</span>
          <span>Mis entradas</span>
          <span>Contactanos</span>
        </nav>
        <nav class="footer-column" aria-label="Información legal">
          <h2>Información</h2>
          <span>Términos y condiciones</span>
          <span>Política de privacidad</span>
          <span>Política de devoluciones</span>
          <span>Accesibilidad</span>
        </nav>
        <section class="footer-newsletter">
          <h2>Que no se te escape ningún show</h2>
          <p>Novedades, fechas y experiencias para tu próxima salida.</p>
          <div class="newsletter-mock" aria-label="Suscripción no disponible en esta demo">
            <span>Tu email</span><span aria-hidden="true">→</span>
          </div>
          <small>Suscripción de ejemplo · Próximamente</small>
        </section>
      </div>
      <div class="footer-trust">
        <span><i class="trust-icon" aria-hidden="true">✓</i> Compra protegida</span>
        <span><i class="trust-icon" aria-hidden="true">▣</i> Entradas digitales</span>
        <span><i class="trust-icon" aria-hidden="true">◷</i> Atención al cliente</span>
        <span class="gateway-status"><i></i> API Gateway <code>{{ gatewayUrl }}</code></span>
      </div>
      <div class="footer-bottom">
        <span>© 2026 TicketYa. Todos los derechos reservados.</span>
        <span>Una experiencia hecha para encontrarnos en vivo.</span>
      </div>
    </footer>
    <div v-if="paymentSuccessVisible" class="payment-success-screen" role="status" aria-live="assertive">
      <div class="payment-success-content">
        <span class="payment-success-check" aria-hidden="true">✓</span>
        <h2>¡Pago aprobado!</h2>
        <p>Estamos preparando tus entradas</p>
      </div>
    </div>
    <div
      v-if="ticketQr || ticketQrLoading || ticketQrError"
      class="ticket-modal-backdrop"
      @click.self="closeTicketQr"
      @keydown.esc="closeTicketQr"
    >
      <section v-if="ticketQrLoading" class="ticket-modal-message" role="status">
        <p>Generando tu entrada...</p>
        <button class="button button-outline" @click="closeTicketQr">Cancelar</button>
      </section>
      <section v-else-if="ticketQrError" class="ticket-modal-message" role="alert">
        <p>{{ ticketQrError }}</p>
        <button class="button button-outline" @click="closeTicketQr">Cerrar</button>
      </section>
      <section v-else-if="ticketQr" class="ticket-modal-shell" role="dialog" aria-modal="true" aria-label="Entrada para imprimir">
        <div class="ticket-modal-toolbar">
          <p>Tu entrada digital está lista. Imprimila o guardala como PDF.</p>
          <div>
            <button class="button button-outline" @click="closeTicketQr">Cerrar</button>
            <button class="button button-dark" @click="printTicket">Imprimir / Guardar PDF</button>
          </div>
        </div>
        <article class="printable-ticket">
          <div class="printable-ticket-main">
            <div class="printable-ticket-brand">
              <img src="/ticketya-logo.png" alt="TicketYa" draggable="false" />
              <span>ENTRADA OFICIAL · {{ ticketQr.estado || 'CONFIRMADA' }}</span>
            </div>
            <p class="eyebrow">MÚSICA EN VIVO · TICKET DIGITAL</p>
            <h1>{{ ticketQr.evento?.nombre || 'Evento TicketYa' }}</h1>
            <p class="printable-ticket-date">{{ formatDate(ticketQr.evento?.comienzaEn) }}</p>
            <div class="printable-ticket-details">
              <div><small>LUGAR</small><strong>{{ ticketQrEvent?.lugar?.nombre || 'Ver detalles del evento' }}</strong></div>
              <div><small>CIUDAD</small><strong>{{ ticketQrEvent?.lugar?.ciudad || ticketQrEvent?.ciudad || 'Argentina' }}</strong></div>
              <div><small>SECTOR</small><strong>{{ ticketQr.sector?.nombre || 'General' }}</strong></div>
              <div><small>ASIENTO</small><strong>{{ ticketQr.fila && ticketQr.numero ? `Fila ${ticketQr.fila} · ${ticketQr.numero}` : 'Sin numerar' }}</strong></div>
            </div>
            <div class="printable-ticket-footer">
              <span>ENTRADA {{ ticketQr.idEntrada }}</span>
              <span>Presentá este QR al ingresar. Válido para un único acceso.</span>
            </div>
          </div>
          <aside class="printable-ticket-qr">
            <img :src="ticketQrImage" alt="Código QR único de acceso a la entrada" />
            <strong>ESCANEÁ PARA INGRESAR</strong>
            <small>Este código es personal y de un solo uso.</small>
          </aside>
          <p class="printable-ticket-terms">
            Términos: conservar la entrada y no compartir el QR. El acceso está sujeto a la capacidad y reglas del recinto.
            La reventa, duplicación o alteración de esta entrada puede invalidarla. TicketYa y el organizador no se responsabilizan
            por códigos difundidos o utilizados previamente. Entrada simulada para demostración académica; no habilita acceso real.
          </p>
        </article>
      </section>
    </div>
  </div>
</template>
