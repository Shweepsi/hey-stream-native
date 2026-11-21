//! Appli web locale d'enregistrement DVR : un seul exécutable qui embarque le
//! moteur (`xtream-core`) et la page web. On le lance, il ouvre
//! `http://localhost:8780` ; les enregistrements sont des fichiers `.ts` dans
//! un dossier du PC.
//!
//! Une seule connexion au fournisseur : le moteur refuse deux chaînes en même
//! temps, et la copie en cours est la seule à ouvrir un flux (le guide ne
//! demande que des listes). Accessible depuis cette machine seulement, sauf
//! avec `--lan`.

use std::fmt::Display;
use std::fs::File;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tiny_http::{Header, Method, Request, Response, Server};
use xtream_core::{
    CaptureControl, CaptureOptions, Credentials, GuideProgramme, Margins, Recording, Recordings,
    ScheduleRequest, XtreamClient, capture, volume_space,
};

const PAGE: &str = include_str!("index.html");
const DEFAULT_PORT: u16 = 8780;
/// Durée de vie de la liste des chaînes en mémoire.
const CATALOG_TTL: Duration = Duration::from_secs(600);
/// Chaînes renvoyées à la page au plus.
const CHANNEL_LIMIT: usize = 300;

#[derive(Default, Clone, Serialize, Deserialize)]
struct Config {
    server_url: String,
    username: String,
    password: String,
    /// Dossier des enregistrements ; vide : `enregistrements/` dans le dossier de données.
    dir: String,
}

struct Active {
    id: i64,
    control: CaptureControl,
}

struct Channel {
    id: i64,
    name: String,
    icon: Option<String>,
    category: Option<String>,
}

struct Catalog {
    at: Instant,
    categories: Vec<(String, String)>,
    channels: Vec<Channel>,
}

struct App {
    data_dir: PathBuf,
    config: Mutex<Config>,
    client: Mutex<Option<Arc<XtreamClient>>>,
    rec: Mutex<Recordings>,
    active: Mutex<Option<Active>>,
    catalog: Mutex<Option<Catalog>>,
    rt: tokio::runtime::Runtime,
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs() as i64)
}

impl App {
    fn dir(&self) -> PathBuf {
        let config = lock(&self.config);
        if config.dir.trim().is_empty() {
            self.data_dir.join("enregistrements")
        } else {
            PathBuf::from(config.dir.trim())
        }
    }

    /// Crée le dossier des enregistrements et le donne au planning.
    fn apply_dir(&self) -> Result<(), String> {
        let dir = self.dir();
        std::fs::create_dir_all(&dir).map_err(|e| format!("dossier impossible à créer : {e}"))?;
        lock(&self.rec).set_directory(&dir).map_err(|e| e.to_string())
    }

    fn client(&self) -> Result<Arc<XtreamClient>, (u16, String)> {
        lock(&self.client)
            .clone()
            .ok_or((400, "Renseigne d'abord le serveur dans Réglages.".to_owned()))
    }

    /// Chaînes et catégories du direct, rechargées au plus toutes les 10 min.
    fn with_catalog<T>(&self, f: impl FnOnce(&Catalog) -> T) -> Result<T, (u16, String)> {
        let fresh = lock(&self.catalog)
            .as_ref()
            .is_some_and(|c| c.at.elapsed() < CATALOG_TTL);
        if !fresh {
            let client = self.client()?;
            let (categories, streams) = self
                .rt
                .block_on(async { Ok::<_, xtream_core::Error>((client.live_categories().await?, client.live_streams(None).await?)) })
                .map_err(bad)?;
            let catalog = Catalog {
                at: Instant::now(),
                categories: categories
                    .into_iter()
                    .map(|c| {
                        let name = c.category_name.unwrap_or_else(|| c.category_id.clone());
                        (c.category_id, name)
                    })
                    .collect(),
                channels: streams
                    .into_iter()
                    .map(|s| Channel {
                        id: s.stream_id,
                        name: s.name.unwrap_or_else(|| format!("Chaîne {}", s.stream_id)),
                        icon: s.stream_icon,
                        category: s.category_id,
                    })
                    .collect(),
            };
            *lock(&self.catalog) = Some(catalog);
        }
        let guard = lock(&self.catalog);
        let catalog = guard.as_ref().ok_or((500, "catalogue indisponible".to_owned()))?;
        Ok(f(catalog))
    }
}

