<script setup>
import { computed, onMounted, ref } from 'vue'
import { apiRequest } from './api'

// "Para vos" (rol CLIENTE). Usa MS8 por el Gateway:
//   GET|PUT|DELETE /preferencias/me · GET /recomendaciones/me
// Las preferencias son tres listas (tipos de evento, artistas y ciudades) y MS8 las usa para puntuar
// los eventos publicados. Los valores de ciudad y artista deben coincidir con los del catálogo.

const emit = defineEmits(['open-event'])

const FIELDS = [
  { key: 'tiposEvento', label: 'Tipos de evento', placeholder: 'Ej.: MUSICA', suggestions: 'tipos' },
  { key: 'artistas', label: 'Artistas o participantes', placeholder: 'Ej.: Banda Alfa', suggestions: 'artistas' },
  { key: 'ciudades', label: 'Ciudades', placeholder: 'Ej.: Córdoba', suggestions: 'ciudades' },
]

const prefs = ref({ tiposEvento: [], artistas: [], ciudades: [] })
const saved = ref(JSON.stringify(prefs.value))
const drafts = ref({ tiposEvento: '', artistas: '', ciudades: '' })
const suggestions = ref({ tipos: [], artistas: [], ciudades: [] })
const recommendations = ref([])
const updatedAt = ref('')
const loading = ref(false)
const busy = ref('')
const confirmingDelete = ref(false)
const errorMessage = ref('')
const successMessage = ref('')

const dirty = computed(() => JSON.stringify(prefs.value) !== saved.value)
const hasPrefs = computed(() => Object.values(prefs.value).some((list) => list.length))

const normalize = (value) => String(value || '').trim().toLowerCase()

// MS8 devuelve solo el puntaje. Los motivos por preferencias se deducen con sus mismas reglas
// (artista +5, tipo +3, ciudad +2) sobre las preferencias guardadas; el resto del puntaje son las
// señales del historial de compras (tipo ya comprado y eventos relacionados), que no se detallan.
const explained = computed(() => {
  const stored = JSON.parse(saved.value)
  const asSet = (list) => new Set(list.map(normalize))
  const artists = asSet(stored.artistas)
  const types = asSet(stored.tiposEvento)
  const cities = asSet(stored.ciudades)
  return recommendations.value.map((rec) => {
    const reasons = []
    const artist = (rec.participantes || []).find((name) => artists.has(normalize(name)))
    if (artist) reasons.push({ text: `Toca o participa ${artist}`, points: 5 })
    if (types.has(normalize(rec.tipo))) reasons.push({ text: 'Tu tipo de evento', points: 3 })
    if (cities.has(normalize(rec.ciudad))) reasons.push({ text: 'En tu ciudad', points: 2 })
    const history = rec.puntuacion - reasons.reduce((sum, reason) => sum + reason.points, 0)
    if (history > 0) reasons.push({ text: 'Por tus compras', points: history, history: true })
    return { ...rec, reasons }
  })
})

function formatDate(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function reportError(error, fallback) {
  errorMessage.value = error instanceof Error ? error.message : fallback
}

function applyPrefs(response) {
  prefs.value = {
    tiposEvento: [...(response?.tiposEvento || [])],
    artistas: [...(response?.artistas || [])],
    ciudades: [...(response?.ciudades || [])],
  }
  saved.value = JSON.stringify(prefs.value)
  updatedAt.value = response?.actualizadoEn || ''
}

function addTag(field) {
  let value = drafts.value[field.key].trim()
  if (!value) return
  if (field.key === 'tiposEvento') value = value.toUpperCase()
  const list = prefs.value[field.key]
  if (!list.some((item) => item.toLowerCase() === value.toLowerCase())) list.push(value)
  drafts.value[field.key] = ''
}

function removeTag(field, value) {
  prefs.value[field.key] = prefs.value[field.key].filter((item) => item !== value)
}

async function loadRecommendations() {
  const result = await apiRequest('/recomendaciones/me')
  recommendations.value = Array.isArray(result) ? result : []
}

async function loadSuggestions() {
  try {
    const result = await apiRequest('/eventos?size=100&orden=fecha&direccion=asc')
    const events = result?.contenido || []
    const unique = (values) => [...new Set(values.filter(Boolean))].sort((a, b) => a.localeCompare(b, 'es'))
    suggestions.value = {
      tipos: unique(events.map((event) => event.tipo)),
      ciudades: unique(events.map((event) => event.ciudad)),
      artistas: unique(events.flatMap((event) => event.participantes || [])),
    }
  } catch {
    // Las sugerencias son opcionales.
  }
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    applyPrefs(await apiRequest('/preferencias/me'))
    await loadRecommendations()
  } catch (error) {
    reportError(error, 'No se pudieron cargar tus preferencias.')
  } finally {
    loading.value = false
  }
}

