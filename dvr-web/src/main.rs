//! Appli web locale d'enregistrement DVR : un seul exécutable qui embarque le
//! moteur (`xtream-core`) et la page web. On le lance, il ouvre
//! `http://localhost:8780` ; les enregistrements sont des fichiers `.ts` dans
//! un dossier du PC.
//!
//! Plusieurs comptes : chacun a son planning et ne copie qu'une chaîne à la
//! fois (une connexion par compte chez le fournisseur). Pour enregistrer
//! plusieurs chaînes en même temps, on ajoute des comptes ; une programmation
//! va sur le premier compte libre à ce moment-là. Accessible depuis cette
//! machine seulement, sauf avec `--lan`.

mod hls;
mod range;

use std::fmt::Display;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tiny_http::{Header, Method, Request, Response, Server, StatusCode};
use xtream_core::{
    CaptureControl, CaptureOptions, Credentials, GuideProgramme, Margins, Recording,
    RecordingState, Recordings, ScheduleRequest, SchedulePreview, XtreamClient, capture,
    volume_space,
};

use range::ByteRange;

const PAGE: &str = include_str!("index.html");
const DEFAULT_PORT: u16 = 8780;
/// Durée de vie de la liste des chaînes en mémoire.
const CATALOG_TTL: Duration = Duration::from_secs(600);
/// Chaînes renvoyées à la page au plus.
const CHANNEL_LIMIT: usize = 300;
/// Pause après un échec du fournisseur avant de le rappeler.
/// Programmes demandés au panneau pour le guide d'une chaîne (le reste de la journée et le lendemain).
const GUIDE_LIMIT: u32 = 40;
const RETRY_AFTER: Duration = Duration::from_secs(60);

#[derive(Clone, Serialize, Deserialize)]
struct AccountConfig {
    id: u32,
    server_url: String,
    username: String,
    password: String,
}

#[derive(Default, Clone, Serialize, Deserialize)]
struct Config {
    #[serde(default)]
    accounts: Vec<AccountConfig>,
    /// Prochain numéro de compte (jamais réutilisé : il nomme le dossier).
    #[serde(default)]
    next_id: u32,
    /// Dossier des enregistrements ; vide : `enregistrements/` dans le dossier de données.
    #[serde(default)]
    dir: String,
    // Ancien format, un seul compte : repris comme compte 1.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    server_url: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    username: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    password: String,
}

struct Active {
    id: i64,
    control: CaptureControl,
}

/// Un compte : son client, son planning et sa copie en cours.
struct Account {
    config: AccountConfig,
    client: Arc<XtreamClient>,
    rec: Mutex<Recordings>,
    active: Mutex<Option<Active>>,
}

impl Account {
    fn label(&self) -> &str {
        &self.config.username
    }

    /// Dossier de ce compte : le compte 1 garde le dossier de base (fichiers
    /// d'avant les comptes multiples), les autres ont le leur.
    fn dir(&self, base: &Path) -> PathBuf {
        if self.config.id == 1 {
            base.to_path_buf()
        } else {
            base.join(format!("compte-{}", self.config.id))
        }
    }
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
    /// Dossier choisi (vide : par défaut).
    dir: Mutex<String>,
    next_id: Mutex<u32>,
    accounts: Mutex<Vec<Arc<Account>>>,
    catalog: Mutex<Option<Catalog>>,
    /// Dernier échec du chargement des chaînes (heure, message).
    failure: Mutex<Option<(Instant, String)>>,
    /// Un seul calcul d'index de lecture à la fois (il lit tout le fichier).
    index_lock: Mutex<()>,
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
    fn base_dir(&self) -> PathBuf {
        let dir = lock(&self.dir);
        if dir.trim().is_empty() {
            self.data_dir.join("enregistrements")
        } else {
            PathBuf::from(dir.trim())
        }
    }

    fn accounts(&self) -> Vec<Arc<Account>> {
        lock(&self.accounts).clone()
    }

    /// Crée les dossiers des enregistrements et les donne aux plannings.
    fn apply_dir(&self) -> Result<(), String> {
        let base = self.base_dir();
        for account in self.accounts() {
            let dir = account.dir(&base);
            std::fs::create_dir_all(&dir)
                .map_err(|e| format!("dossier impossible à créer : {e}"))?;
            lock(&account.rec)
                .set_directory(&dir)
                .map_err(|e| e.to_string())?;
        }
        Ok(())
    }