fn bad(e: impl Display) -> (u16, String) {
    (500, e.to_string())
}

fn config_path(data_dir: &Path) -> PathBuf {
    data_dir.join("config.json")
}

fn load_config(data_dir: &Path) -> Config {
    std::fs::read_to_string(config_path(data_dir))
        .ok()
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default()
}

fn save_config(data_dir: &Path, config: &Config) -> Result<(), String> {
    let text = serde_json::to_string_pretty(config).map_err(|e| e.to_string())?;
    std::fs::write(config_path(data_dir), text).map_err(|e| e.to_string())
}

fn make_client(config: &Config) -> Option<Arc<XtreamClient>> {
    let credentials = Credentials {
        server_url: config.server_url.clone(),
        username: config.username.clone(),
        password: config.password.clone(),
    };
    XtreamClient::new(&credentials).ok().map(Arc::new)
}

// --- Requêtes ---------------------------------------------------------------

enum Reply {
    Json(Value),
    Html,
    File(PathBuf, Option<String>),
}

type Res = Result<Reply, (u16, String)>;

fn query_param(query: &str, key: &str) -> Option<String> {
    query.split('&').find_map(|pair| {
        let (k, v) = pair.split_once('=')?;
        (k == key).then(|| percent_decode(v))
    })
}

fn percent_decode(text: &str) -> String {
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'+' => out.push(b' '),
            b'%' if i + 2 < bytes.len() => {
                let hex = std::str::from_utf8(&bytes[i + 1..i + 3]).ok();
                match hex.and_then(|h| u8::from_str_radix(h, 16).ok()) {
                    Some(b) => {
                        out.push(b);
                        i += 2;
                    }
                    None => out.push(b'%'),
                }
            }
            other => out.push(other),
        }
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

#[derive(Deserialize)]
struct LoginBody {
    server_url: String,
    username: String,
    password: String,
    #[serde(default)]
    dir: String,
}

#[derive(Deserialize)]
struct ScheduleBody {
    stream_id: i64,
    channel: String,
    title: String,
    start: i64,
    stop: i64,
    #[serde(default)]
    before: i64,
    #[serde(default)]
    after: i64,
}

#[derive(Deserialize)]
struct IdBody {
    id: i64,
}

fn parse<T: for<'de> Deserialize<'de>>(body: &str) -> Result<T, (u16, String)> {
    serde_json::from_str(body).map_err(|e| (400, format!("requête illisible : {e}")))
}

fn schedule_request(b: ScheduleBody) -> ScheduleRequest {
    ScheduleRequest {
        stream_id: b.stream_id,
        channel: b.channel,
        is_adult: false,
        programme: GuideProgramme {
            title: b.title,
            description: None,
            start: b.start,
            stop: b.stop,
        },
        margins: Margins {
            before_secs: b.before.max(0),
            after_secs: b.after.max(0),
        },
        buffered_from: None,
    }
}

fn recording_json(r: &Recording, active: Option<&Active>) -> Value {
    let live = active.filter(|a| a.id == r.id);
    json!({
        "id": r.id,
        "stream_id": r.stream_id,
        "channel": r.channel,
        "title": r.programmes.first().map_or(r.channel.as_str(), |p| p.title.as_str()),
        "more": r.programmes.len().saturating_sub(1),
        "state": format!("{:?}", r.state).to_lowercase(),
        "problem": r.problem.map(|p| format!("{p:?}")),
        "start": r.start,
        "stop": r.stop,
        "bytes": live.map_or(r.bytes, |a| a.control.bytes()),
        "volume_missing": r.volume_missing,
        "watched": r.watched,
    })
}

fn route(app: &Arc<App>, method: &Method, url: &str, body: &str) -> Res {
    let (path, query) = url.split_once('?').unwrap_or((url, ""));
    match (method, path) {
        (Method::Get, "/") => Ok(Reply::Html),
        (Method::Get, "/api/state") => state(app),
        (Method::Post, "/api/login") => login(app, parse(body)?),
        (Method::Get, "/api/channels") => channels(app, query),
        (Method::Get, "/api/epg") => epg(app, query),
        (Method::Post, "/api/preview") => preview(app, parse(body)?),
        (Method::Post, "/api/schedule") => schedule(app, parse(body)?),
        (Method::Get, "/api/recordings") => recordings(app),
        (Method::Post, "/api/cancel") => cancel(app, parse::<IdBody>(body)?.id),
        (Method::Post, "/api/delete") => delete(app, parse::<IdBody>(body)?.id),
        (Method::Get, p) if p.starts_with("/files/") => file(app, &p["/files/".len()..], query),
        _ => Err((404, "introuvable".to_owned())),
    }
}