async function save() {
  busy.value = 'save'
  errorMessage.value = ''
  successMessage.value = ''
  try {
    applyPrefs(await apiRequest('/preferencias/me', { method: 'PUT', body: JSON.stringify(prefs.value) }))
    await loadRecommendations()
    successMessage.value = 'Preferencias guardadas. Actualizamos tus recomendaciones.'
  } catch (error) {
    reportError(error, 'No se pudieron guardar las preferencias.')
  } finally {
    busy.value = ''
  }
}

async function remove() {
  busy.value = 'delete'
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await apiRequest('/preferencias/me', { method: 'DELETE' })
    applyPrefs(null)
    confirmingDelete.value = false
    await loadRecommendations()
    successMessage.value = 'Borramos tus preferencias.'
  } catch (error) {
    reportError(error, 'No se pudieron borrar las preferencias.')
  } finally {
    busy.value = ''
  }
}

onMounted(() => {
  load()
  loadSuggestions()
})
</script>

<template>
  <section class="tickets-page foryou-page">
    <div class="tickets-heading">
      <div>
        <p class="eyebrow">A TU MEDIDA</p>
        <h1>Para vos<span>.</span></h1>
        <p>Contanos qué te gusta y te recomendamos eventos.</p>
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

    <div class="fy-panel">
      <p class="eyebrow">MIS PREFERENCIAS</p>
      <div v-for="field in FIELDS" :key="field.key" class="fy-field">
        <span class="field-caption">{{ field.label.toUpperCase() }}</span>
        <div class="fy-tags">
          <span v-for="tag in prefs[field.key]" :key="tag" class="fy-tag">
            {{ tag }}<button type="button" :aria-label="`Quitar ${tag}`" @click="removeTag(field, tag)">×</button>
          </span>
          <input v-model="drafts[field.key]" :list="`fy-${field.suggestions}`" :placeholder="field.placeholder"
            :aria-label="`Agregar a ${field.label}`" @keydown.enter.prevent="addTag(field)" @blur="addTag(field)" />
        </div>
      </div>
      <datalist id="fy-tipos"><option v-for="value in suggestions.tipos" :key="value" :value="value" /></datalist>
      <datalist id="fy-artistas"><option v-for="value in suggestions.artistas" :key="value" :value="value" /></datalist>
      <datalist id="fy-ciudades"><option v-for="value in suggestions.ciudades" :key="value" :value="value" /></datalist>

      <small class="fy-hint">Escribí un valor y presioná Enter. Las ciudades y los artistas tienen que escribirse como figuran en los eventos (con tildes).<template v-if="updatedAt"> Última actualización: {{ formatDate(updatedAt) }}.</template></small>

      <div class="fy-actions">
        <button class="button button-green" :disabled="!dirty || busy !== ''" @click="save">{{ busy === 'save' ? 'Guardando...' : 'Guardar preferencias' }}</button>
        <template v-if="hasPrefs || updatedAt">
          <button v-if="!confirmingDelete" class="button button-outline" :disabled="busy !== ''" @click="confirmingDelete = true">Borrar preferencias</button>
          <template v-else>
            <button class="button button-dark" :disabled="busy !== ''" @click="remove">{{ busy === 'delete' ? 'Borrando...' : 'Sí, borrar todo' }}</button>
            <button class="button button-outline" :disabled="busy !== ''" @click="confirmingDelete = false">Cancelar</button>
          </template>
        </template>
      </div>
    </div>

    <div class="fy-panel">
      <p class="eyebrow">RECOMENDADOS PARA VOS</p>
      <div v-if="!recommendations.length && !loading" class="empty-state">
        <span class="empty-illustration">✦</span>
        <h3>Todavía no tenemos recomendaciones</h3>
        <p>Cuando haya eventos publicados que coincidan con tus preferencias, aparecen acá.</p>
      </div>
      <p v-if="recommendations.length" class="fy-hint">Ordenados por afinidad: suman tus preferencias (artista 5, tipo 3, ciudad 2) y tu historial de compras. Por eso puede aparecer un tipo de evento que no elegiste.</p>
      <ul v-if="recommendations.length" class="fy-recs">
        <li v-for="rec in explained" :key="rec.eventoId">
          <div>
            <strong>{{ rec.nombre }}</strong>
            <small>{{ rec.tipo }} · {{ rec.ciudad }} · {{ formatDate(rec.fechaHora) }}</small>
            <small v-if="rec.participantes?.length">{{ rec.participantes.join(' · ') }}</small>
            <div class="fy-reasons">
              <span v-for="reason in rec.reasons" :key="reason.text" class="fy-reason" :class="{ 'fy-reason-history': reason.history }">
                {{ reason.text }} <b>+{{ reason.points }}</b>
              </span>
            </div>
          </div>
          <div class="fy-rec-side">
            <span class="fy-score" :title="'Puntaje de afinidad con tus preferencias'">Afinidad {{ rec.puntuacion }}</span>
            <button class="button button-outline" @click="emit('open-event', rec.eventoId)">Ver evento</button>
          </div>
        </li>
      </ul>
    </div>
  </section>
