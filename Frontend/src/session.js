import { computed, ref } from 'vue'

// Sesión del usuario: el JWT que emite MS6 (POST /auth/login) a través del API Gateway.
// Se guarda en localStorage para sobrevivir a una recarga de la página. Al vencer (una hora por
// defecto) o si el Gateway responde 401, la sesión se cierra y se vuelve al login.

const STORAGE_KEY = 'ticketya.sesion'

/** Decodifica el payload de un JWT (sin verificar la firma: eso lo hace el Gateway). */
export function decodeJwt(token) {
  try {
    const payload = token.split('.')[1]
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/')
    const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4)
    const bytes = Uint8Array.from(atob(padded), (char) => char.charCodeAt(0))
    return JSON.parse(new TextDecoder().decode(bytes))
  } catch {
    return null
  }
}

function readStored() {
  try {
    const stored = JSON.parse(window.localStorage.getItem(STORAGE_KEY) || 'null')
    return stored?.token ? stored : null
  } catch {
    return null
  }
}

function writeStored(value) {
  try {
    if (value) window.localStorage.setItem(STORAGE_KEY, JSON.stringify(value))
    else window.localStorage.removeItem(STORAGE_KEY)
  } catch {
    // Sin almacenamiento (modo privado o bloqueado): la sesión dura mientras la página esté abierta.
  }
}

function claimsOf(token) {
  const claims = token ? decodeJwt(token) : null
  if (!claims?.sub || !claims.exp) return null
  if (claims.exp * 1000 <= Date.now()) return null
  return claims
}

const stored = readStored()
const state = ref(stored && claimsOf(stored.token) ? stored : null)
if (stored && !state.value) writeStored(null)

/** Motivo del último cierre de sesión automático (por ejemplo, token vencido). */
export const sessionNotice = ref('')

export const session = computed(() => {
  const claims = state.value ? claimsOf(state.value.token) : null
  if (!claims) return null
  return {
    token: state.value.token,
    userId: claims.sub,
    email: claims.email,
    roles: Array.isArray(claims.roles) ? claims.roles : [],
    expiresAt: new Date(claims.exp * 1000),
    nombre: state.value.nombre || '',
  }
})

export const isLoggedIn = computed(() => Boolean(session.value))

export function hasRole(role) {
  return Boolean(session.value?.roles.includes(role))
}

export function startSession(token) {
  if (!claimsOf(token)) throw new Error('El token recibido no es válido o ya venció.')
  state.value = { token, nombre: '' }
  writeStored(state.value)
  sessionNotice.value = ''
  scheduleExpiry()
}

export function setProfileName(nombre) {
  if (!state.value) return
  state.value = { ...state.value, nombre }
  writeStored(state.value)
}

export function endSession(notice = '') {
  state.value = null
  writeStored(null)
  sessionNotice.value = notice
  window.clearTimeout(expiryTimer)
}

let expiryTimer = 0

/** Cierra la sesión cuando vence el token, aunque el usuario no haga ningún pedido. */
function scheduleExpiry() {
  window.clearTimeout(expiryTimer)
  const expiresAt = session.value?.expiresAt?.getTime()
  if (!expiresAt) return
  // setTimeout admite hasta ~24 días; el token dura bastante menos.
  expiryTimer = window.setTimeout(
    () => endSession('Tu sesión venció. Volvé a iniciar sesión.'),
    Math.max(0, expiresAt - Date.now()),
  )
}

scheduleExpiry()
