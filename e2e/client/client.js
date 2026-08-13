'use strict'

const mineflayer = require('mineflayer')

const host = process.env.E2E_HOST || 'velocity'
const port = Number.parseInt(process.env.E2E_PORT || '25577', 10)
const version = process.env.E2E_VERSION || '1.21.4'
const names = (process.env.E2E_NAMES || 'E2EClient')
  .split(',')
  .map(name => name.trim())
  .filter(Boolean)
const expectation = process.env.E2E_EXPECT || 'spawn'
const expectedMessage = (process.env.E2E_MESSAGE || '').toLowerCase()
const timeoutMillis = Number.parseInt(process.env.E2E_TIMEOUT_MILLIS || '210000', 10)
const holdMillis = Number.parseInt(process.env.E2E_HOLD_MILLIS || '1500', 10)

if (!Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error(`Invalid E2E_PORT: ${process.env.E2E_PORT}`)
}
if (names.length === 0 || names.some(name => name.length > 16)) {
  throw new Error('E2E_NAMES must contain one or more Minecraft usernames of at most 16 characters')
}
if (!['spawn', 'message'].includes(expectation)) {
  throw new Error(`Unsupported E2E_EXPECT: ${expectation}`)
}
if (expectation === 'message' && expectedMessage.length === 0) {
  throw new Error('E2E_MESSAGE is required when E2E_EXPECT=message')
}

function emit(event, fields = {}) {
  process.stdout.write(`${JSON.stringify({ event, ...fields })}\n`)
}

function delay(millis) {
  return new Promise(resolve => setTimeout(resolve, millis))
}

function printable(value) {
  if (typeof value === 'string') return value
  const converted = value && typeof value.toString === 'function' ? value.toString() : ''
  if (converted && converted !== '[object Object]') return converted
  try {
    return JSON.stringify(value)
  } catch (_) {
    return String(value)
  }
}

function connect(name) {
  let settled = false
  let observed = ''
  let bot

  const outcome = new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      finish(new Error(`${name} timed out waiting for ${expectation}; observed: ${observed}`))
    }, timeoutMillis)

    function finish(error, result) {
      if (settled) return
      settled = true
      clearTimeout(timer)
      if (error) {
        reject(error)
      } else {
        resolve(result)
      }
    }

    function observe(kind, text) {
      const message = printable(text)
      observed += `${kind}: ${message}\n`
      emit(kind, { name, message })
      if (expectation === 'message' && message.toLowerCase().includes(expectedMessage)) {
        finish(null, { name, outcome: 'message', message })
      }
    }

    bot = mineflayer.createBot({
      host,
      port,
      username: name,
      auth: 'offline',
      version,
      hideErrors: false,
      checkTimeoutInterval: 30000
    })

    bot.once('login', () => emit('login', { name }))
    bot.once('spawn', () => {
      emit('spawn', { name })
      if (expectation === 'spawn') {
        finish(null, { name, outcome: 'spawn' })
      }
    })
    bot.on('message', message => observe('message', message.toString()))
    bot.on('kicked', reason => observe('kicked', reason))
    bot.on('error', error => {
      observe('error', error.message || error)
      if (expectation === 'spawn') {
        finish(error)
      }
    })
    bot.on('end', reason => {
      observe('end', reason)
      if (!settled) {
        finish(new Error(`${name} disconnected before observing ${expectation}: ${reason}`))
      }
    })
  })

  return { bot: () => bot, outcome }
}

async function main() {
  emit('batch-start', { host, port, version, names, expectation })
  const clients = names.map(connect)
  try {
    const outcomes = await Promise.all(clients.map(client => client.outcome))
    emit('batch-passed', { outcomes })
    await delay(holdMillis)
  } finally {
    for (const client of clients) {
      const bot = client.bot()
      if (bot && bot._client && !bot._client.ended) {
        bot.quit('AutoStopper E2E complete')
      }
    }
    await delay(500)
  }
}

main().catch(error => {
  emit('batch-failed', { message: error.stack || error.message || String(error) })
  process.exitCode = 1
})
