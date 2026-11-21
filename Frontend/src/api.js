import { endSession, session } from './session'

const gatewayUrl = (import.meta.env.VITE_GATEWAY_URL || 'http://localhost:8086').replace(/\/+$/, '')

/**
 * Pedido al API Gateway. Si hay sesión, envía el JWT en Authorization: el Gateway lo valida y
 * propaga la identidad (X-User-Id, X-User-Roles) a los microservicios; la app ya no la declara.
 * Un 401 con sesión iniciada significa que el token venció o no es válido: se cierra la sesión.
 */
export async function apiRequest(path, options = {}) {
  const headers = new Headers(options.headers || {})
  const token = session.value?.token
  if (token) headers.set('Authorization', `Bearer ${token}`)
  headers.set('Accept', 'application/json')
  if (options.body) headers.set('Content-Type', 'application/json')

  let response
  try {
    response = await fetch(`${gatewayUrl}${path}`, { ...options, headers })
  } catch {
    throw new Error(`No se pudo conectar con el API Gateway (${gatewayUrl}). Verifica que este encendido y que CORS permita esta direccion.`)
  }

  const text = await response.text()
  let payload = null
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch {
      payload = null
    }
  }

  if (!response.ok) {
    if (response.status === 401 && token) {
      endSession('Tu sesión venció o no es válida. Volvé a iniciar sesión.')
    }
    const detail = payload?.mensaje || payload?.message || payload?.error || `Error HTTP ${response.status}`
    const error = new Error(`${detail} (HTTP ${response.status})`)
    error.status = response.status
    error.code = payload?.error
    error.fields = payload?.campos || null
    throw error
  }
  return payload
}

export { gatewayUrl }
