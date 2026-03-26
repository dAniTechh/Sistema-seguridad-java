import { useState, useEffect } from 'react'
import './App.css'

function App() {
  const [user, setUser] = useState('')
  const [pass, setPass] = useState('')
  const [token, setToken] = useState(null)
  const [balance, setBalance] = useState('---')
  const [logs, setLogs] = useState([])
  
  // Estado para comprar
  const [price, setPrice] = useState(100)
  const [cant, setCant] = useState(1)

  const addLog = (msg) => setLogs(prev => [`> ${msg}`, ...prev])

  // 1. LOGIN
  const handleLogin = async (e) => {
    e.preventDefault()
    try {
      const res = await fetch(`/login?user=${user}&pass=${pass}`)
      const text = await res.text()
      
      if (text.includes("LOGIN_OK")) {
        const extractedToken = text.split(": ")[1].trim()
        setToken(extractedToken)
        addLog("Acceso concedido. Token recibido.")
        fetchBalance(extractedToken)
      } else {
        addLog("Error: " + text)
      }
    } catch (err) { addLog("Error de Red. ¿Está Docker encendido?") }
  }

  // 2. CONSULTAR SALDO
  const fetchBalance = async (t) => {
    const currentToken = t || token
    if (!currentToken) return
    const res = await fetch(`/balance?token=${currentToken}`)
    const text = await res.text()
    setBalance(text.replace("SALDO: ", ""))
  }

  // 3. COMPRAR
  const handleBuy = async () => {
    addLog(`Enviando orden de compra... (${cant}u @ $${price})`)
    const res = await fetch(`/buy?token=${token}&price=${price}&cant=${cant}`)
    const text = await res.text()
    addLog(text)
    setTimeout(() => fetchBalance(), 1000) // Actualizar saldo tras 1 seg
  }

  // Refrescar saldo automáticamente cada 5 segundos
  useEffect(() => {
    if (token) {
      const interval = setInterval(() => fetchBalance(), 5000)
      return () => clearInterval(interval)
    }
  }, [token])

  if (!token) {
    return (
      <div className="container login-screen">
        <h1>🔒 WALL STREET LOGIN</h1>
        <form onSubmit={handleLogin}>
          <input placeholder="Usuario (dani)" value={user} onChange={e => setUser(e.target.value)} />
          <input type="password" placeholder="Pass (1234)" value={pass} onChange={e => setPass(e.target.value)} />
          <button type="submit">ENTRAR AL MERCADO</button>
        </form>
        <div className="logs">
          {logs.map((l, i) => <div key={i}>{l}</div>)}
        </div>
      </div>
    )
  }

  return (
    <div className="container dashboard">
      <header>
        <h2>👤 TRADER: {user.toUpperCase()}</h2>
        <div className="balance-box">
          <span>SALDO DISPONIBLE</span>
          <h1>{balance}</h1>
        </div>
      </header>

      <main>
        <div className="panel trade-panel">
          <h3>🛒 NUEVA ORDEN</h3>
          <div className="inputs">
            <label>Precio ($)</label>
            <input type="number" value={price} onChange={e => setPrice(e.target.value)} />
            <label>Cantidad</label>
            <input type="number" value={cant} onChange={e => setCant(e.target.value)} />
          </div>
          <button onClick={handleBuy} className="buy-btn">COMPRAR AHORA</button>
        </div>

        <div className="panel log-panel">
          <h3>📟 REGISTRO DE OPERACIONES</h3>
          <div className="logs-scroll">
            {logs.map((l, i) => <div key={i} className="log-line">{l}</div>)}
          </div>
        </div>
      </main>
      
      <button className="logout" onClick={() => setToken(null)}>CERRAR SESIÓN</button>
    </div>
  )
}

export default App