fn state(app: &Arc<App>) -> Res {
    let dir = app.dir();
    let configured = lock(&app.client).is_some();
    let space = volume_space(&dir);
    let usage = space.and_then(|s| lock(&app.rec).usage(&dir, s).ok());
    Ok(Reply::Json(json!({
        "configured": configured,
        "dir": dir.to_string_lossy(),
        "now": now(),
        "disk": space.map(|s| json!({"total": s.total_bytes, "free": s.free_bytes})),
        "usage": usage.map(|u| json!({
            "used": u.used_bytes,
            "usable": u.usable_bytes,
            "remaining_secs": u.remaining_secs,
            "low": u.low,
        })),
    })))
}

fn login(app: &Arc<App>, body: LoginBody) -> Res {
    let config = Config {
        server_url: body.server_url.trim().to_owned(),
        username: body.username.trim().to_owned(),
        password: body.password.trim().to_owned(),
        dir: body.dir.trim().to_owned(),
    };
    let client = make_client(&config).ok_or((400, "Serveur ou identifiants invalides.".to_owned()))?;
    app.rt.block_on(client.authenticate()).map_err(|e| (400, e.to_string()))?;
    save_config(&app.data_dir, &config).map_err(bad)?;
    *lock(&app.config) = config;
    *lock(&app.client) = Some(client);
    *lock(&app.catalog) = None;
    app.apply_dir().map_err(|e| (500, e))?;
    Ok(Reply::Json(json!({"ok": true})))
}

fn channels(app: &Arc<App>, query: &str) -> Res {
    let category = query_param(query, "category").filter(|c| !c.is_empty());
    let search = query_param(query, "q").unwrap_or_default().to_lowercase();
    let value = app.with_catalog(|catalog| {
        let list: Vec<Value> = catalog
            .channels
            .iter()
            .filter(|c| category.is_none() || c.category == category)
            .filter(|c| search.is_empty() || c.name.to_lowercase().contains(&search))
            .take(CHANNEL_LIMIT)
            .map(|c| json!({"id": c.id, "name": c.name, "icon": c.icon}))
            .collect();
        let categories: Vec<Value> = catalog
            .categories
            .iter()
            .map(|(id, name)| json!({"id": id, "name": name}))
            .collect();
        json!({"categories": categories, "channels": list})
    })?;
    Ok(Reply::Json(value))
}

fn epg(app: &Arc<App>, query: &str) -> Res {
    let id: i64 = query_param(query, "stream_id")
        .and_then(|v| v.parse().ok())
        .ok_or((400, "stream_id manquant".to_owned()))?;
    let client = app.client()?;
    let programmes = app.rt.block_on(client.short_epg(id)).map_err(bad)?;
    let list: Vec<Value> = programmes
        .iter()
        .map(|p| json!({"title": p.title, "description": p.description, "start": p.start, "stop": p.stop}))
        .collect();
    Ok(Reply::Json(json!({"programmes": list})))
}

fn preview(app: &Arc<App>, body: ScheduleBody) -> Res {
    let space = volume_space(&app.dir()).ok_or((400, "Le dossier des enregistrements est introuvable.".to_owned()))?;
    let request = schedule_request(body);
    let p = lock(&app.rec).preview(&request, space, now()).map_err(bad)?;
    Ok(Reply::Json(json!({
        "merged_into": p.merged_into,
        "start": p.start,
        "stop": p.stop,
        "margins_shortened": p.margins_shortened,
        "measured": p.measured,
        "estimated_bytes": p.estimated_bytes,
        "missing_bytes": p.missing_bytes,
    })))
}

fn schedule(app: &Arc<App>, body: ScheduleBody) -> Res {
    app.apply_dir().map_err(|e| (500, e))?;
    let request = schedule_request(body);
    // Les marges retenues ne servent qu'au guide de l'appli Android : ici la
    // page les choisit à chaque fois.
    let id = lock(&app.rec).schedule_manual(&request, now()).map_err(bad)?;
    Ok(Reply::Json(json!({"id": id})))
}

