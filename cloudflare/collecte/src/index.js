// Service de collecte des données de l'APK testeur de Hey Stream.
//
// L'app (clé d'envoi INGEST_KEY) :
//   GET  /v1/status   → {"collect": bool} ; coupure à distance.
//   POST /v1/ingest   → lot gzip de lignes JSON, rangé tel quel dans R2.
// Le propriétaire et ses rapports (clé ADMIN_KEY, jamais dans l'app) :
//   GET  /v1/admin/devices          → appareils vus, dernière visite, version.
//   GET  /v1/admin/list?prefix=…    → objets (clé, taille, date).
//   GET  /v1/admin/object?key=…     → un lot ; &decompress=1 pour du texte.
//   GET|PUT /v1/admin/control       → {"paused": bool, "paused_devices": [...]}.
// Chaque nuit : effacement des lots de plus de RETENTION_DAYS jours.
//
// Rangement : <kind>/<AAAA-MM-JJ>/<appareil>/<heure>-<aléa>.ndjson.gz, kind
// = responses (réponses du panneau, nettoyées sur l'appareil) ou events.

export const RETENTION_DAYS = 365;
const KINDS = new Set(["responses", "events"]);
const DEVICE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const VERSION = /^[0-9A-Za-z.+-]{1,32}$/;
const MAX_BODY = 64 * 1024 * 1024;
const CONTROL = "config/control.json";

const json = (value, status = 200) =>
  new Response(JSON.stringify(value), {
    status,
    headers: { "content-type": "application/json" },
  });

/** Comparaison en temps constant d'une clé `Bearer`. */
function authorized(request, secret) {
  if (!secret) return false;
  const given = (request.headers.get("authorization") || "").replace(/^Bearer /, "");
  if (given.length !== secret.length) return false;
  let diff = 0;
  for (let i = 0; i < secret.length; i++) diff |= given.charCodeAt(i) ^ secret.charCodeAt(i);
  return diff === 0;
}

async function control(env) {
  const object = await env.BUCKET.get(CONTROL);
  const value = object ? await object.json().catch(() => ({})) : {};
  return {
    paused: value.paused === true,
    paused_devices: Array.isArray(value.paused_devices) ? value.paused_devices : [],
  };
}

const collecting = (c, device) => !c.paused && !c.paused_devices.includes(device);

function day(date) {
  return date.toISOString().slice(0, 10);
}

async function app(request, env, url) {
  if (!authorized(request, env.INGEST_KEY)) return json({ error: "unauthorized" }, 401);
  const device = request.headers.get("x-device") || "";
  const version = request.headers.get("x-app-version") || "";
  if (!DEVICE.test(device) || !VERSION.test(version)) return json({ error: "bad request" }, 400);
  const c = await control(env);

  if (url.pathname === "/v1/status" && request.method === "GET") {
    await env.BUCKET.put(
      `devices/${device}.json`,
      JSON.stringify({ device, version, last_seen: new Date().toISOString() }),
      { httpMetadata: { contentType: "application/json" } },
    );
    return json({ collect: collecting(c, device) });
  }

  if (url.pathname === "/v1/ingest" && request.method === "POST") {
    if (!collecting(c, device)) return json({ collect: false }, 423);
    const kind = request.headers.get("x-kind") || "";
    if (!KINDS.has(kind)) return json({ error: "bad kind" }, 400);
    const length = Number(request.headers.get("content-length") || "0");
    if (length > MAX_BODY) return json({ error: "too large" }, 413);
    const body = await request.arrayBuffer();
    const bytes = new Uint8Array(body);
    if (bytes.length < 2 || bytes.length > MAX_BODY || bytes[0] !== 0x1f || bytes[1] !== 0x8b) {
      return json({ error: "gzip expected" }, 400);
    }
    const now = new Date();
    const random = crypto.randomUUID().slice(0, 8);
    const key = `${kind}/${day(now)}/${device}/${now.toISOString().replace(/[:.]/g, "-")}-${random}.ndjson.gz`;
    await env.BUCKET.put(key, body, {
      httpMetadata: { contentType: "application/gzip" },
      customMetadata: { version },
    });
    return new Response(null, { status: 204 });
  }
  return json({ error: "not found" }, 404);
}

