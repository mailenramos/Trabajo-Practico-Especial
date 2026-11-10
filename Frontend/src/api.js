const gatewayUrl = (import.meta.env.VITE_GATEWAY_URL || 'http://localhost:8086').replace(/\/+$/, '')

export async function apiRequest(path, userId, options = {}) {
  const headers = new Headers(options.headers || {})
  headers.set('X-User-Id', userId)
  headers.set('X-User-Role', 'CLIENTE')
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
    const detail = payload?.mensaje || payload?.message || payload?.error || `Error HTTP ${response.status}`
    const error = new Error(`${detail} (HTTP ${response.status})`)
    error.status = response.status
    throw error
  }
  return payload
}

export { gatewayUrl }