fn recordings(app: &Arc<App>) -> Res {
    let guard = lock(&app.active);
    let active = guard.as_ref();
    let rec = lock(&app.rec);
    let scheduled = rec.scheduled(true).map_err(bad)?;
    let done = rec.recorded(true).map_err(bad)?;
    Ok(Reply::Json(json!({
        "scheduled": scheduled.iter().map(|r| recording_json(r, active)).collect::<Vec<_>>(),
        "recorded": done.iter().map(|r| recording_json(r, active)).collect::<Vec<_>>(),
    })))
}

fn cancel(app: &Arc<App>, id: i64) -> Res {
    if let Some(active) = lock(&app.active).as_ref().filter(|a| a.id == id) {
        active.control.stop();
    } else {
        lock(&app.rec).cancel(id).map_err(bad)?;
    }
    Ok(Reply::Json(json!({"ok": true})))
}

fn delete(app: &Arc<App>, id: i64) -> Res {
    lock(&app.rec).delete(id).map_err(bad)?;
    Ok(Reply::Json(json!({"ok": true})))
}

fn file(app: &Arc<App>, id: &str, query: &str) -> Res {
    let id: i64 = id.parse().map_err(|_| (404, "introuvable".to_owned()))?;
    let recording = lock(&app.rec)
        .recording(id)
        .map_err(bad)?
        .ok_or((404, "Cet enregistrement n'existe plus.".to_owned()))?;
    let name = (query_param(query, "dl").as_deref() == Some("1"))
        .then(|| format!("enregistrement-{id}.ts"));
    Ok(Reply::File(recording.path, name))
}

fn header(name: &str, value: &str) -> Header {
    Header::from_bytes(name.as_bytes(), value.as_bytes()).expect("en-tête valide")
}

fn handle(app: Arc<App>, mut request: Request) {
    let mut body = String::new();
    if *request.method() == Method::Post {
        let _ = request.as_reader().read_to_string(&mut body);
    }
    let method = request.method().clone();
    let url = request.url().to_owned();
    let result = route(&app, &method, &url, &body);
    let _ = match result {
        Ok(Reply::Json(value)) => request.respond(
            Response::from_string(value.to_string()).with_header(header("Content-Type", "application/json; charset=utf-8")),
        ),
        Ok(Reply::Html) => request.respond(
            Response::from_string(PAGE).with_header(header("Content-Type", "text/html; charset=utf-8")),
        ),
        Ok(Reply::File(path, download)) => match File::open(&path) {
            Ok(file) => {
                let mut response = Response::from_file(file).with_header(header("Content-Type", "video/mp2t"));
                if let Some(name) = download {
                    response = response.with_header(header(
                        "Content-Disposition",
                        &format!("attachment; filename=\"{name}\""),
                    ));
                }
                request.respond(response)
            }
            Err(_) => request.respond(
                Response::from_string(json!({"error": "Fichier introuvable (disque débranché ?)."}).to_string())
                    .with_status_code(404)
                    .with_header(header("Content-Type", "application/json; charset=utf-8")),
            ),
        },
        Err((code, message)) => request.respond(
            Response::from_string(json!({"error": message}).to_string())
                .with_status_code(code)
                .with_header(header("Content-Type", "application/json; charset=utf-8")),
        ),
    };
}

// --- Planificateur ----------------------------------------------------------