async function admin(request, env, url) {
  if (!authorized(request, env.ADMIN_KEY)) return json({ error: "unauthorized" }, 401);
  const path = url.pathname;

  if (path === "/v1/admin/control" && request.method === "GET") return json(await control(env));
  if (path === "/v1/admin/control" && request.method === "PUT") {
    const value = await request.json().catch(() => null);
    if (!value || typeof value !== "object") return json({ error: "json expected" }, 400);
    const next = {
      paused: value.paused === true,
      paused_devices: (Array.isArray(value.paused_devices) ? value.paused_devices : []).filter((d) =>
        DEVICE.test(d),
      ),
    };
    await env.BUCKET.put(CONTROL, JSON.stringify(next), {
      httpMetadata: { contentType: "application/json" },
    });
    return json(next);
  }

  if (path === "/v1/admin/list" && request.method === "GET") {
    const listed = await env.BUCKET.list({
      prefix: url.searchParams.get("prefix") || undefined,
      cursor: url.searchParams.get("cursor") || undefined,
      limit: Math.min(Number(url.searchParams.get("limit")) || 1000, 1000),
    });
    return json({
      objects: listed.objects.map((o) => ({
        key: o.key,
        size: o.size,
        uploaded: o.uploaded,
        version: o.customMetadata?.version,
      })),
      truncated: listed.truncated,
      cursor: listed.truncated ? listed.cursor : null,
    });
  }

  if (path === "/v1/admin/devices" && request.method === "GET") {
    const listed = await env.BUCKET.list({ prefix: "devices/", limit: 1000 });
    const devices = [];
    for (const o of listed.objects) {
      const object = await env.BUCKET.get(o.key);
      if (object) devices.push(await object.json().catch(() => ({ key: o.key })));
    }
    return json({ devices });
  }

  if (path === "/v1/admin/object" && request.method === "GET") {
    const key = url.searchParams.get("key") || "";
    const object = key ? await env.BUCKET.get(key) : null;
    if (!object) return json({ error: "not found" }, 404);
    if (url.searchParams.get("decompress") === "1" && key.endsWith(".gz")) {
      return new Response(object.body.pipeThrough(new DecompressionStream("gzip")), {
        headers: { "content-type": "application/x-ndjson; charset=utf-8" },
      });
    }
    return new Response(object.body, {
      headers: { "content-type": object.httpMetadata?.contentType || "application/octet-stream" },
    });
  }
  return json({ error: "not found" }, 404);
}

/** Efface les lots des jours au-delà de la durée de conservation (rattrape un mois de nuits manquées). */
export async function expire(env, now = new Date()) {
  let deleted = 0;
  for (const kind of KINDS) {
    for (let back = RETENTION_DAYS + 1; back <= RETENTION_DAYS + 31; back++) {
      const date = day(new Date(now.getTime() - back * 86_400_000));
      let cursor;
      do {
        const listed = await env.BUCKET.list({ prefix: `${kind}/${date}/`, cursor, limit: 1000 });
        const keys = listed.objects.map((o) => o.key);
        if (keys.length) await env.BUCKET.delete(keys);
        deleted += keys.length;
        cursor = listed.truncated ? listed.cursor : undefined;
      } while (cursor);
    }
  }
  return deleted;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname.startsWith("/v1/admin/")) return admin(request, env, url);
    if (url.pathname.startsWith("/v1/")) return app(request, env, url);
    return json({ error: "not found" }, 404);
  },
  async scheduled(_event, env, ctx) {
    ctx.waitUntil(expire(env));
  },
};
