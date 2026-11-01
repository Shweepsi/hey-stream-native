//! Différé sur le direct : tampon, index, séries, place, et enregistrement
//! qui suit le tampon (réponses simulées, jamais le panneau de référence).
//!
//! Chaque réponse simulée livre un morceau puis se ferme : chaque connexion
//! est donc une série, et l'attente entre deux essais un trou.

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};
use xtream_core::{
    CaptureControl, CaptureEnd, CaptureJob, CaptureOptions, CaptureReport, Credentials,
    GuideProgramme, Margins, RecordingState, Recordings, ScheduleRequest, TuneEnd, Tuner,
    TunerOptions, VolumeSpace, XtreamClient, purge_timeshift,
};

const GB: u64 = 1_000_000_000;
const ROOMY: VolumeSpace = VolumeSpace {
    total_bytes: 100 * GB,
    free_bytes: 80 * GB,
};
const PACKET: usize = 188;

fn temp_dir(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("hs-timeshift-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

fn now() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs_f64()
}

fn client(server: &MockServer) -> XtreamClient {
    XtreamClient::new(&Credentials {
        server_url: server.uri(),
        username: "u".into(),
        password: "p".into(),
    })
    .unwrap()
}

fn fast() -> TunerOptions {
    TunerOptions {
        first_retry: Duration::from_millis(20),
        max_retry: Duration::from_millis(40),
        stall: Duration::from_secs(2),
        segment_secs: 0.1,
        mark_secs: 0.01,
        space_check_bytes: 1,
        probe: Arc::new(|_| Some(ROOMY)),
        ..TunerOptions::default()
    }
}

fn capture_options() -> CaptureOptions {
    CaptureOptions {
        probe: Arc::new(|_| Some(ROOMY)),
        ..CaptureOptions::default()
    }
}

async fn stream(server: &MockServer, packets: usize) {
    Mock::given(method("GET"))
        .and(path("/live/u/p/42.ts"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(vec![0x47u8; PACKET * packets]))
        .mount(server)
        .await;
}

fn start(tuner: &Tuner, server: &MockServer) -> tokio::task::JoinHandle<TuneEnd> {
    let tuner = tuner.clone();
    let client = client(server);
    tokio::spawn(async move { tuner.run(&client).await })
}

async fn until(what: impl Fn() -> bool) {
    for _ in 0..500 {
        if what() {
            return;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    panic!("condition jamais atteinte");
}

fn files(dir: &Path) -> usize {
    std::fs::read_dir(dir).map_or(0, |d| d.count())
}

#[tokio::test(flavor = "multi_thread")]
async fn a_failed_first_connection_is_reported_at_once() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(403))
        .mount(&server)
        .await;
    let dir = temp_dir("refused");
    let tuner = Tuner::new(42, &dir, fast()).unwrap();
    let started = now();
    let end = start(&tuner, &server).await.unwrap();
    assert_eq!(end, TuneEnd::OpenFailed);
    assert!(now() - started < 1.0);
    assert_eq!(tuner.end(), Some(TuneEnd::OpenFailed));
    assert!(tuner.window().is_none());
}

#[tokio::test(flavor = "multi_thread")]
async fn the_buffer_indexes_runs_and_gaps() {
    let server = MockServer::start().await;
    stream(&server, 10).await;
    let dir = temp_dir("index");
    let tuner = Tuner::new(42, &dir, fast()).unwrap();
    let run = start(&tuner, &server);
    until(|| tuner.window().is_some_and(|w| w.gaps.len() >= 3)).await;

    let window = tuner.window().unwrap();
    assert!(window.oldest < window.newest);
    assert_eq!(window.bytes % PACKET as u64, 0);
    assert_eq!(window.bytes, tuner.bytes());
    // Chaque connexion est une série, séparée de la suivante par un trou.
    let first = tuner.locate(window.oldest).unwrap();
    let last = tuner.locate(window.newest + 100.0).unwrap();
    assert!(last.seq > first.seq);
    assert!(last.run > first.run);
    assert_eq!(first.offset % PACKET as u64, 0);
    let gap = window.gaps[1];
    assert!(gap.from < gap.to);
    // Un moment dans un trou : la lecture reprend juste avant le retour du flux.
    let inside = tuner.locate((gap.from + gap.to) / 2.0).unwrap();
    assert!(inside.at <= gap.from);
    // Un fichier par série au moins, lisible pendant la copie.
    let segment = tuner.segment(first.seq).unwrap();
    assert!(segment.complete);
    assert_eq!(
        std::fs::metadata(&segment.path).unwrap().len(),
        segment.bytes
    );
    assert!(tuner.time_at(first.seq, 0).unwrap() >= window.oldest);

    // Plus personne ne regarde : la copie s'arrête.
    tuner.set_viewing(false);
    assert_eq!(run.await.unwrap(), TuneEnd::Stopped);
    let folder = tuner.directory().to_path_buf();
    assert!(folder.exists());
    drop(tuner);
    assert!(
        !folder.exists(),
        "le tampon est effacé avec le dernier Tuner"
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn the_buffer_keeps_its_depth() {
    let server = MockServer::start().await;
    stream(&server, 10).await;
    let dir = temp_dir("depth");
    let options = TunerOptions {
        max_secs: 0.3,
        ..fast()
    };
    let tuner = Tuner::new(42, &dir, options).unwrap();
    let run = start(&tuner, &server);
    let started = now();
    until(|| now() - started > 1.0).await;
    let window = tuner.window().unwrap();
    // Profondeur plus un fichier au plus.
    assert!(window.newest - window.oldest < 0.3 + 0.3, "{window:?}");
    assert!(files(tuner.directory()) < 15);
    tuner.stop();
    assert_eq!(run.await.unwrap(), TuneEnd::Stopped);
}

#[tokio::test(flavor = "multi_thread")]
async fn the_buffer_gives_way_before_the_reserve() {
    let server = MockServer::start().await;
    stream(&server, 10).await;
    let dir = temp_dir("space");
    // Sous la réserve plus la marge : le tampon ne garde que le fichier en cours.
    let free = Arc::new(AtomicU64::new(1_200_000_000));
    let probe_free = free.clone();
    let options = TunerOptions {
        probe: Arc::new(move |_| {
            Some(VolumeSpace {
                total_bytes: 8 * GB,
                free_bytes: probe_free.load(Ordering::SeqCst),
            })
        }),
        ..fast()
    };
    let tuner = Tuner::new(42, &dir, options).unwrap();
    let run = start(&tuner, &server);
    until(|| tuner.locate(f64::MAX).is_some_and(|s| s.run >= 4)).await;
    assert!(files(tuner.directory()) <= 2);
    // Réserve atteinte : arrêt propre.
    free.store(900_000_000, Ordering::SeqCst);
    assert_eq!(run.await.unwrap(), TuneEnd::OutOfSpace);
}

fn job(dir: &Path, until: f64) -> CaptureJob {
    CaptureJob {
        recording_id: 1,
        stream_id: 42,
        path: dir.join("1.ts"),
        until: until.ceil() as i64,
    }
}

fn follow(
    tuner: &Tuner,
    from: i64,
    job: &CaptureJob,
    control: &CaptureControl,
) -> tokio::task::JoinHandle<CaptureReport> {
    // Inscrit tout de suite (pas dans la tâche) : la copie ne peut plus
    // s'arrêter avant que l'enregistrement la suive.
    let follower = tuner.attach(from).expect("copie en cours");
    let job = job.clone();
    let control = control.clone();
    tokio::spawn(async move { follower.copy(&job, &control, &capture_options()).await })
}

#[tokio::test(flavor = "multi_thread")]
async fn a_recording_takes_the_past_from_the_buffer_and_keeps_it_alive() {
    let server = MockServer::start().await;
    stream(&server, 10).await;
    let dir = temp_dir("follow");
    let tuner = Tuner::new(42, &dir, fast()).unwrap();
    let run = start(&tuner, &server);
    until(|| tuner.window().is_some_and(|w| w.gaps.len() >= 2)).await;
    let oldest = tuner.window().unwrap().oldest;
    let before = tuner.bytes();

    // Depuis avant le tampon : tout ce qu'il contient, puis la suite.
    let job = job(&dir, now() + 1.0);
    let control = CaptureControl::new();
    let follower = follow(&tuner, oldest as i64 - 60, &job, &control);
    // Le spectateur s'en va : l'enregistrement garde la copie en vie.
    tuner.set_viewing(false);
    let report = follower.await.unwrap();
    assert_eq!(report.end, CaptureEnd::Finished);
    let size = std::fs::metadata(&job.path).unwrap().len();
    assert_eq!(report.bytes, size);
    assert_eq!(size % PACKET as u64, 0);
    assert!(size > before, "le passé ({before}) puis la suite ({size})");
    // Chaque reconnexion simulée est une coupure.
    assert!(report.gap_secs + report.received_secs >= 1);
    assert_eq!(control.bytes(), size);
    // Enregistrement fini, plus de spectateur : la copie s'arrête.
    assert_eq!(run.await.unwrap(), TuneEnd::Stopped);
}

#[tokio::test(flavor = "multi_thread")]
async fn a_recording_can_wait_for_the_first_data() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/live/u/p/42.ts"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_bytes(vec![0x47u8; PACKET * 10])
                .set_delay(Duration::from_millis(300)),
        )
        .mount(&server)
        .await;
    let dir = temp_dir("first-data");
    let tuner = Tuner::new(42, &dir, fast()).unwrap();
    let run = start(&tuner, &server);
    // Rien encore dans le tampon.
    assert!(tuner.window().is_none());
    let job = job(&dir, now() + 1.0);
    let follower = follow(&tuner, 0, &job, &CaptureControl::new());
    tuner.set_viewing(false);
    let report = follower.await.unwrap();
    assert_eq!(report.end, CaptureEnd::Finished);
    assert!(report.bytes > 0);
    assert_eq!(run.await.unwrap(), TuneEnd::Stopped);
}

#[tokio::test(flavor = "multi_thread")]
async fn stopping_the_tuner_leaves_its_recording_incomplete() {
    let server = MockServer::start().await;
    stream(&server, 10).await;
    let dir = temp_dir("preempt");
    let tuner = Tuner::new(42, &dir, fast()).unwrap();
    let run = start(&tuner, &server);
    until(|| tuner.window().is_some()).await;
    let job = job(&dir, now() + 60.0);
    let follower = follow(&tuner, 0, &job, &CaptureControl::new());
    until(|| std::fs::metadata(&job.path).is_ok_and(|m| m.len() > 0)).await;
    tuner.stop();
    let report = follower.await.unwrap();
    // Pas un arrêt à la main : l'enregistrement sera incomplet.
    assert_eq!(report.end, CaptureEnd::Preempted);
    assert!(report.bytes > 0);
    assert_eq!(run.await.unwrap(), TuneEnd::Stopped);
    // Une copie arrêtée ne prend plus d'enregistrement.
    assert!(tuner.attach(0).is_none());
}

#[tokio::test(flavor = "multi_thread")]
async fn stop_during_an_outage_ends_the_recording_at_once() {
    let server = MockServer::start().await;
    // Une seule réponse, puis le fournisseur refuse : coupure sans fin.
    Mock::given(method("GET"))
        .and(path("/live/u/p/42.ts"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(vec![0x47u8; PACKET * 10]))
        .up_to_n_times(1)
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(503))
        .mount(&server)
        .await;
    let dir = temp_dir("outage");
    let tuner = Tuner::new(42, &dir, fast()).unwrap();
    let run = start(&tuner, &server);
    until(|| tuner.window().is_some()).await;
    let job = job(&dir, now() + 60.0);
    let control = CaptureControl::new();
    let follower = follow(&tuner, 0, &job, &control);
    tuner.set_viewing(false);
    until(|| control.bytes() == (PACKET * 10) as u64).await;
    // Toujours en coupure : la copie réessaie pour l'enregistrement.
    tokio::time::sleep(Duration::from_millis(200)).await;
    assert!(!run.is_finished());
    let stopped_at = now();
    control.stop();
    let report = follower.await.unwrap();
    assert_eq!(report.end, CaptureEnd::Stopped);
    assert!(now() - stopped_at < 1.0);
    assert!(report.gap_secs >= 0 && report.received_secs >= 0);
    // Plus personne : la copie s'arrête aussi.
    assert_eq!(run.await.unwrap(), TuneEnd::Stopped);
}

/// Flux de paquets TS numérotés : l'octet de synchronisation 0x47 puis le
/// numéro du paquet, pour vérifier alignement et continuité.
fn packets(count: usize) -> Vec<u8> {
    let mut body = vec![0u8; PACKET * count];
    for (i, packet) in body.chunks_mut(PACKET).enumerate() {
        packet[0] = 0x47;
        packet[1..5].copy_from_slice(&(i as u32).to_be_bytes());
    }
    body
}

#[tokio::test(flavor = "multi_thread")]
async fn a_run_over_several_files_stays_aligned_and_continuous() {
    let server = MockServer::start().await;
    let body = packets(20_000);
    Mock::given(method("GET"))
        .and(path("/live/u/p/42.ts"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(body.clone()))
        .up_to_n_times(1)
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(503))
        .mount(&server)
        .await;
    let dir = temp_dir("aligned");
    // Un fichier par morceau reçu, un point d'index par morceau.
    let options = TunerOptions {
        segment_secs: 0.0,
        mark_secs: 0.0,
        ..fast()
    };
    let tuner = Tuner::new(42, &dir, options).unwrap();
    let run = start(&tuner, &server);
    until(|| tuner.bytes() == body.len() as u64).await;
    let window = tuner.window().unwrap();
    let first = tuner.locate(window.oldest).unwrap();
    let last = tuner.locate(f64::MAX).unwrap();
    assert!(last.seq > first.seq, "plusieurs fichiers dans la série");
    assert_eq!(first.run, last.run);
    // Chaque fichier : son premier point d'index tombe sur un début de paquet.
    for seq in first.seq..=last.seq {
        let segment = tuner.segment(seq).unwrap();
        let spot = tuner.locate(segment.start).unwrap();
        assert_eq!(spot.seq, seq);
        let bytes = std::fs::read(&segment.path).unwrap();
        if (spot.offset as usize) < bytes.len() {
            assert_eq!(bytes[spot.offset as usize], 0x47, "fichier {seq}");
        }
        let at = tuner.time_at(seq, spot.offset).unwrap();
        assert!(at >= segment.start && at <= segment.end);
    }

    // Un enregistrement depuis le début traverse les fichiers sans rien perdre.
    let job = job(&dir, now() + 0.5);
    let follower = follow(&tuner, 0, &job, &CaptureControl::new());
    let report = follower.await.unwrap();
    assert_eq!(report.end, CaptureEnd::Finished);
    assert_eq!(std::fs::read(&job.path).unwrap(), body);
    tuner.stop();
    assert_eq!(run.await.unwrap(), TuneEnd::Stopped);
}

#[tokio::test(flavor = "multi_thread")]
async fn a_lagging_recording_keeps_its_files_without_a_viewer() {
    let server = MockServer::start().await;
    stream(&server, 10).await;
    let dir = temp_dir("pin");
    let tuner = Tuner::new(42, &dir, fast()).unwrap();
    let run = start(&tuner, &server);
    until(|| tuner.window().is_some_and(|w| w.gaps.len() >= 3)).await;
    let oldest = tuner.locate(0.0).unwrap();
    // Inscrit mais pas encore parti : rien de ce qu'il doit copier ne part.
    let follower = tuner.attach(0).unwrap();
    tuner.set_viewing(false);
    tokio::time::sleep(Duration::from_millis(300)).await;
    assert_eq!(tuner.locate(0.0).unwrap().seq, oldest.seq);
    assert!(oldest.path.exists());
    assert!(!run.is_finished(), "l'enregistrement garde la copie en vie");
    drop(follower);
    assert_eq!(run.await.unwrap(), TuneEnd::Stopped);
}

#[tokio::test(flavor = "multi_thread")]
async fn a_following_recording_stops_at_the_reserve() {
    let server = MockServer::start().await;
    stream(&server, 10).await;
    let dir = temp_dir("follow-space");
    let tuner = Tuner::new(42, &dir, fast()).unwrap();
    let run = start(&tuner, &server);
    until(|| tuner.window().is_some()).await;
    let job = job(&dir, now() + 60.0);
    let follower = tuner.attach(0).unwrap();
    let full = CaptureOptions {
        probe: Arc::new(|_| {
            Some(VolumeSpace {
                total_bytes: 8 * GB,
                free_bytes: GB / 2,
            })
        }),
        ..CaptureOptions::default()
    };
    let report = follower.copy(&job, &CaptureControl::new(), &full).await;
    assert_eq!(report.end, CaptureEnd::OutOfSpace);
    tuner.stop();
    assert_eq!(run.await.unwrap(), TuneEnd::Stopped);
}

#[test]
fn a_recording_can_start_in_the_buffered_past() {
    let mut rec = Recordings::open_in_memory().unwrap();
    rec.set_directory(&temp_dir("schedule")).unwrap();
    let request = |buffered_from| ScheduleRequest {
        stream_id: 42,
        channel: "Chaîne".into(),
        is_adult: false,
        programme: GuideProgramme {
            title: "Match".into(),
            description: None,
            start: 10_000,
            stop: 13_600,
        },
        margins: Margins {
            before_secs: 120,
            after_secs: 300,
        },
        buffered_from,
    };
    // Sans différé : tout de suite.
    let preview = rec.preview(&request(None), ROOMY, 11_000).unwrap();
    assert_eq!(preview.start, 11_000);
    // Le différé remonte à 10 500 : l'enregistrement aussi.
    let preview = rec.preview(&request(Some(10_500)), ROOMY, 11_000).unwrap();
    assert_eq!(preview.start, 10_500);
    // Le différé remonte plus loin que la marge : la marge gagne.
    let preview = rec.preview(&request(Some(5_000)), ROOMY, 11_000).unwrap();
    assert_eq!(preview.start, 10_000 - 120);

    let id = rec.schedule(&request(Some(10_500)), 11_000).unwrap();
    let job = rec
        .begin_from(id, Some(ROOMY), 11_000, Some(10_500))
        .unwrap();
    assert_eq!(job.until, 13_900);
    let recording = rec.recording(id).unwrap().unwrap();
    assert_eq!(recording.state, RecordingState::Recording);
    // Rien de perdu : le tampon couvre tout depuis le début réel.
    assert_eq!(recording.gap_secs, 0);
}

#[test]
fn a_missing_start_counts_as_a_gap() {
    let mut rec = Recordings::open_in_memory().unwrap();
    rec.set_directory(&temp_dir("late")).unwrap();
    let request = ScheduleRequest {
        stream_id: 42,
        channel: "Chaîne".into(),
        is_adult: false,
        programme: GuideProgramme {
            title: "Match".into(),
            description: None,
            start: 11_000,
            stop: 13_600,
        },
        margins: Margins {
            before_secs: 120,
            after_secs: 300,
        },
        buffered_from: None,
    };
    let id = rec.schedule(&request, 10_000).unwrap();
    // Programmé à l'avance ; on regardait depuis 11 200 seulement.
    let job = rec
        .begin_from(id, Some(ROOMY), 11_500, Some(11_200))
        .unwrap();
    assert_eq!(job.until, 13_900);
    let recording = rec.recording(id).unwrap().unwrap();
    assert_eq!(recording.gap_secs, 11_200 - (11_000 - 120));
}

#[test]
fn leftover_buffers_are_purged() {
    let dir = temp_dir("purge");
    let tuner = Tuner::new(42, &dir, TunerOptions::default()).unwrap();
    let folder = tuner.directory().to_path_buf();
    std::mem::forget(tuner);
    std::fs::write(folder.join("00000000.ts"), [0x47u8; 188]).unwrap();
    std::fs::write(dir.join("1.ts"), [0x47u8; 188]).unwrap();
    purge_timeshift(&dir);
    assert!(!folder.exists());
    assert!(dir.join("1.ts").exists(), "les enregistrements restent");
}