/// Toutes les 3 s : prolonge ou raccourcit la copie en cours, ou démarre
/// l'enregistrement dû. Une seule copie à la fois.
fn tick(app: &Arc<App>) {
    let now = now();
    let running = lock(&app.active).as_ref().map(|a| (a.id, a.control.clone()));
    if let Some((id, control)) = running {
        let mut rec = lock(&app.rec);
        if let Ok(Some(due)) = rec.due(now)
            && due.id == id
        {
            control.set_until(due.stop);
        }
        let _ = rec.update_progress(id, control.bytes(), now);
        return;
    }
    let Some(client) = lock(&app.client).clone() else {
        return;
    };
    let due = match lock(&app.rec).due(now) {
        Ok(Some(due)) => due,
        _ => return,
    };
    if app.apply_dir().is_err() {
        return;
    }
    let space = volume_space(&app.dir());
    let job = match lock(&app.rec).begin(due.id, space, now) {
        Ok(job) => job,
        Err(e) => {
            eprintln!("Enregistrement {} impossible : {e}", due.id);
            return;
        }
    };
    let control = CaptureControl::default();
    *lock(&app.active) = Some(Active {
        id: job.recording_id,
        control: control.clone(),
    });
    println!("Enregistrement {} démarré.", job.recording_id);
    let app = app.clone();
    std::thread::spawn(move || {
        let report = app
            .rt
            .block_on(capture(&client, &job, &control, &CaptureOptions::default()));
        match lock(&app.rec).finish(job.recording_id, &report, self::now()) {
            Ok(r) => println!("Enregistrement {} terminé ({:?}).", r.id, r.state),
            Err(e) => eprintln!("Bilan de l'enregistrement {} perdu : {e}", job.recording_id),
        }
        *lock(&app.active) = None;
    });
}

// --- Démarrage --------------------------------------------------------------

struct Options {
    port: u16,
    lan: bool,
    open: bool,
    data_dir: PathBuf,
}

fn default_data_dir() -> PathBuf {
    if let Some(appdata) = std::env::var_os("APPDATA") {
        return PathBuf::from(appdata).join("heystream-dvr");
    }
    let home = std::env::var_os("HOME").map_or_else(|| PathBuf::from("."), PathBuf::from);
    home.join(".heystream-dvr")
}

fn options() -> Options {
    let mut options = Options {
        port: DEFAULT_PORT,
        lan: false,
        open: true,
        data_dir: default_data_dir(),
    };
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--port" => {
                if let Some(port) = args.next().and_then(|p| p.parse().ok()) {
                    options.port = port;
                }
            }
            "--data" => {
                if let Some(dir) = args.next() {
                    options.data_dir = PathBuf::from(dir);
                }
            }
            "--lan" => options.lan = true,
            "--no-open" => options.open = false,
            _ => {}
        }
    }
    options
}

fn open_browser(url: &str) {
    #[cfg(windows)]
    let _ = std::process::Command::new("cmd")
        .args(["/C", "start", "", url])
        .spawn();
    #[cfg(not(windows))]
    let _ = url;
}

fn main() {
    let options = options();
    if let Err(e) = std::fs::create_dir_all(&options.data_dir) {
        eprintln!("Dossier de données impossible à créer : {e}");
        return;
    }
    let config = load_config(&options.data_dir);
    let rec = match Recordings::open(options.data_dir.join("planning.db")) {
        Ok(rec) => rec,
        Err(e) => {
            eprintln!("Planning illisible : {e}");
            return;
        }
    };
    let rt = match tokio::runtime::Builder::new_multi_thread().enable_all().build() {
        Ok(rt) => rt,
        Err(e) => {
            eprintln!("Moteur asynchrone impossible à démarrer : {e}");
            return;
        }
    };
    let app = Arc::new(App {
        data_dir: options.data_dir.clone(),
        client: Mutex::new(make_client(&config)),
        config: Mutex::new(config),
        rec: Mutex::new(rec),
        active: Mutex::new(None),
        catalog: Mutex::new(None),
        rt,
    });
    if let Err(e) = app.apply_dir() {
        eprintln!("{e}");
    }
    // Enregistrements interrompus par un arrêt du programme.
    let _ = lock(&app.rec).recover(now());

    let host = if options.lan { "0.0.0.0" } else { "127.0.0.1" };
    let server = match Server::http((host, options.port)) {
        Ok(server) => server,
        Err(e) => {
            eprintln!("Port {} impossible à ouvrir : {e}", options.port);
            return;
        }
    };
    let url = format!("http://localhost:{}", options.port);
    println!("hey-stream DVR : {url}  (Ctrl+C pour quitter)");
    println!("Données : {}", options.data_dir.display());
    if options.open {
        open_browser(&url);
    }

    let scheduler = app.clone();
    std::thread::spawn(move || {
        loop {
            tick(&scheduler);
            std::thread::sleep(Duration::from_secs(3));
        }
    });
    for request in server.incoming_requests() {
        let app = app.clone();
        std::thread::spawn(move || handle(app, request));
    }
}
