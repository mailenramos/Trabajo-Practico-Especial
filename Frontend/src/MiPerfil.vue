<script setup>
import { computed, onMounted, ref } from 'vue'
import { apiRequest } from './api'
import { setProfileName } from './session'

// Mi perfil (MS6 por el Gateway): GET /usuarios/me · PUT /usuarios/me { nombre, apellido }
// El correo y los roles no se editan desde acá (los roles los cambia un administrador).

const ROLE_LABELS = { CLIENTE: 'Cliente', ORGANIZADOR: 'Organizador', ADMIN: 'Admin', PERSONAL_ACCESO: 'Personal de acceso' }

const profile = ref(null)
const form = ref({ nombre: '', apellido: '' })
const loading = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const successMessage = ref('')

const valid = computed(() => Boolean(form.value.nombre.trim() && form.value.apellido.trim()))
const dirty = computed(() => Boolean(profile.value)
  && (form.value.nombre.trim() !== profile.value.nombre || form.value.apellido.trim() !== profile.value.apellido))

function apply(result) {
  profile.value = result
  form.value = { nombre: result.nombre || '', apellido: result.apellido || '' }
}

function formatDate(value) {
  return value ? new Intl.DateTimeFormat('es-AR', { dateStyle: 'long' }).format(new Date(value)) : '—'
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    apply(await apiRequest('/usuarios/me'))
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!valid.value || !dirty.value) return
  saving.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const result = await apiRequest('/usuarios/me', {
      method: 'PUT', body: JSON.stringify({ nombre: form.value.nombre.trim(), apellido: form.value.apellido.trim() }),
    })
    apply(result)
    setProfileName(`${result.nombre} ${result.apellido}`.trim())
    successMessage.value = 'Perfil actualizado.'
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="tickets-page">
    <div class="tickets-heading">
      <div>
        <p class="eyebrow">TU CUENTA</p>
        <h1>Mi perfil<span>.</span></h1>
        <p>Revisá y actualizá tus datos.</p>
      </div>
    </div>

    <div v-if="errorMessage" class="notice notice-error" role="alert"><span class="notice-icon">!</span><span>{{ errorMessage }}</span></div>
    <div v-if="successMessage" class="notice notice-success" role="status"><span class="notice-icon">✓</span><span>{{ successMessage }}</span></div>
    <p v-if="loading" class="px-card px-muted">Cargando tu perfil...</p>

    <form v-if="profile" class="px-card" @submit.prevent="save">
      <div class="px-grid">
        <label class="px-field"><span class="field-caption">NOMBRE</span><input v-model="form.nombre" maxlength="100" autocomplete="given-name" /></label>
        <label class="px-field"><span class="field-caption">APELLIDO</span><input v-model="form.apellido" maxlength="100" autocomplete="family-name" /></label>
        <div class="px-field"><span class="field-caption">CORREO</span><strong>{{ profile.correo }}</strong></div>
        <div class="px-field"><span class="field-caption">ROLES</span><div class="px-chips"><span v-for="role in profile.roles" :key="role" class="px-chip">{{ ROLE_LABELS[role] || role }}</span></div></div>
      </div>
      <p class="px-muted">Cuenta creada el {{ formatDate(profile.creadoEl) }}. El correo no se puede cambiar y los roles los administra un administrador.</p>
      <div class="px-actions">
        <button class="button button-green" :disabled="!valid || !dirty || saving">{{ saving ? 'Guardando...' : 'Guardar cambios' }}</button>
      </div>
    </form>
  </section>
</template>