    fn persist(&self) -> Result<(), String> {
        let config = Config {
            accounts: self.accounts().iter().map(|a| a.config.clone()).collect(),
            next_id: *lock(&self.next_id),
            dir: lock(&self.dir).clone(),
            ..Config::default()
        };
        save_config(&self.data_dir, &config)
    }

    /// Où est gardé l'index de lecture d'un enregistrement.
    fn index_path(&self, account: u32, id: i64) -> PathBuf {
        self.data_dir.join("hls").join(format!("{account}-{id}.txt"))
    }

    /// Index de lecture d'un enregistrement : relu s'il est déjà calculé pour ce
    /// fichier, sinon calculé. Pendant l'enregistrement le fichier grandit : on
    /// calcule ce qu'il y a, sans le garder.
    fn playback_index(&self, account: u32, recording: &Recording) -> Result<hls::Index, (u16, String)> {
        let _one_at_a_time = lock(&self.index_lock);
        let cache = (recording.state != RecordingState::Recording)
            .then(|| self.index_path(account, recording.id));
        hls::build(&recording.path, cache.as_deref()).map_err(|e| (422, e))
    }

    fn first_client(&self) -> Result<Arc<XtreamClient>, (u16, String)> {
        self.accounts()
            .first()
            .map(|a| a.client.clone())
            .ok_or((400, "Ajoute d'abord un compte dans Réglages.".to_owned()))
    }

    /// Chaînes et catégories du direct (via le premier compte), rechargées au
    /// plus toutes les 10 min. Un seul chargement à la fois, et après un échec
    /// le fournisseur n'est pas rappelé avant une minute (il bloque les
    /// requêtes en rafale) : la liste périmée, si on l'a, reste servie.
    fn with_catalog<T>(&self, f: impl FnOnce(&Catalog) -> T) -> Result<T, (u16, String)> {
        let mut failure = lock(&self.failure);
        let fresh = lock(&self.catalog)
            .as_ref()
            .is_some_and(|c| c.at.elapsed() < CATALOG_TTL);
        let blocked = failure
            .as_ref()
            .is_some_and(|(at, _)| at.elapsed() < RETRY_AFTER);
        if !fresh && !blocked {
            let client = self.first_client()?;
            let fetched = self.rt.block_on(async {
                let categories = client.live_categories().await?;
                // Une pause entre les deux requêtes : pas de rafale.
                tokio::time::sleep(Duration::from_secs(1)).await;
                let streams = client.live_streams(None).await?;
                Ok::<_, xtream_core::Error>((categories, streams))
            });
            match fetched {
                Ok((categories, streams)) => {
                    *failure = None;
                    *lock(&self.catalog) = Some(Catalog {
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
                    });
                }
                Err(e) => {
                    eprintln!("Chargement des chaînes : {e}");
                    *failure = Some((Instant::now(), e.to_string()));
                }
            }
        }
        let guard = lock(&self.catalog);
        match (guard.as_ref(), failure.as_ref()) {
            (Some(catalog), _) => Ok(f(catalog)),
            (None, Some((_, message))) => Err((
                502,
                format!("Chargement des chaînes impossible : {message}. Nouvel essai possible dans une minute."),
            )),
            (None, None) => Err((500, "catalogue indisponible".to_owned())),
        }
    }
}

fn bad(e: impl Display) -> (u16, String) {
    (500, e.to_string())
}

fn config_path(data_dir: &Path) -> PathBuf {
    data_dir.join("config.json")
}

fn load_config(data_dir: &Path) -> Config {
    let mut config: Config = std::fs::read_to_string(config_path(data_dir))
        .ok()
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default();
    // Ancien format : le compte unique devient le compte 1.
    if config.accounts.is_empty() && !config.username.is_empty() {
        config.accounts.push(AccountConfig {
            id: 1,
            server_url: std::mem::take(&mut config.server_url),
            username: std::mem::take(&mut config.username),
            password: std::mem::take(&mut config.password),
        });
    }
    let max = config.accounts.iter().map(|a| a.id).max().unwrap_or(0);
    config.next_id = config.next_id.max(max + 1);
    config
}

