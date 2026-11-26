<script setup>
import { computed, onMounted, ref } from 'vue'
import { apiRequest } from './api'

// Lugares y sectores (MS1 por el Gateway, solo ADMIN para crear):
//   GET /lugares · POST /lugares · POST /lugares/{id}/sectores
// Un sector NUMERADO se define por filas y asientos por fila; uno NO_NUMERADO, por capacidad.

const venues = ref([])
const loading = ref(false)
const busy = ref('')
const creating = ref(false)
const venueForm = ref(emptyVenue())
const sectorFor = ref('')
const sectorForm = ref(emptySector())
const errorMessage = ref('')
const successMessage = ref('')

function emptyVenue() {
  return { nombre: '', direccion: '', ciudad: '', latitud: '', longitud: '' }
}

function emptySector() {
  return { nombre: '', tipo: 'NO_NUMERADO', filas: '', asientosPorFila: '', capacidad: '' }
}

const inRange = (value, limit) => value !== '' && Math.abs(Number(value)) <= limit
const venueValid = computed(() => {
  const f = venueForm.value
  return Boolean(f.nombre.trim() && f.direccion.trim() && f.ciudad.trim() && inRange(f.latitud, 90) && inRange(f.longitud, 180))
})
const sectorValid = computed(() => {
  const f = sectorForm.value
  const positive = (value) => Number.isInteger(Number(value)) && Number(value) >= 1
  return Boolean(f.nombre.trim()) && (f.tipo === 'NUMERADO' ? positive(f.filas) && positive(f.asientosPorFila) : positive(f.capacidad))
})

function sectorSummary(sector) {
  return sector.tipo === 'NUMERADO' ? `${sector.filas} filas × ${sector.asientosPorFila}` : `capacidad ${sector.capacidad}`
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await apiRequest('/lugares')
    venues.value = Array.isArray(result) ? result : []
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    loading.value = false
  }
}

async function submit(key, path, body, success, done) {
  busy.value = key
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await apiRequest(path, { method: 'POST', body: JSON.stringify(body) })
    done()
    await load()
    successMessage.value = success
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    busy.value = ''
  }
}

function createVenue() {
  if (!venueValid.value) return
  const f = venueForm.value
  submit('venue', '/lugares', {
    nombre: f.nombre.trim(), direccion: f.direccion.trim(), ciudad: f.ciudad.trim(),
    latitud: Number(f.latitud), longitud: Number(f.longitud),
  }, `Lugar ${f.nombre.trim()} creado. Agregale al menos un sector para poder usarlo en un evento.`,
  () => { creating.value = false; venueForm.value = emptyVenue() })
}

function createSector(venue) {
  if (!sectorValid.value) return
  const f = sectorForm.value
  const numbered = f.tipo === 'NUMERADO'
  submit('sector', `/lugares/${encodeURIComponent(venue.id)}/sectores`, {
    nombre: f.nombre.trim(), tipo: f.tipo,
    filas: numbered ? Number(f.filas) : null,
    asientosPorFila: numbered ? Number(f.asientosPorFila) : null,
    capacidad: numbered ? null : Number(f.capacidad),
  }, `Sector ${f.nombre.trim()} agregado a ${venue.nombre}.`, () => { sectorFor.value = '' })
}

function startSector(venue) {
  sectorFor.value = venue.id
  sectorForm.value = emptySector()
}

onMounted(load)
</script>

<template>
  <div>
    <div v-if="errorMessage" class="notice notice-error" role="alert"><span class="notice-icon">!</span><span>{{ errorMessage }}</span></div>
    <div v-if="successMessage" class="notice notice-success" role="status"><span class="notice-icon">✓</span><span>{{ successMessage }}</span></div>

    <div class="px-actions" style="margin-top: 18px">
      <button v-if="!creating" class="button button-dark" @click="creating = true">+ Nuevo lugar</button>
    </div>

    <form v-if="creating" class="px-card" @submit.prevent="createVenue">
      <p class="eyebrow">NUEVO LUGAR</p>
      <div class="px-grid">
        <label class="px-field"><span class="field-caption">NOMBRE</span><input v-model="venueForm.nombre" /></label>
        <label class="px-field"><span class="field-caption">CIUDAD</span><input v-model="venueForm.ciudad" /></label>
        <label class="px-field px-wide"><span class="field-caption">DIRECCIÓN</span><input v-model="venueForm.direccion" /></label>
        <label class="px-field"><span class="field-caption">LATITUD (entre -90 y 90)</span><input v-model="venueForm.latitud" type="number" step="any" placeholder="-37.32" /></label>
        <label class="px-field"><span class="field-caption">LONGITUD (entre -180 y 180)</span><input v-model="venueForm.longitud" type="number" step="any" placeholder="-59.13" /></label>
      </div>
      <div class="px-actions">
        <button class="button button-green" :disabled="!venueValid || busy !== ''">{{ busy === 'venue' ? 'Creando...' : 'Crear lugar' }}</button>
        <button type="button" class="button button-outline" @click="creating = false">Cancelar</button>
      </div>
    </form>

    <p v-if="!venues.length && !loading" class="px-card px-muted">Todavía no hay lugares.</p>

    <article v-for="venue in venues" :key="venue.id" class="px-card">
      <div class="px-row">
        <div>
          <h3>{{ venue.nombre }}</h3>
          <small>{{ venue.direccion }} · {{ venue.ciudad }}</small>
          <div class="px-chips">
            <span v-for="sector in venue.sectores" :key="sector.id" class="px-chip">{{ sector.nombre }} · {{ sectorSummary(sector) }}</span>
            <span v-if="!venue.sectores?.length" class="px-muted">Sin sectores</span>
          </div>
        </div>
        <button v-if="sectorFor !== venue.id" class="button button-outline" @click="startSector(venue)">Agregar sector</button>
      </div>

      <form v-if="sectorFor === venue.id" @submit.prevent="createSector(venue)">
        <div class="px-grid">
          <label class="px-field"><span class="field-caption">NOMBRE DEL SECTOR</span><input v-model="sectorForm.nombre" /></label>
          <label class="px-field"><span class="field-caption">TIPO</span>
            <select v-model="sectorForm.tipo"><option value="NO_NUMERADO">Sin numerar</option><option value="NUMERADO">Numerado</option></select>
          </label>
          <template v-if="sectorForm.tipo === 'NUMERADO'">
            <label class="px-field"><span class="field-caption">FILAS</span><input v-model="sectorForm.filas" type="number" min="1" /></label>
            <label class="px-field"><span class="field-caption">ASIENTOS POR FILA</span><input v-model="sectorForm.asientosPorFila" type="number" min="1" /></label>
          </template>
          <label v-else class="px-field"><span class="field-caption">CAPACIDAD</span><input v-model="sectorForm.capacidad" type="number" min="1" /></label>
        </div>
        <div class="px-actions" style="margin-top: 14px">
          <button class="button button-green" :disabled="!sectorValid || busy !== ''">{{ busy === 'sector' ? 'Agregando...' : 'Agregar sector' }}</button>
          <button type="button" class="button button-outline" @click="sectorFor = ''">Cancelar</button>
        </div>
      </form>
    </article>
  </div>
</template>
