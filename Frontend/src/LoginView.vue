<script setup>
import { ref } from 'vue'
import { apiRequest } from './api'
import { sessionNotice, startSession } from './session'

const mode = ref('login')
const busy = ref(false)
const error = ref('')
const fieldErrors = ref({})
const info = ref('')
const loginForm = ref({ correo: '', password: '' })
const registerForm = ref({ nombre: '', apellido: '', correo: '', password: '' })

// Usuarios de prueba que crea MS6 en desarrollo (USUARIOS_DATOS_PRUEBA=true).
const demoUsers = [
  { label: 'Cliente', correo: 'cliente@ticketya.test' },
  { label: 'Organizador', correo: 'organizador@ticketya.test' },
  { label: 'Admin', correo: 'admin@ticketya.test' },
  { label: 'Personal de acceso', correo: 'personal@ticketya.test' },
]

function switchMode(next) {
  mode.value = next
  error.value = ''
  fieldErrors.value = {}
  info.value = ''
}

function showError(err) {
  error.value = err instanceof Error ? err.message : 'Ocurrió un error inesperado.'
  fieldErrors.value = err?.fields || {}
}

async function login(correo, password) {
  const result = await apiRequest('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ correo, password }),
  })
  startSession(result.token)
}

async function submitLogin() {
  error.value = ''
  fieldErrors.value = {}
  busy.value = true
  try {
    await login(loginForm.value.correo, loginForm.value.password)
  } catch (err) {
    showError(err)
  } finally {
    busy.value = false
  }
}

async function submitRegister() {
  error.value = ''
  fieldErrors.value = {}
  busy.value = true
  try {
    await apiRequest('/auth/registro', {
      method: 'POST',
      body: JSON.stringify(registerForm.value),
    })
    // La cuenta queda creada como CLIENTE: se inicia sesión con los mismos datos.
    await login(registerForm.value.correo, registerForm.value.password)
  } catch (err) {
    showError(err)
  } finally {
    busy.value = false
  }
}

function useDemoUser(correo) {
  switchMode('login')
  loginForm.value = { correo, password: 'Prueba123' }
  info.value = 'Usuario de prueba de MS6 (solo desarrollo). Contraseña: Prueba123.'
}
</script>

<template>
  <section class="auth-page">
    <div class="auth-intro">
      <p class="eyebrow"><span class="live-dot"></span> VIVI LO QUE VIENE</p>
      <h1>Tu próxima<br /><span>gran noche</span> empieza acá.</h1>
      <p>Ingresá para buscar eventos, elegir tu lugar y tener tus entradas siempre a mano.</p>
    </div>

    <div class="auth-card">
      <div class="auth-tabs" role="tablist" aria-label="Acceso">
        <button role="tab" :aria-selected="mode === 'login'" :class="{ active: mode === 'login' }" @click="switchMode('login')">
          Iniciar sesión
        </button>
        <button role="tab" :aria-selected="mode === 'register'" :class="{ active: mode === 'register' }" @click="switchMode('register')">
          Crear cuenta
        </button>
      </div>

      <div v-if="sessionNotice && !error" class="notice notice-error" role="status">
        <span class="notice-icon">!</span><span>{{ sessionNotice }}</span>
      </div>
      <div v-if="error" class="notice notice-error" role="alert">
        <span class="notice-icon">!</span><span>{{ error }}</span>
      </div>
      <div v-if="info && !error" class="notice notice-success" role="status">
        <span class="notice-icon">i</span><span>{{ info }}</span>
      </div>

      <form v-if="mode === 'login'" class="auth-form" @submit.prevent="submitLogin">
        <label>
          <span>Correo electrónico</span>
          <input v-model.trim="loginForm.correo" type="email" autocomplete="username" required />
          <small v-if="fieldErrors.correo" class="field-error">{{ fieldErrors.correo }}</small>
        </label>
        <label>
          <span>Contraseña</span>
          <input v-model="loginForm.password" type="password" autocomplete="current-password" required />
          <small v-if="fieldErrors.password" class="field-error">{{ fieldErrors.password }}</small>
        </label>
        <button class="button button-dark auth-submit" :disabled="busy">
          {{ busy ? 'Ingresando...' : 'Ingresar' }} <span aria-hidden="true">→</span>
        </button>
      </form>

      <form v-else class="auth-form" @submit.prevent="submitRegister">
        <div class="auth-row">
          <label>
            <span>Nombre</span>
            <input v-model.trim="registerForm.nombre" autocomplete="given-name" required maxlength="100" />
            <small v-if="fieldErrors.nombre" class="field-error">{{ fieldErrors.nombre }}</small>
          </label>
          <label>
            <span>Apellido</span>
            <input v-model.trim="registerForm.apellido" autocomplete="family-name" required maxlength="100" />
            <small v-if="fieldErrors.apellido" class="field-error">{{ fieldErrors.apellido }}</small>
          </label>
        </div>
        <label>
          <span>Correo electrónico</span>
          <input v-model.trim="registerForm.correo" type="email" autocomplete="email" required />
          <small v-if="fieldErrors.correo" class="field-error">{{ fieldErrors.correo }}</small>
        </label>
        <label>
          <span>Contraseña</span>
          <input v-model="registerForm.password" type="password" autocomplete="new-password" required minlength="8" maxlength="72" />
          <small v-if="fieldErrors.password" class="field-error">{{ fieldErrors.password }}</small>
          <small v-else class="field-hint">Entre 8 y 72 caracteres, con al menos una letra y un número.</small>
        </label>
        <button class="button button-dark auth-submit" :disabled="busy">
          {{ busy ? 'Creando cuenta...' : 'Crear cuenta' }} <span aria-hidden="true">→</span>
        </button>
      </form>

      <div class="demo-users">
        <p class="eyebrow">USUARIOS DE PRUEBA</p>
        <div>
          <button v-for="user in demoUsers" :key="user.correo" type="button" class="demo-user" @click="useDemoUser(user.correo)">
            {{ user.label }}
          </button>
        </div>
      </div>
    </div>
  </section>
</template>