</template>

<style scoped>
.fy-panel { display: flex; flex-direction: column; gap: 16px; margin-top: 22px; padding: 22px 24px; border: 1px solid var(--line); border-radius: 14px; background: var(--paper); box-shadow: 0 7px 22px rgb(22 39 29 / 4%); }
.fy-panel .eyebrow { margin: 0; }
.fy-field { display: flex; flex-direction: column; gap: 6px; }
.fy-tags { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 8px 10px; border: 1px solid #dfe5dc; border-radius: 10px; background: white; }
.fy-tags input { flex: 1; min-width: 150px; padding: 6px 4px; border: 0; outline: 0; background: transparent; font: inherit; font-size: 13px; }
.fy-tag { display: inline-flex; align-items: center; gap: 6px; padding: 5px 6px 5px 11px; border-radius: 16px; background: #eaf5d3; color: #2f5233; font-size: 12px; font-weight: 600; }
.fy-tag button { display: grid; place-items: center; width: 18px; height: 18px; padding: 0; border: 0; border-radius: 50%; background: rgb(35 69 50 / 12%); color: inherit; font-size: 13px; line-height: 1; cursor: pointer; }
.fy-hint { color: var(--muted); font-size: 12px; line-height: 1.6; }
.fy-actions { display: flex; flex-wrap: wrap; gap: 10px; }
.fy-recs { display: flex; flex-direction: column; margin: 0; padding: 0; list-style: none; }
.fy-recs li { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 14px 0; border-top: 1px solid var(--line); }
.fy-recs li:first-child { border-top: 0; }
.fy-recs strong { display: block; font-size: 17px; }
.fy-recs small { display: block; color: var(--muted); font-size: 12px; line-height: 1.6; }
.fy-rec-side { display: flex; align-items: center; gap: 14px; flex: none; }
.fy-reasons { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
.fy-reason { padding: 3px 9px; border-radius: 12px; background: #eaf5d3; color: #2f5233; font-size: 11px; font-weight: 600; }
.fy-reason-history { background: #eef0f6; color: #47507a; }
.fy-score { padding: 4px 10px; border-radius: 12px; background: #f3f5f0; color: #4b5a52; font-size: 11px; font-weight: 700; }
@media (max-width: 640px) {
  .fy-recs li, .fy-rec-side { flex-direction: column; align-items: flex-start; }
}
</style>
