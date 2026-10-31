import { test } from "node:test";
import assert from "node:assert/strict";
import { gzipSync } from "node:zlib";
import worker, { expire } from "../src/index.js";

/** R2 en mémoire : ce dont le service se sert. */
class FakeBucket {
  constructor() {
    this.objects = new Map();
  }
  async put(key, value, options = {}) {
    const bytes = typeof value === "string" ? new TextEncoder().encode(value) : new Uint8Array(value);
    this.objects.set(key, { bytes, ...options, uploaded: new Date() });
  }
  async get(key) {
    const o = this.objects.get(key);
    if (!o) return null;
    return {
      size: o.bytes.length,
      httpMetadata: o.httpMetadata,
      body: new Blob([o.bytes]).stream(),
      json: async () => JSON.parse(new TextDecoder().decode(o.bytes)),
    };
  }
  async list({ prefix = "", limit = 1000 } = {}) {
    const objects = [...this.objects.entries()]
      .filter(([k]) => k.startsWith(prefix))
      .slice(0, limit)
      .map(([key, o]) => ({ key, size: o.bytes.length, uploaded: o.uploaded, customMetadata: o.customMetadata }));
    return { objects, truncated: false };
  }
  async delete(keys) {
    for (const k of [].concat(keys)) this.objects.delete(k);
  }
}

const DEVICE = "0f8fad5b-d9cb-469f-a165-70867728950e";
const env = () => ({ BUCKET: new FakeBucket(), INGEST_KEY: "envoi", ADMIN_KEY: "admin", SHARE_KEY: "partage" });

function call(e, path, { method = "GET", key = "envoi", headers = {}, body } = {}) {
  return worker.fetch(
    new Request(`https://collecte.example${path}`, {
      method,
      body,
      headers: {
        authorization: `Bearer ${key}`,
        "x-device": DEVICE,
        "x-app-version": "0.7.0",
        ...headers,
      },
    }),
    e,
  );
}

const ingest = (e, body = gzipSync('{"type":"screen"}\n')) =>
  call(e, "/v1/ingest", { method: "POST", headers: { "x-kind": "events" }, body });

test("un lot est rangé par sorte, jour et appareil", async () => {
  const e = env();
  assert.deepEqual(await (await call(e, "/v1/status")).json(), { collect: true });
  assert.equal((await ingest(e)).status, 204);
  const keys = [...e.BUCKET.objects.keys()].filter((k) => k.startsWith("events/"));
  assert.equal(keys.length, 1);
  assert.match(keys[0], new RegExp(`^events/\\d{4}-\\d{2}-\\d{2}/${DEVICE}/.+\\.ndjson\\.gz$`));
  assert.ok(e.BUCKET.objects.has(`devices/${DEVICE}.json`));

  const object = await call(e, `/v1/admin/object?key=${encodeURIComponent(keys[0])}&decompress=1`, { key: "admin" });
  assert.equal(await object.text(), '{"type":"screen"}\n');
});

test("les clés sont vérifiées, et la clé d'envoi ne lit rien", async () => {
  const e = env();
  assert.equal((await call(e, "/v1/status", { key: "faux" })).status, 401);
  assert.equal((await call(e, "/v1/admin/list", { key: "envoi" })).status, 401);
  assert.equal((await call(e, "/v1/status", { headers: { "x-device": "../x" } })).status, 400);
  assert.equal(
    (await call(e, "/v1/ingest", { method: "POST", headers: { "x-kind": "../config" }, body: gzipSync("{}") })).status,
    400,
  );
  assert.equal((await ingest(e, "pas du gzip")).status, 400);
});

test("la coupure à distance arrête tout l'envoi ou celui d'un appareil", async () => {
  for (const control of [{ paused: true }, { paused_devices: [DEVICE] }]) {
    const e = env();
    const put = await call(e, "/v1/admin/control", { method: "PUT", key: "admin", body: JSON.stringify(control) });
    assert.equal(put.status, 200);
    assert.deepEqual(await (await call(e, "/v1/status")).json(), { collect: false });
    assert.equal((await ingest(e)).status, 423);
  }
});

test("les lots de plus d'un an sont effacés", async () => {
  const e = env();
  const now = new Date("2027-11-01T03:17:00Z");
  await e.BUCKET.put("events/2026-10-31/d/a.ndjson.gz", "x");
  await e.BUCKET.put("responses/2026-10-01/d/b.ndjson.gz", "x");
  await e.BUCKET.put("events/2026-11-02/d/c.ndjson.gz", "x");
  await e.BUCKET.put("config/control.json", "{}");
  assert.equal(await expire(e, now), 2);
  assert.deepEqual([...e.BUCKET.objects.keys()].sort(), ["config/control.json", "events/2026-11-02/d/c.ndjson.gz"]);
});

test("l'APK testeur n'est servi qu'avec la clé d'envoi", async () => {
  const e = env();
  assert.equal((await call(e, "/v1/update")).status, 404);
  await e.BUCKET.put("apk/hey-stream-0.7.0-t.apk", "apk");
  await e.BUCKET.put(
    "apk/latest.json",
    JSON.stringify({ version: "0.7.0", notes: "- x", size: 3, sha256: "ab", file: "apk/hey-stream-0.7.0-t.apk" }),
  );
  assert.deepEqual(await (await call(e, "/v1/update")).json(), { version: "0.7.0", notes: "- x", size: 3, sha256: "ab" });
  const apk = await call(e, "/v1/update/apk?version=0.7.0");
  assert.equal(apk.status, 200);
  assert.equal(await apk.text(), "apk");
  assert.equal((await call(e, "/v1/update/apk", { key: "faux" })).status, 401);
  // Comme l'app (download_update) : la clé seule, sans appareil ni version.
  const bare = await worker.fetch(
    new Request("https://collecte.example/v1/update/apk?version=0.7.0", { headers: { authorization: "Bearer envoi" } }),
    e,
  );
  assert.equal(bare.status, 200);
  assert.equal(await bare.text(), "apk");
  // Lien à partager : l'APK seulement, avec la clé de partage.
  const shared = await call(e, "/v1/apk?share=partage", { key: "" });
  assert.equal(shared.status, 200);
  assert.match(shared.headers.get("content-disposition"), /hey-stream-0\.7\.0-t\.apk/);
  assert.equal((await call(e, "/v1/apk?share=admin", { key: "" })).status, 401);
  assert.equal((await call(e, "/v1/apk", { key: "" })).status, 401);
  assert.equal((await call(e, "/v1/admin/devices?share=partage", { key: "" })).status, 401);
  const unset = { ...e, SHARE_KEY: undefined };
  assert.equal((await call(unset, "/v1/apk?share=", { key: "" })).status, 401);
  // Le propriétaire le télécharge avec sa clé, dans un navigateur.
  const owner = await call(e, "/v1/admin/apk?key=admin", { key: "" });
  assert.equal(owner.status, 200);
  assert.match(owner.headers.get("content-disposition"), /hey-stream-0\.7\.0-t\.apk/);
  assert.equal((await call(e, "/v1/admin/apk?key=envoi", { key: "" })).status, 401);
  // Jamais effacé par la conservation d'un an.
  await expire(e, new Date("2030-01-01T00:00:00Z"));
  assert.ok(e.BUCKET.objects.has("apk/latest.json"));
});