fn save_config(data_dir: &Path, config: &Config) -> Result<(), String> {
    let text = serde_json::to_string_pretty(config).map_err(|e| e.to_string())?;
    std::fs::write(config_path(data_dir), text).map_err(|e| e.to_string())
}

fn make_client(config: &AccountConfig) -> Option<Arc<XtreamClient>> {
    let credentials = Credentials {
        server_url: config.server_url.clone(),
        username: config.username.clone(),
        password: config.password.clone(),
    };
    XtreamClient::new(&credentials).ok().map(Arc::new)
}

/// Ouvre le planning d'un compte (le compte 1 garde `planning.db`).
fn open_account(data_dir: &Path, config: AccountConfig) -> Result<Arc<Account>, String> {
    let client = make_client(&config).ok_or("Serveur ou identifiants invalides.".to_owned())?;
    let file = if config.id == 1 {
        "planning.db".to_owned()
    } else {
        format!("planning-{}.db", config.id)
    };
    let rec = Recordings::open(data_dir.join(file)).map_err(|e| format!("Planning illisible : {e}"))?;
    Ok(Arc::new(Account {
        config,
        client,
        rec: Mutex::new(rec),
        active: Mutex::new(None),
    }))
}

// --- Requêtes ---------------------------------------------------------------

enum Reply {
    Json(Value),
    Html,
    /// Liste de lecture HLS.
    Playlist(String),
    File {
        path: PathBuf,
        /// Nom proposé au téléchargement.
        download: Option<String>,
        /// En-tête `Range` de la requête.
        range: Option<String>,
    },
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
struct RecordingRef {
    account: u32,
    id: i64,
}

#[derive(Deserialize)]
struct AccountRef {
    id: u32,
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

fn recording_json(account: &Account, r: &Recording) -> Value {
    let active = lock(&account.active);
    let live = active.as_ref().filter(|a| a.id == r.id);
    json!({
        "account": account.config.id,
        "account_label": account.label(),
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

fn route(app: &Arc<App>, method: &Method, url: &str, body: &str, range: Option<String>) -> Res {
    let (path, query) = url.split_once('?').unwrap_or((url, ""));
    match (method, path) {
        (Method::Get, "/") => Ok(Reply::Html),
        (Method::Get, "/api/state") => state(app),
        (Method::Post, "/api/login") => add_account(app, parse(body)?),
        (Method::Post, "/api/account/remove") => remove_account(app, parse::<AccountRef>(body)?.id),
        (Method::Get, "/api/channels") => channels(app, query),
        (Method::Get, "/api/epg") => epg(app, query),
        (Method::Post, "/api/preview") => preview(app, parse(body)?),
        (Method::Post, "/api/schedule") => schedule(app, parse(body)?),
        (Method::Get, "/api/recordings") => recordings(app),
        (Method::Post, "/api/cancel") => cancel(app, parse(body)?),
        (Method::Post, "/api/delete") => delete(app, parse(body)?),
        (Method::Get, p) if p.starts_with("/files/") => {
            file(app, &p["/files/".len()..], query, range)
        }
        (Method::Get, p) if p.starts_with("/hls/") => playlist(app, &p["/hls/".len()..]),
        _ => Err((404, "introuvable".to_owned())),
    }
}

fn find_account(app: &App, id: u32) -> Result<Arc<Account>, (u16, String)> {
    app.accounts()
        .into_iter()
        .find(|a| a.config.id == id)
        .ok_or((404, "Ce compte n'existe plus.".to_owned()))
}

fn state(app: &Arc<App>) -> Res {
    let base = app.base_dir();
    let accounts = app.accounts();
    let space = volume_space(&base);
    // Place prise par tous les comptes ; durée restante au débit du premier.
    let usage = space.and_then(|s| {
        let mut used = 0;
        let mut first = None;
        for account in &accounts {
            let u = lock(&account.rec).usage(&account.dir(&base), s).ok()?;
            used += u.used_bytes;
            first.get_or_insert(u);
        }
        first.map(|u| (used, u))
    });
    Ok(Reply::Json(json!({
        "configured": !accounts.is_empty(),
        "dir": base.to_string_lossy(),
        "now": now(),
        "accounts": accounts.iter().map(|a| json!({
            "id": a.config.id,
            "label": a.label(),
            "server": a.config.server_url,
            "recording": lock(&a.active).is_some(),
        })).collect::<Vec<_>>(),
        "disk": space.map(|s| json!({"total": s.total_bytes, "free": s.free_bytes})),
        "usage": usage.map(|(used, u)| json!({
            "used": used,
            "usable": u.usable_bytes,
            "remaining_secs": u.remaining_secs,
            "low": u.low,
        })),
    })))
}

/// Requête brute (HTTP simple) au panneau avec l'identifiant `agent`, pour
/// comprendre un refus : ligne de statut et début de la réponse, mot de passe
/// masqué. Seulement après un échec de connexion.
fn probe(server_url: &str, user: &str, pass: &str, agent: &str) -> String {
    use std::io::{Read, Write};
    use std::net::{TcpStream, ToSocketAddrs};

    let url = server_url.trim();
    if url.starts_with("https://") {
        return "https : non testé".to_owned();
    }
    let rest = url.trim_start_matches("http://").trim_end_matches('/');
    let host_port = if rest.contains(':') {
        rest.to_owned()
    } else {
        format!("{rest}:80")
    };
    let request = format!(
        "GET /player_api.php?username={user}&password={pass} HTTP/1.1\r\nHost: {rest}\r\nUser-Agent: {agent}\r\nAccept: */*\r\nConnection: close\r\n\r\n"
    );
    let answer = (|| -> std::io::Result<String> {
        let addr = host_port
            .to_socket_addrs()?
            .next()
            .ok_or_else(|| std::io::Error::other("adresse introuvable"))?;
        let mut stream = TcpStream::connect_timeout(&addr, Duration::from_secs(10))?;
        stream.set_read_timeout(Some(Duration::from_secs(10)))?;
        stream.write_all(request.as_bytes())?;
        let mut buf = Vec::new();
        let _ = (&mut stream).take(4096).read_to_end(&mut buf);
        Ok(String::from_utf8_lossy(&buf).into_owned())
    })();
    match answer {
        Ok(text) => {
            let text = text.replace(pass, "***");
            let status = text.lines().next().unwrap_or("(réponse vide)").to_owned();
            let body = text.split("\r\n\r\n").nth(1).unwrap_or("");
            let snippet: String = body.chars().take(160).collect();
            format!("{status} · {}", snippet.replace(['\r', '\n'], " "))
        }
        Err(e) => format!("échec réseau : {e}"),
    }
}

/// Ajoute un compte (testé auprès du fournisseur avant d'être gardé).
fn add_account(app: &Arc<App>, body: LoginBody) -> Res {
    let mut config = AccountConfig {
        id: 0,
        server_url: body.server_url.trim().to_owned(),
        username: body.username.trim().to_owned(),
        password: body.password.trim().to_owned(),
    };
    let client = make_client(&config).ok_or((400, "Serveur ou identifiants invalides.".to_owned()))?;
    if let Err(e) = app.rt.block_on(client.authenticate()) {
        let own = format!("HeyStream/{}", env!("CARGO_PKG_VERSION"));
        let browser = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)";
        let message = format!(
            "{e}\nDiagnostic · {own} : {}\nDiagnostic · navigateur : {}",
            probe(&config.server_url, &config.username, &config.password, &own),
            probe(&config.server_url, &config.username, &config.password, browser),
        );
        return Err((400, message));
    }
    if lock(&app.accounts)
        .iter()
        .any(|a| a.config.username == config.username && a.config.server_url == config.server_url)
    {
        return Err((409, "Ce compte est déjà ajouté.".to_owned()));
    }
    {
        let mut next = lock(&app.next_id);
        config.id = (*next).max(1);
        *next = config.id + 1;
    }
    let account = open_account(&app.data_dir, config).map_err(|e| (500, e))?;
    if !body.dir.trim().is_empty() {
        *lock(&app.dir) = body.dir.trim().to_owned();
    }
    lock(&app.accounts).push(account);
    *lock(&app.catalog) = None;
    *lock(&app.failure) = None;
    app.apply_dir().map_err(|e| (500, e))?;
    app.persist().map_err(bad)?;
    Ok(Reply::Json(json!({"ok": true})))
}

fn remove_account(app: &Arc<App>, id: u32) -> Res {
    let account = find_account(app, id)?;
    if lock(&account.active).is_some() {
        return Err((409, "Ce compte enregistre en ce moment : arrête d'abord l'enregistrement.".to_owned()));
    }
    // Les programmations de ce compte sont annulées, les fichiers restent.
    let _ = lock(&account.rec).cancel_all_scheduled();
    lock(&app.accounts).retain(|a| a.config.id != id);
    *lock(&app.catalog) = None;
    app.persist().map_err(bad)?;
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
    let client = app.first_client()?;
    let programmes = app.rt.block_on(client.short_epg_limit(id, GUIDE_LIMIT)).map_err(bad)?;
    let list: Vec<Value> = programmes
        .iter()
        .map(|p| json!({"title": p.title, "description": p.description, "start": p.start, "stop": p.stop}))
        .collect();
    Ok(Reply::Json(json!({"programmes": list})))
}

/// Compte qui prendra l'enregistrement : celui dont l'enregistrement de la
/// même chaîne se prolonge (programmes qui se suivent), sinon le premier qui
/// n'est pas déjà pris à ce moment-là.
fn pick(app: &Arc<App>, request: &ScheduleRequest) -> Result<(Arc<Account>, SchedulePreview), (u16, String)> {
    let space = volume_space(&app.base_dir())
        .ok_or((400, "Le dossier des enregistrements est introuvable.".to_owned()))?;
    let accounts = app.accounts();
    if accounts.is_empty() {
        return Err((400, "Ajoute d'abord un compte dans Réglages.".to_owned()));
    }
    let mut chosen: Option<(Arc<Account>, SchedulePreview)> = None;
    let mut first_error: Option<xtream_core::Error> = None;
    for account in &accounts {
        match lock(&account.rec).preview(request, space, now()) {
            Ok(preview) => {
                let merged = preview.merged_into.is_some();
                if chosen.is_none() || merged {
                    chosen = Some((account.clone(), preview));
                }
                if merged {
                    break;
                }
            }
            Err(e) => {
                first_error.get_or_insert(e);
            }
        }
    }
    match (chosen, first_error) {
        (Some(found), _) => Ok(found),
        (None, Some(e)) if accounts.len() > 1 => Err((
            409,
            format!("{e} (sur chacun des {} comptes : ajoute un compte pour enregistrer plus de chaînes à la fois)", accounts.len()),
        )),
        (None, Some(e)) => Err(bad(e)),
        (None, None) => Err((500, "aucun compte".to_owned())),
    }
}

fn preview(app: &Arc<App>, body: ScheduleBody) -> Res {
    let request = schedule_request(body);
    let (account, p) = pick(app, &request)?;
    Ok(Reply::Json(json!({
        "account": account.label(),
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
    let (account, _) = pick(app, &request)?;
    // Les marges retenues ne servent qu'au guide de l'appli Android : ici la
    // page les choisit à chaque fois.
    let id = lock(&account.rec).schedule_manual(&request, now()).map_err(bad)?;
    Ok(Reply::Json(json!({"id": id, "account": account.label()})))
}

fn recordings(app: &Arc<App>) -> Res {
    let mut scheduled = Vec::new();
    let mut done = Vec::new();
    for account in app.accounts() {
        let rec = lock(&account.rec);
        scheduled.extend(rec.scheduled(true).map_err(bad)?.iter().map(|r| (r.start, recording_json(&account, r))));
        done.extend(rec.recorded(true).map_err(bad)?.iter().map(|r| (r.start, recording_json(&account, r))));
    }
    scheduled.sort_by_key(|(start, _)| *start);
    done.sort_by_key(|(start, _)| std::cmp::Reverse(*start));
    Ok(Reply::Json(json!({
        "scheduled": scheduled.into_iter().map(|(_, v)| v).collect::<Vec<_>>(),
        "recorded": done.into_iter().map(|(_, v)| v).collect::<Vec<_>>(),
    })))
}

fn cancel(app: &Arc<App>, target: RecordingRef) -> Res {
    let account = find_account(app, target.account)?;
    let control = lock(&account.active)
        .as_ref()
        .filter(|a| a.id == target.id)
        .map(|a| a.control.clone());
    match control {
        Some(control) => control.stop(),
        None => lock(&account.rec).cancel(target.id).map_err(bad)?,
    }
    Ok(Reply::Json(json!({"ok": true})))
}

fn delete(app: &Arc<App>, target: RecordingRef) -> Res {
    let account = find_account(app, target.account)?;
    lock(&account.rec).delete(target.id).map_err(bad)?;
    let _ = std::fs::remove_file(app.index_path(account.config.id, target.id));
    Ok(Reply::Json(json!({"ok": true})))
}

/// `<compte>/<id>` : l'enregistrement demandé.
fn find_recording(app: &Arc<App>, path: &str) -> Result<(Arc<Account>, Recording), (u16, String)> {
    let not_found = || -> (u16, String) { (404, "introuvable".to_owned()) };
    let (account, id) = path.split_once('/').ok_or_else(not_found)?;
    let account: u32 = account.parse().map_err(|_| not_found())?;
    let id: i64 = id.parse().map_err(|_| not_found())?;
    let account = find_account(app, account)?;
    let recording = lock(&account.rec)
        .recording(id)
        .map_err(bad)?
        .ok_or((404, "Cet enregistrement n'existe plus.".to_owned()))?;
    Ok((account, recording))
}

fn file(app: &Arc<App>, path: &str, query: &str, range: Option<String>) -> Res {
    let (account, recording) = find_recording(app, path)?;
    let download = (query_param(query, "dl").as_deref() == Some("1"))
        .then(|| format!("enregistrement-{}-{}.ts", account.config.id, recording.id));
    Ok(Reply::File {
        path: recording.path,
        download,
        range,
    })
}

/// `<compte>/<id>.m3u8` : liste de lecture HLS de l'enregistrement. La première fois,
/// le fichier est lu en entier pour en tirer l'index (quelques secondes).
fn playlist(app: &Arc<App>, path: &str) -> Res {
    let path = path
        .strip_suffix(".m3u8")
        .ok_or((404, "introuvable".to_owned()))?;
    let (account, recording) = find_recording(app, path)?;
    let index = app.playback_index(account.config.id, &recording)?;
    let media = format!("/files/{}/{}", account.config.id, recording.id);
    Ok(Reply::Playlist(index.playlist(&media)))
}

fn header(name: &str, value: &str) -> Header {
    Header::from_bytes(name.as_bytes(), value.as_bytes()).expect("en-tête valide")
}

/// Réponse d'erreur : `{"error": "…"}`.
fn error_response(code: u16, message: &str) -> Response<std::io::Cursor<Vec<u8>>> {
    Response::from_string(json!({"error": message}).to_string())
        .with_status_code(code)
        .with_header(header("Content-Type", "application/json; charset=utf-8"))
}

/// Envoie un fichier, en entier ou seulement la plage demandée (le lecteur vidéo
/// s'en sert pour se déplacer dans l'enregistrement).
fn send_file(
    request: Request,
    path: &Path,
    download: Option<String>,
    range: Option<&str>,
) -> std::io::Result<()> {
    let Ok(mut file) = File::open(path) else {
        return request.respond(error_response(404, "Fichier introuvable (disque débranché ?)."));
    };
    let Ok(len) = file.metadata().map(|m| m.len()) else {
        return request.respond(error_response(500, "Fichier illisible."));
    };
    let mut headers = vec![
        header("Content-Type", "video/mp2t"),
        header("Accept-Ranges", "bytes"),
    ];
    if let Some(name) = download {
        headers.push(header(
            "Content-Disposition",
            &format!("attachment; filename=\"{name}\""),
        ));
    }
    match range::parse(range, len) {
        ByteRange::Whole => request.respond(Response::new(
            StatusCode(200),
            headers,
            file,
            usize::try_from(len).ok(),
            None,
        )),
        ByteRange::Partial { start, end } => {
            if file.seek(SeekFrom::Start(start)).is_err() {
                return request.respond(error_response(500, "Fichier illisible."));
            }
            let count = end - start + 1;
            headers.push(header("Content-Range", &format!("bytes {start}-{end}/{len}")));
            request.respond(Response::new(
                StatusCode(206),
                headers,
                file.take(count),
                usize::try_from(count).ok(),
                None,
            ))
        }
        ByteRange::Unsatisfiable => request.respond(
            Response::new_empty(StatusCode(416))
                .with_header(header("Content-Range", &format!("bytes */{len}"))),
        ),
    }
}

fn handle(app: Arc<App>, mut request: Request) {
    let mut body = String::new();
    if *request.method() == Method::Post {
        let _ = request.as_reader().read_to_string(&mut body);
    }
    let method = request.method().clone();
    let url = request.url().to_owned();
    let range = request
        .headers()
        .iter()
        .find(|h| h.field.equiv("Range"))
        .map(|h| h.value.as_str().to_owned());
    let result = route(&app, &method, &url, &body, range);
    let _ = match result {
        Ok(Reply::Json(value)) => request.respond(
            Response::from_string(value.to_string()).with_header(header("Content-Type", "application/json; charset=utf-8")),
        ),
        Ok(Reply::Html) => request.respond(
            Response::from_string(PAGE).with_header(header("Content-Type", "text/html; charset=utf-8")),
        ),
        Ok(Reply::Playlist(text)) => request.respond(
            Response::from_string(text)
                .with_header(header("Content-Type", "application/vnd.apple.mpegurl"))
                .with_header(header("Cache-Control", "no-store")),
        ),
        Ok(Reply::File { path, download, range }) => {
            send_file(request, &path, download, range.as_deref())
        }
        Err((code, message)) => request.respond(error_response(code, &message)),
    };
}

// --- Planificateur ----------------------------------------------------------

/// Toutes les 3 s, pour chaque compte : prolonge ou raccourcit sa copie en
/// cours, ou démarre l'enregistrement dû. Une copie à la fois par compte.
fn tick(app: &Arc<App>) {
    for account in app.accounts() {
        tick_account(app, &account);
    }
}

fn tick_account(app: &Arc<App>, account: &Arc<Account>) {
    let now = now();
    let running = lock(&account.active).as_ref().map(|a| (a.id, a.control.clone()));
    if let Some((id, control)) = running {
        let mut rec = lock(&account.rec);
        if let Ok(Some(due)) = rec.due(now)
            && due.id == id
        {
            control.set_until(due.stop);
        }
        let _ = rec.update_progress(id, control.bytes(), now);
        return;
    }
    let due = match lock(&account.rec).due(now) {
        Ok(Some(due)) => due,
        _ => return,
    };
    if app.apply_dir().is_err() {
        return;
    }
    let space = volume_space(&app.base_dir());
    let job = match lock(&account.rec).begin(due.id, space, now) {
        Ok(job) => job,
        Err(e) => {
            eprintln!("Enregistrement {} ({}) impossible : {e}", due.id, account.label());
            return;
        }
    };
    let control = CaptureControl::default();
    *lock(&account.active) = Some(Active {
        id: job.recording_id,
        control: control.clone(),
    });
    println!("Enregistrement {} démarré ({}).", job.recording_id, account.label());
    let app = app.clone();
    let account = account.clone();
    std::thread::spawn(move || {
        let report = app
            .rt
            .block_on(capture(&account.client, &job, &control, &CaptureOptions::default()));
        let finished = lock(&account.rec).finish(job.recording_id, &report, self::now());
        match &finished {
            Ok(r) => println!("Enregistrement {} terminé ({:?}).", r.id, r.state),
            Err(e) => eprintln!("Bilan de l'enregistrement {} perdu : {e}", job.recording_id),
        }
        *lock(&account.active) = None;
        // Index de lecture calculé tout de suite : la première lecture est alors immédiate.
        if let Ok(r) = finished
            && r.bytes > 0
        {
            let _ = app.playback_index(account.config.id, &r);
        }
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
    let rt = match tokio::runtime::Builder::new_multi_thread().enable_all().build() {
        Ok(rt) => rt,
        Err(e) => {
            eprintln!("Moteur asynchrone impossible à démarrer : {e}");
            return;
        }
    };
    let mut accounts = Vec::new();
    for account in config.accounts.iter().cloned() {
        match open_account(&options.data_dir, account) {
            Ok(account) => accounts.push(account),
            Err(e) => eprintln!("Compte ignoré : {e}"),
        }
    }
    let app = Arc::new(App {
        data_dir: options.data_dir.clone(),
        dir: Mutex::new(config.dir),
        next_id: Mutex::new(config.next_id.max(1)),
        accounts: Mutex::new(accounts),
        catalog: Mutex::new(None),
        failure: Mutex::new(None),
        index_lock: Mutex::new(()),
        rt,
    });
    if let Err(e) = app.apply_dir() {
        eprintln!("{e}");
    }
    // Enregistrements interrompus par un arrêt du programme.
    for account in app.accounts() {
        let _ = lock(&account.rec).recover(now());
    }

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
