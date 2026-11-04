// Convertit la sortie de `cmd package dump-profiles` (profil réel de l'appli sur un
// appareil) en règles de profil de référence (`baseline-prof.txt`).
// Usage : node scripts/profil_reference.mjs PROFIL.txt SORTIE.txt
// Méthodes chaudes (H), de démarrage (S), d'après démarrage (P), puis classes (L).
import { readFileSync, writeFileSync } from 'node:fs'

const [, , input, output] = process.argv
if (!input || !output) {
  console.error('usage : node scripts/profil_reference.mjs PROFIL.txt SORTIE.txt')
  process.exit(2)
}
const primitive = { void: 'V', boolean: 'Z', byte: 'B', char: 'C', short: 'S', int: 'I', long: 'J', float: 'F', double: 'D' }
const descriptor = (type) => {
  let t = type.trim()
  let array = ''
  while (t.endsWith('[]')) {
    array += '['
    t = t.slice(0, -2)
  }
  return array + (primitive[t] || `L${t.replace(/\./g, '/')};`)
}
const methods = new Map()
const classes = new Set()
let section = null
let unreadable = 0
for (const raw of readFileSync(input, 'utf8').split('\n')) {
  if (/^\thot methods:/.test(raw)) section = 'H'
  else if (/^\tstartup methods:/.test(raw)) section = 'S'
  else if (/^\tpost startup methods:/.test(raw)) section = 'P'
  else if (/^\tclasses:/.test(raw)) section = 'C'
  if (!raw.startsWith('\t\t') || !section) continue
  let line = raw.trim().replace(/,\s*$/, '')
  if (section === 'C') {
    if (/^[\w$.]+$/.test(line)) classes.add(`L${line.replace(/\./g, '/')};`)
    continue
  }
  // « [] » final : caches en ligne du profil, sans intérêt ici.
  line = line.replace(/\[[^\]]*\]\s*$/, '')
  const match = line.match(/^(\S+) (\S+)\((.*)\)$/)
  const dot = match ? match[2].lastIndexOf('.') : -1
  if (!match || dot < 0) {
    unreadable++
    continue
  }
  const [, returns, full, params] = match
  const owner = full.slice(0, dot).replace(/\./g, '/')
  const args = params ? params.split(',').map(descriptor).join('') : ''
  const key = `L${owner};->${full.slice(dot + 1)}(${args})${descriptor(returns)}`
  const flags = methods.get(key) ?? new Set()
  flags.add(section)
  methods.set(key, flags)
}
const header = [
  "# Profil de référence (Baseline Profile) tiré de l'usage réel sur l'appareil de",
  '# référence (scripts/profil_reference.sh). Méthodes chaudes (H), de démarrage (S),',
  "# d'après démarrage (P), classes (L).",
]
const rules = [...classes].sort()
for (const [key, flags] of [...methods].sort(([a], [b]) => (a < b ? -1 : 1))) {
  rules.push(['H', 'S', 'P'].filter((f) => flags.has(f)).join('') + key)
}
writeFileSync(output, `${[...header, ...rules].join('\n')}\n`)
console.log(`${classes.size} classes, ${methods.size} méthodes, ${unreadable} lignes illisibles`)
