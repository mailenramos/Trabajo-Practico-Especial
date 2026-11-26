<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { apiRequest } from './api'

// Reportes de un evento para su organizador (MS3 por el Gateway):
//   GET /reportes/eventos/{id}/{ventas|devoluciones|recaudacion|ocupacion}
// Cada reporte trae totales y el detalle por sector.

const props = defineProps({ eventId: { type: String, required: true } })

const REPORTS = {
  ventas: {
    label: 'Ventas',
    totals: [['comprasConfirmadas', 'Compras confirmadas'], ['entradasVendidas', 'Entradas vendidas'], ['importeVendido', 'Importe vendido', 'money']],
    columns: [['entradasVendidas', 'Entradas'], ['importeVendido', 'Importe', 'money']],
  },
  devoluciones: {
    label: 'Devoluciones',
    totals: [['entradasVendidas', 'Entradas vendidas'], ['entradasDevueltas', 'Entradas devueltas'], ['entradasNetas', 'Entradas netas'], ['importeDevuelto', 'Importe devuelto', 'money']],
    columns: [['entradasVendidas', 'Vendidas'], ['devueltasPorDevolucion', 'Por devolución'], ['devueltasPorCancelacion', 'Por cancelación'], ['entradasNetas', 'Netas'], ['importeDevuelto', 'Devuelto', 'money']],
  },
  recaudacion: {
    label: 'Recaudación',
    totals: [['recaudacionBruta', 'Recaudación bruta', 'money'], ['reintegrosExitosos', 'Reintegros', 'money'], ['recaudacionNeta', 'Recaudación neta', 'money']],
    columns: [['recaudacionBruta', 'Bruta', 'money'], ['reintegrosExitosos', 'Reintegros', 'money'], ['recaudacionNeta', 'Neta', 'money']],
  },
  ocupacion: {
    label: 'Ocupación',
    totals: [['capacidad', 'Capacidad'], ['vendidos', 'Vendidos'], ['reservasTemporales', 'Reservas temporales'], ['porcentajeOcupacion', 'Ocupación', 'percent']],
    columns: [['capacidad', 'Capacidad'], ['vendidos', 'Vendidos'], ['reservasTemporales', 'Reservas'], ['porcentajeOcupacion', 'Ocupación', 'percent']],
  },
}

const selected = ref('ventas')
const report = ref(null)
const loading = ref(false)
const errorMessage = ref('')
const config = computed(() => REPORTS[selected.value])

function format(value, kind) {
  if (value === null || value === undefined) return '—'
  if (kind === 'money') return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS' }).format(value)
  const number = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 2 }).format(value)
  return kind === 'percent' ? `${number} %` : number
}

function formatDate(value) {
  return value ? new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—'
}

async function load() {
  const requested = selected.value
  loading.value = true
  errorMessage.value = ''
  report.value = null
  try {
    const result = await apiRequest(`/reportes/eventos/${encodeURIComponent(props.eventId)}/${requested}`)
    if (requested === selected.value) report.value = result
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    loading.value = false
  }
}

function select(name) {
  selected.value = name
  load()
}

watch(() => props.eventId, load)
onMounted(load)
</script>

<template>
  <div class="px-card">
    <div class="px-row">
      <p class="eyebrow">REPORTES</p>
      <button type="button" class="button button-outline" :disabled="loading" @click="load">↻ Actualizar</button>
    </div>
    <div class="px-tabs">
      <button v-for="(item, name) in REPORTS" :key="name" type="button" :class="{ active: selected === name }" @click="select(name)">{{ item.label }}</button>
    </div>

    <p v-if="loading" class="px-muted">Cargando reporte...</p>
    <div v-if="errorMessage" class="notice notice-error" role="alert"><span class="notice-icon">!</span><span>{{ errorMessage }}</span></div>

    <template v-if="report">
      <div class="px-stats">
        <div v-for="[key, label, kind] in config.totals" :key="key" class="px-stat"><small>{{ label }}</small><strong>{{ format(report.totales?.[key], kind) }}</strong></div>
      </div>
      <p v-if="report.hayOperacionesPendientes" class="px-muted px-warn">Hay operaciones pendientes: los números pueden cambiar cuando se completen.</p>
      <p v-if="selected === 'ocupacion' && report.inventarioInicializado === false" class="px-muted">Este evento todavía no tiene inventario en Reservas.</p>
      <div v-if="report.sectores?.length" class="px-table-wrap">
        <table class="px-table">
          <thead><tr><th>Sector</th><th v-for="[key, label] in config.columns" :key="key">{{ label }}</th></tr></thead>
          <tbody>
            <tr v-for="sector in report.sectores" :key="sector.sectorId">
              <td>{{ sector.sectorNombre }}</td>
              <td v-for="[key, , kind] in config.columns" :key="key">{{ format(sector[key], kind) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="px-muted">Todavía no hay movimientos por sector.</p>
      <p class="px-muted">Generado: {{ formatDate(report.generadoEn) }}</p>
    </template>
  </div>
</template>
