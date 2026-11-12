#!/usr/bin/env node
// Génère les images du logo (clap doré, docs/DESIGN.md) pour l'app Android
// depuis docs/brand/logo.png (fond transparent, recadré au plus juste).
//
// Usage (racine du dépôt) : npm i --no-save sharp && node scripts/brand_assets.mjs
//
// - Icône adaptative : premier plan et icône thématique (Android 13+), 108 dp,
//   logo dans la zone sûre, une image par densité (mipmap-*dpi).
// - Logo affiché dans l'app (drawable-nodpi/ic_logo.png).
// - Bannière Android TV 320 × 180 dp (drawable-xhdpi/banner.png) : logo et mot
//   « Hey Stream » (docs/brand/wordmark.svg, Inter Bold vectorisée).
// - Site : site/logo.png (en-tête et accueil) et site/favicon.png.
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import sharp from 'sharp'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const RES = resolve(ROOT, 'android/app/src/main/res')
const LOGO = resolve(ROOT, 'docs/brand/logo.png')
const WORDMARK = resolve(ROOT, 'docs/brand/wordmark.svg')
const BG = { r: 0x12, g: 0x12, b: 0x14, alpha: 1 } // fond de l'app, #121214
const CLEAR = { r: 0, g: 0, b: 0, alpha: 0 }
const DENSITIES = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 }
// Part des 108 dp occupée par le plus grand côté du logo (zone sûre : 66 dp).
const LAUNCHER_BOX = 0.68

const out = (dir, name) => {
  mkdirSync(resolve(RES, dir), { recursive: true })
  return resolve(RES, dir, name)
}

// Logo redimensionné pour tenir dans w × h.
const fit = (input, w, h) =>
  sharp(input).resize(Math.round(w), Math.round(h), { fit: 'inside', kernel: 'lanczos3' }).png().toBuffer()

async function centered(input, size, box, background) {
  const logo = await fit(input, size * box, size * box)
  return sharp({ create: { width: size, height: size, channels: 4, background } })
    .composite([{ input: logo, gravity: 'centre' }])
    .png({ compressionLevel: 9 })
}

// Icône thématique : seules les parties dorées (claires) restent, en blanc.
async function monochrome() {
  const { data, info } = await sharp(LOGO).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
  const px = Buffer.alloc(data.length)
  for (let i = 0; i < data.length; i += 4) {
    const lum = 0.2126 * data[i] + 0.7152 * data[i + 1] + 0.0722 * data[i + 2]
    const t = Math.min(1, Math.max(0, (lum - 80) / 70))
    px[i] = px[i + 1] = px[i + 2] = 255
    px[i + 3] = Math.round(data[i + 3] * t)
  }
  return sharp(px, { raw: info }).png().toBuffer()
}

const mono = await monochrome()
for (const [density, k] of Object.entries(DENSITIES)) {
  const size = Math.round(108 * k)
  await (await centered(LOGO, size, LAUNCHER_BOX, CLEAR)).toFile(out(`mipmap-${density}`, 'ic_launcher_foreground.png'))
  await (await centered(mono, size, LAUNCHER_BOX, CLEAR)).toFile(out(`mipmap-${density}`, 'ic_launcher_monochrome.png'))
}

// Logo dans l'app : 64 dp de haut au plus, à 4× (xxxhdpi).
await sharp(LOGO).resize({ height: 256, kernel: 'lanczos3' }).png({ compressionLevel: 9 })
  .toFile(out('drawable-nodpi', 'ic_logo.png'))

// Bannière TV, en xhdpi (640 × 360 px).
const K = 2
const logo = await sharp(LOGO).resize({ height: 104 * K, kernel: 'lanczos3' }).png().toBuffer()
const word = await sharp(WORDMARK, { density: 72 * K }).trim({ threshold: 1 }).png().toBuffer()
const [lm, wm] = [await sharp(logo).metadata(), await sharp(word).metadata()]
const gap = 14 * K
const left = Math.round((320 * K - (lm.width + gap + wm.width)) / 2)
await sharp({ create: { width: 320 * K, height: 180 * K, channels: 4, background: BG } })
  .composite([
    { input: logo, left, top: Math.round((180 * K - lm.height) / 2) },
    { input: word, left: left + lm.width + gap, top: Math.round((180 * K - wm.height) / 2) },
  ])
  .flatten({ background: BG })
  .png({ compressionLevel: 9 })
  .toFile(out('drawable-xhdpi', 'banner.png'))

// Site : 256 px de large (accueil à 128 px CSS, écrans 2×), favicon carré 64 px.
const SITE = resolve(ROOT, 'site')
await sharp(LOGO).resize({ width: 256, kernel: 'lanczos3' }).png({ compressionLevel: 9 }).toFile(resolve(SITE, 'logo.png'))
await (await centered(LOGO, 64, 1, CLEAR)).toFile(resolve(SITE, 'favicon.png'))

console.log('Logo : icône, icône thématique, logo, bannière TV et site générés.')
