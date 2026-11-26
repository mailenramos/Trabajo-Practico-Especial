<script setup>
import { onMounted, ref } from 'vue'
import { apiRequest } from './api'
import { session } from './session'

// Administración de usuarios (MS6 por el Gateway):
//   GET /usuarios?rol=&correo=&page=&size= · PUT /usuarios/{id}/roles { roles } · PUT /usuarios/{id}/estado { activo }
// Los cambios de rol rigen desde el próximo inicio de sesión. MS6 impide quedarse sin administradores.

const ROLE_LABELS = { CLIENTE: 'Cliente', ORGANIZADOR: 'Organizador', ADMIN: 'Admin', PERSONAL_ACCESO: 'Personal de acceso' }

const users = ref([])
const filters = ref({ rol: '', correo: '' })
const page = ref(0)
const totalPages = ref(0)
const total = ref(0)
const loading = ref(false)
const acting = ref('')
const editing = ref('')
const draftRoles = ref([])
const deactivating = ref('')
const errorMessage = ref('')
const successMessage = ref('')

async function load(nextPage = 0) {
  loading.value = true
  errorMessage.value = ''
  try {
    const query = new URLSearchParams({ page: String(nextPage), size: '20' })
    if (filters.value.rol) query.set('rol', filters.value.rol)
    if (filters.value.correo.trim()) query.set('correo', filters.value.correo.trim())
    const result = await apiRequest(`/usuarios?${query}`)
    users.value = result?.content || []
    page.value = result?.page ?? nextPage
    totalPages.value = result?.totalPages ?? 0
    total.value = result?.totalElements ?? users.value.length
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    loading.value = false
  }
}

function startEdit(user) {
  editing.value = user.id
  draftRoles.value = [...user.roles]
  deactivating.value = ''
}

async function update(user, path, body, success) {
  acting.value = user.id
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await apiRequest(`/usuarios/${encodeURIComponent(user.id)}/${path}`, { method: 'PUT', body: JSON.stringify(body) })
    editing.value = ''
    deactivating.value = ''
    await load(page.value)
    successMessage.value = success
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    acting.value = ''
  }
}

const saveRoles = (user) => update(user, 'roles', { roles: draftRoles.value },
  `Roles de ${user.correo} actualizados. Rigen desde su próximo inicio de sesión.`)
const setActive = (user, activo) => update(user, 'estado', { activo },
  `${user.correo} quedó ${activo ? 'activo' : 'desactivado'}.`)

onMounted(() => load())
</script>

<template>
  <div>
    <form class="px-card" @submit.prevent="load(0)">
      <div class="px-grid">
        <label class="px-field"><span class="field-caption">CORREO (coincidencia parcial)</span><input v-model="filters.correo" placeholder="cliente@" /></label>
        <label class="px-field"><span class="field-caption">ROL</span>
          <select v-model="filters.rol"><option value="">Todos</option><option v-for="(label, role) in ROLE_LABELS" :key="role" :value="role">{{ label }}</option></select>
        </label>
      </div>
      <div class="px-actions"><button class="button button-dark" :disabled="loading">{{ loading ? 'Buscando...' : 'Buscar' }}</button><p class="px-muted">{{ total }} usuario{{ total === 1 ? '' : 's' }}</p></div>
    </form>

    <div v-if="errorMessage" class="notice notice-error" role="alert"><span class="notice-icon">!</span><span>{{ errorMessage }}</span></div>
    <div v-if="successMessage" class="notice notice-success" role="status"><span class="notice-icon">✓</span><span>{{ successMessage }}</span></div>

    <article v-for="user in users" :key="user.id" class="px-card">
      <div class="px-row">
        <div>
          <span class="px-badge" :class="user.activo ? '' : 'px-off'">{{ user.activo ? 'Activo' : 'Desactivado' }}</span>
          <h3>{{ user.nombre }} {{ user.apellido }}</h3>
          <small>{{ user.correo }}</small>
          <div class="px-chips"><span v-for="role in user.roles" :key="role" class="px-chip">{{ ROLE_LABELS[role] || role }}</span></div>
        </div>
        <div class="px-actions">
          <button class="button button-outline" :disabled="Boolean(acting)" @click="startEdit(user)">Cambiar roles</button>
          <button v-if="!user.activo" class="button button-green" :disabled="Boolean(acting)" @click="setActive(user, true)">Activar</button>
          <button v-else-if="user.id !== session?.userId" class="button button-outline px-bad" :disabled="Boolean(acting)" @click="deactivating = user.id; editing = ''">Desactivar</button>
        </div>
      </div>

      <template v-if="editing === user.id">
        <div class="px-checks">
          <label v-for="(label, role) in ROLE_LABELS" :key="role"><input v-model="draftRoles" type="checkbox" :value="role" /> {{ label }}</label>
        </div>
        <div class="px-actions">
          <button class="button button-green" :disabled="!draftRoles.length || Boolean(acting)" @click="saveRoles(user)">{{ acting === user.id ? 'Guardando...' : 'Guardar roles' }}</button>
          <button class="button button-outline" :disabled="Boolean(acting)" @click="editing = ''">Cancelar</button>
          <p v-if="!draftRoles.length" class="px-muted">Elegí al menos un rol.</p>
        </div>
      </template>

      <div v-if="deactivating === user.id" class="px-danger">
        <p>{{ user.correo }} no va a poder iniciar sesión hasta que lo vuelvas a activar.</p>
        <div class="px-actions">
          <button class="button button-dark" :disabled="Boolean(acting)" @click="setActive(user, false)">Sí, desactivar</button>
          <button class="button button-outline" :disabled="Boolean(acting)" @click="deactivating = ''">Volver</button>
        </div>
      </div>
    </article>

    <div v-if="totalPages > 1" class="pagination">
      <button class="button button-outline" :disabled="page <= 0 || loading" @click="load(page - 1)">Anterior</button>
      <span>Página {{ page + 1 }} de {{ totalPages }}</span>
      <button class="button button-outline" :disabled="page + 1 >= totalPages || loading" @click="load(page + 1)">Siguiente</button>
    </div>
  </div>
</template>
