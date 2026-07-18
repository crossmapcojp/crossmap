import fs from 'node:fs/promises'
import { pathToFileURL } from 'node:url'

const [modulePath, inputPath, concurrencyText = '4'] = process.argv.slice(2)
if (!modulePath || !inputPath) {
  throw new Error('usage: node geolonia-normalize.mjs <main-node-esm.mjs> <input.json> [concurrency]')
}

const { normalize } = await import(pathToFileURL(modulePath).href)
const requests = JSON.parse(await fs.readFile(inputPath, 'utf8'))
const concurrency = Math.max(1, Number.parseInt(concurrencyText, 10) || 1)
const results = new Array(requests.length)
let next = 0

async function worker() {
  while (true) {
    const index = next++
    if (index >= requests.length) return
    const request = requests[index]
    try {
      const result = await normalize(request.address, { level: 8 })
      results[index] = { churchId: request.churchId, status: 'success', ...result }
    } catch (error) {
      results[index] = {
        churchId: request.churchId,
        status: 'error',
        error: error instanceof Error ? `${error.name}: ${error.message}` : String(error),
      }
    }
  }
}

await Promise.all(Array.from({ length: Math.min(concurrency, requests.length) }, worker))
process.stdout.write(JSON.stringify(results))
