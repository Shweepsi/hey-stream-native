//! Enregistrement programmé : planning, espace, cycle de vie et copie du flux
//! (réponses simulées, jamais le panneau de référence).

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};
use xtream_core::{
    CaptureControl, CaptureEnd, CaptureJob, CaptureOptions, CaptureReport, Credentials, Error,
    GuideProgramme, Margins, RecordingProblem, RecordingState, Recordings, ScheduleRequest,
    VolumeSpace, XtreamClient, capture,
};

const GB: u64 = 1_000_000_000;
const HOUR: i64 = 3600;
/// Disque large : rien ne manque.
const ROOMY: VolumeSpace = VolumeSpace {
    total_bytes: 100 * GB,
    free_bytes: 80 * GB,
};

fn temp_dir(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("hs-dvr-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

fn store(dir: &Path) -> Recordings {
    let recordings = Recordings::open_in_memory().unwrap();
    recordings.set_directory(dir).unwrap();
    recordings
}

fn request(stream_id: i64, title: &str, start: i64, stop: i64) -> ScheduleRequest {
    ScheduleRequest {
        stream_id,
        channel: format!("Chaîne {stream_id}"),
        is_adult: false,
        programme: GuideProgramme {
            title: title.into(),
            description: None,
            start,
            stop,
        },
        margins: Margins {
            before_secs: 120,
            after_secs: 300,
        },
    }
}

fn report(end: CaptureEnd, bytes: u64, gap_secs: i64) -> CaptureReport {
    CaptureReport {
        end,
        bytes,
        written: bytes,
        gap_secs,
        received_secs: HOUR,
    }
}

// --- Programmation -----------------------------------------------------------

#[test]
fn a_programme_is_scheduled_with_its_margins() {
    let dir = temp_dir("schedule");
    let mut rec = store(&dir);
    let id = rec.schedule(&request(1, "JT", 10_000, 12_000), 0).unwrap();
    let r = rec.recording(id).unwrap().unwrap();
    assert_eq!((r.start, r.stop), (9_880, 12_300));
    assert_eq!(r.state, RecordingState::Scheduled);
    assert_eq!(r.programmes[0].title, "JT");
    assert_eq!(r.path, dir.join(format!("{id}.ts")));
    assert!(!r.volume_missing);
    assert_eq!(rec.next_start(0).unwrap(), Some(9_880));
    assert_eq!(rec.due(9_879).unwrap(), None);
    let due = rec.due(9_880).unwrap().unwrap();
    assert_eq!((due.id, due.stream_id, due.stop), (id, 1, 12_300));
    // Marges retenues pour la prochaine fois.
    assert_eq!(
        rec.last_margins().unwrap(),
        Margins {
            before_secs: 120,
            after_secs: 300
        }
    );
}

#[test]
fn default_margins_are_two_and_five_minutes() {
    let rec = Recordings::open_in_memory().unwrap();
    assert_eq!(rec.last_margins().unwrap(), xtream_core::DEFAULT_MARGINS);
}

#[test]
fn no_disk_chosen_means_no_recording() {
    let mut rec = Recordings::open_in_memory().unwrap();
    assert!(matches!(
        rec.schedule(&request(1, "JT", 10_000, 12_000), 0),
        Err(Error::VolumeMissing)
    ));
}

#[test]
fn ended_programmes_are_refused_and_running_ones_start_now() {
    let dir = temp_dir("ended");
    let mut rec = store(&dir);
    assert!(matches!(
        rec.schedule(&request(1, "Fini", 1_000, 2_000), 2_000),
        Err(Error::RecordingEnded)
    ));
    let id = rec
        .schedule(&request(1, "En cours", 1_000, 5_000), 3_000)
        .unwrap();
    let due = rec.due(3_000).unwrap().unwrap();
    assert_eq!((due.id, due.start, due.stop), (id, 3_000, 5_300));
}

#[test]
fn consecutive_programmes_on_one_channel_are_merged() {
    let dir = temp_dir("merge");
    let mut rec = store(&dir);
    let first = rec
        .schedule(&request(1, "Film", 10_000, 17_200), 0)
        .unwrap();
    let preview = rec
        .preview(&request(1, "Débat", 17_200, 20_000), ROOMY, 0)
        .unwrap();
    assert_eq!(preview.merged_into, Some(first));
    assert_eq!((preview.start, preview.stop), (9_880, 20_300));
    let second = rec
        .schedule(&request(1, "Débat", 17_200, 20_000), 0)
        .unwrap();
    assert_eq!(first, second);
    let r = rec.recording(first).unwrap().unwrap();
    let titles: Vec<&str> = r.programmes.iter().map(|p| p.title.as_str()).collect();
    assert_eq!(titles, ["Film", "Débat"]);
    assert_eq!((r.start, r.stop), (9_880, 20_300));
    assert_eq!(rec.scheduled(false).unwrap().len(), 1);

    // Un programme qui comble le trou entre deux enregistrements les réunit.
    let far = rec
        .schedule(&request(1, "Nuit", 30_000, 32_000), 0)
        .unwrap();
    assert_ne!(far, first);
    let gap = rec
        .schedule(&request(1, "Soir", 20_000, 30_000), 0)
        .unwrap();
    assert_eq!(gap, first);
    let r = rec.recording(first).unwrap().unwrap();
    assert_eq!(r.programmes.len(), 4);
    assert_eq!((r.start, r.stop), (9_880, 32_300));
    assert!(rec.recording(far).unwrap().is_none());
}

#[test]
fn overlapping_programmes_on_two_channels_are_refused() {
    let dir = temp_dir("conflict");
    let mut rec = store(&dir);
    let first = rec
        .schedule(&request(1, "Match", 10_000, 17_000), 0)
        .unwrap();
    match rec.schedule(&request(2, "Film", 16_000, 20_000), 0) {
        Err(Error::RecordingConflict { recording_id }) => assert_eq!(recording_id, first),
        other => panic!("conflit attendu : {other:?}"),
    }
    assert!(matches!(
        rec.preview(&request(2, "Film", 16_000, 20_000), ROOMY, 0),
        Err(Error::RecordingConflict { .. })
    ));
}

#[test]
fn back_to_back_programmes_on_two_channels_share_the_margins() {
    let dir = temp_dir("shorten");
    let mut rec = store(&dir);
    let first = rec
        .schedule(&request(1, "Match", 10_000, 17_000), 0)
        .unwrap();
    let preview = rec
        .preview(&request(2, "Film", 17_000, 20_000), ROOMY, 0)
        .unwrap();
    assert!(preview.margins_shortened);
    assert_eq!(preview.merged_into, None);
    assert_eq!((preview.start, preview.stop), (17_000, 20_300));
    let second = rec
        .schedule(&request(2, "Film", 17_000, 20_000), 0)
        .unwrap();
    assert_eq!(rec.due(16_999).unwrap().unwrap().id, first);
    assert_eq!(rec.due(16_999).unwrap().unwrap().stop, 17_000);
    assert_eq!(rec.due(17_000).unwrap().unwrap().id, second);
    // Annuler le second rend ses marges au premier.
    rec.cancel(second).unwrap();
    assert_eq!(rec.due(17_200).unwrap().unwrap().id, first);
    assert!(matches!(rec.cancel(second), Err(Error::RecordingNotFound)));
}

#[test]
fn scheduling_during_a_copy_moves_its_end() {
    let dir = temp_dir("running");
    let mut rec = store(&dir);
    let a = rec
        .schedule(&request(1, "Match", 10_000, 17_000), 0)
        .unwrap();
    let job = rec.begin(a, Some(ROOMY), 10_000).unwrap();
    assert_eq!(job.until, 17_300);
    // Programme suivant sur la même chaîne : la copie en cours continue.
    assert_eq!(
        rec.schedule(&request(1, "Après-match", 17_000, 18_000), 12_000)
            .unwrap(),
        a
    );
    assert_eq!(rec.due(12_000).unwrap().unwrap().stop, 18_300);
    // Autre chaîne juste après : la marge de fin est raccourcie.
    rec.schedule(&request(2, "Film", 18_000, 20_000), 12_000)
        .unwrap();
    let due = rec.due(12_000).unwrap().unwrap();
    assert_eq!((due.id, due.start, due.stop), (a, 9_880, 18_000));
}

#[test]
fn no_merge_around_another_channel() {
    let dir = temp_dir("between");
    let mut rec = store(&dir);
    let a = rec.schedule(&request(1, "A", 10_000, 11_000), 0).unwrap();
    let b = rec
        .schedule(&request(2, "Flash", 11_000, 11_180), 0)
        .unwrap();
    // Ses marges touchent A, mais le flash de la chaîne 2 est entre les deux.
    let c = rec.schedule(&request(1, "C", 11_240, 13_000), 0).unwrap();
    assert!(a != c && b != c);
    let window = |at| {
        let d = rec.due(at).unwrap().unwrap();
        (d.id, d.start, d.stop)
    };
    assert_eq!(window(10_999), (a, 9_880, 11_000));
    assert_eq!(window(11_000), (b, 11_000, 11_210));
    assert_eq!(window(11_210), (c, 11_210, 13_300));
}

// --- Espace ------------------------------------------------------------------

#[test]
fn missing_space_is_announced_before_scheduling() {
    let dir = temp_dir("space");
    let mut rec = store(&dir);
    // 1 h + 7 min de marges à 3 Go/h : 3,35 Go ; 2 Go utilisables.
    let space = VolumeSpace {
        total_bytes: 8 * GB,
        free_bytes: 3 * GB,
    };
    let preview = rec
        .preview(&request(1, "Film", 10_000, 10_000 + HOUR), space, 0)
        .unwrap();
    assert_eq!(preview.estimated_bytes, 3_350_000_000);
    assert_eq!(preview.missing_bytes, 1_350_000_000);
    assert!(rec.scheduled(true).unwrap().is_empty(), "aperçu sans effet");
    // Programmer quand même reste possible ; le suivant compte le premier.
    rec.schedule(&request(1, "Film", 10_000, 10_000 + HOUR), 0)
        .unwrap();
    let preview = rec
        .preview(&request(2, "Autre", 20_000, 20_000 + HOUR), space, 0)
        .unwrap();
    assert_eq!(preview.missing_bytes, 4_700_000_000);
    let roomy = rec
        .preview(&request(2, "Autre", 20_000, 20_000 + HOUR), ROOMY, 0)
        .unwrap();
    assert_eq!(roomy.missing_bytes, 0);
}

#[test]
fn the_estimate_learns_each_channel() {
    let dir = temp_dir("rate");
    let mut rec = store(&dir);
    let default = rec.bytes_per_sec(1).unwrap();
    assert!((default - 3e9 / 3600.0).abs() < 1.0);
    // Trop court : ignoré.
    rec.learn_rate(1, 1_000, 10.0).unwrap();
    assert_eq!(rec.bytes_per_sec(1).unwrap(), default);
    rec.learn_rate(1, 500_000 * 60, 60.0).unwrap();
    assert!((rec.bytes_per_sec(1).unwrap() - 500_000.0).abs() < 1.0);
    rec.learn_rate(1, 1_000_000 * 60, 60.0).unwrap();
    assert!((rec.bytes_per_sec(1).unwrap() - 750_000.0).abs() < 1.0);
    assert_eq!(rec.bytes_per_sec(2).unwrap(), default);
    let preview = rec
        .preview(&request(1, "Film", 10_000, 10_000 + HOUR), ROOMY, 0)
        .unwrap();
    assert_eq!(preview.estimated_bytes, 750_000 * (HOUR as u64 + 420));
}

#[test]
fn usage_and_cleanup_follow_the_files() {
    let dir = temp_dir("usage");
    let mut rec = store(&dir);
    let mut done = Vec::new();
    for (i, start) in [10_000, 20_000, 30_000].into_iter().enumerate() {
        let id = rec
            .schedule(&request(1, &format!("P{i}"), start, start + 1_000), 0)
            .unwrap();
        rec.begin(id, Some(ROOMY), start).unwrap();
        rec.finish(id, &report(CaptureEnd::Finished, GB, 0), start + 1_300)
            .unwrap();
        done.push(id);
    }
    rec.set_position(done[2], 950, 1_000).unwrap();
    assert!(rec.recording(done[2]).unwrap().unwrap().watched);
    rec.set_position(done[1], 500, 1_000).unwrap();
    assert!(!rec.recording(done[1]).unwrap().unwrap().watched);

    let usage = rec
        .usage(
            &dir,
            VolumeSpace {
                total_bytes: 8 * GB,
                free_bytes: 1_500_000_000,
            },
        )
        .unwrap();
    assert_eq!(usage.used_bytes, 3 * GB);
    // 0,5 Go au débit appris (1 Go/h) : 30 min.
    assert_eq!(usage.usable_bytes, GB / 2);
    assert_eq!(usage.remaining_secs, 1800);
    assert!(usage.low);
    assert!(!rec.usage(&dir, ROOMY).unwrap().low);

    // Déjà vus d'abord, puis les plus anciens, jusqu'à la place manquante.
    let ids = |list: Vec<xtream_core::Recording>| list.iter().map(|r| r.id).collect::<Vec<_>>();
    assert_eq!(
        ids(rec.cleanup_candidates(&dir, GB + 1).unwrap()),
        [done[2], done[0]]
    );
    assert!(rec.cleanup_candidates(&dir, 0).unwrap().is_empty());
}

// --- Cycle de vie ------------------------------------------------------------

#[test]
fn begin_checks_the_disk_again() {
    let dir = temp_dir("begin");
    let mut rec = store(&dir);
    let a = rec.schedule(&request(1, "A", 10_000, 11_000), 0).unwrap();
    let b = rec.schedule(&request(1, "B", 20_000, 21_000), 0).unwrap();
    assert!(matches!(
        rec.begin(a, None, 10_000),
        Err(Error::VolumeMissing)
    ));
    let a = rec.recording(a).unwrap().unwrap();
    assert_eq!(a.state, RecordingState::Failed);
    assert_eq!(a.problem, Some(RecordingProblem::VolumeMissing));
    let full = VolumeSpace {
        total_bytes: 8 * GB,
        free_bytes: GB,
    };
    assert!(matches!(
        rec.begin(b, Some(full), 20_000),
        Err(Error::NotEnoughSpace)
    ));
    assert_eq!(
        rec.recording(b).unwrap().unwrap().problem,
        Some(RecordingProblem::OutOfSpace)
    );
    // Pas dû : refusé.
    let c = rec.schedule(&request(1, "C", 30_000, 31_000), 0).unwrap();
    assert!(matches!(
        rec.begin(c, Some(ROOMY), 1_000),
        Err(Error::RecordingNotFound)
    ));
}

#[test]
fn the_capture_report_decides_the_final_state() {
    let dir = temp_dir("finish");
    let mut rec = store(&dir);
    let cases = [
        (
            report(CaptureEnd::Finished, GB, 30),
            RecordingState::Done,
            None,
        ),
        (
            report(CaptureEnd::Finished, GB, 61),
            RecordingState::Incomplete,
            Some(RecordingProblem::Gaps),
        ),
        (
            report(CaptureEnd::OutOfSpace, GB, 0),
            RecordingState::Incomplete,
            Some(RecordingProblem::OutOfSpace),
        ),
        (
            report(CaptureEnd::ConnectionLost, GB, 0),
            RecordingState::Incomplete,
            Some(RecordingProblem::ConnectionLost),
        ),
        (
            report(CaptureEnd::ConnectionLost, 0, 0),
            RecordingState::Failed,
            Some(RecordingProblem::ConnectionLost),
        ),
        (
            report(CaptureEnd::Stopped, GB, 600),
            RecordingState::Done,
            None,
        ),
    ];
    for (i, (report, state, problem)) in cases.into_iter().enumerate() {
        let start = 10_000 * (i as i64 + 1);
        let id = rec
            .schedule(&request(1, "P", start, start + 1_000), 0)
            .unwrap();
        let job = rec.begin(id, Some(ROOMY), start).unwrap();
        assert_eq!((job.recording_id, job.stream_id), (id, 1));
        assert_eq!(job.until, start + 1_300);
        assert_eq!(
            rec.scheduled(false).unwrap()[0].state,
            RecordingState::Recording
        );
        let r = rec.finish(id, &report, start + 1_300).unwrap();
        assert_eq!((r.state, r.problem), (state, problem), "cas {i}");
        assert_eq!(r.started_at, Some(start));
        assert_eq!(r.ended_at, Some(start + 1_300));
    }
    // Débit appris par l'enregistrement : 1 Go en 1 h.
    assert!((rec.bytes_per_sec(1).unwrap() - GB as f64 / 3600.0).abs() < 1.0);
}

#[test]
fn recover_flags_missed_and_interrupted_recordings() {
    let dir = temp_dir("recover");
    let mut rec = store(&dir);
    let missed = rec.schedule(&request(1, "A", 10_000, 11_000), 0).unwrap();
    let cut = rec.schedule(&request(1, "B", 20_000, 21_000), 0).unwrap();
    let resumed = rec.schedule(&request(1, "C", 30_000, 31_000), 0).unwrap();
    let later = rec.schedule(&request(1, "D", 40_000, 41_000), 0).unwrap();

    let job = rec.begin(cut, Some(ROOMY), 20_000).unwrap();
    std::fs::write(&job.path, b"donnees").unwrap();
    rec.update_progress(cut, 7, 20_500).unwrap();
    rec.begin(resumed, Some(ROOMY), 30_000).unwrap();
    rec.update_progress(resumed, 7, 30_100).unwrap();

    // L'app redémarre à 30 400 : C est encore dans sa fenêtre.
    let flagged = rec.recover(30_400).unwrap();
    let states: Vec<_> = flagged.iter().map(|r| (r.id, r.state, r.problem)).collect();
    assert_eq!(
        states,
        [
            (
                missed,
                RecordingState::Missed,
                Some(RecordingProblem::NotStarted)
            ),
            (
                cut,
                RecordingState::Incomplete,
                Some(RecordingProblem::Interrupted)
            ),
        ]
    );
    assert_eq!(rec.recording(cut).unwrap().unwrap().ended_at, Some(20_500));
    // C reprend dans le même fichier ; le temps app arrêtée est un trou.
    assert_eq!(rec.due(30_400).unwrap().unwrap().id, resumed);
    rec.begin(resumed, Some(ROOMY), 30_400).unwrap();
    assert_eq!(rec.recording(resumed).unwrap().unwrap().gap_secs, 300);
    let r = rec
        .finish(resumed, &report(CaptureEnd::Finished, GB, 0), 31_300)
        .unwrap();
    assert_eq!(r.problem, Some(RecordingProblem::Gaps));
    assert_eq!(r.started_at, Some(30_000));
    assert_eq!(
        rec.recording(later).unwrap().unwrap().state,
        RecordingState::Scheduled
    );
}

#[test]
fn signing_out_cancels_schedules_but_keeps_files() {
    let dir = temp_dir("signout");
    let mut rec = store(&dir);
    let done = rec.schedule(&request(1, "A", 10_000, 11_000), 0).unwrap();
    rec.begin(done, Some(ROOMY), 10_000).unwrap();
    rec.finish(done, &report(CaptureEnd::Finished, GB, 0), 11_300)
        .unwrap();
    rec.schedule(&request(1, "B", 20_000, 21_000), 0).unwrap();
    assert_eq!(rec.cancel_all_scheduled().unwrap(), 1);
    assert!(rec.scheduled(true).unwrap().is_empty());
    assert_eq!(rec.recorded(true).unwrap()[0].id, done);
}

#[test]
fn adult_recordings_stay_locked() {
    let dir = temp_dir("adult");
    let mut rec = store(&dir);
    let mut adult = request(1, "Nuit", 10_000, 11_000);
    adult.is_adult = true;
    let id = rec.schedule(&adult, 0).unwrap();
    rec.schedule(&request(2, "JT", 20_000, 21_000), 0).unwrap();
    assert_eq!(rec.scheduled(false).unwrap().len(), 1);
    assert_eq!(rec.scheduled(true).unwrap().len(), 2);
    rec.begin(id, Some(ROOMY), 10_000).unwrap();
    rec.finish(id, &report(CaptureEnd::Finished, GB, 0), 11_300)
        .unwrap();
    assert!(rec.recorded(false).unwrap().is_empty());
    assert!(rec.recorded(true).unwrap()[0].is_adult);
    // Une fusion avec un programme adulte rend tout l'enregistrement adulte.
    let mut late = request(2, "Film", 21_000, 22_000);
    late.is_adult = true;
    rec.schedule(&late, 0).unwrap();
    assert!(rec.scheduled(false).unwrap().is_empty());
}

#[test]
fn deleting_removes_the_file() {
    let dir = temp_dir("delete");
    let mut rec = store(&dir);
    let id = rec.schedule(&request(1, "A", 10_000, 11_000), 0).unwrap();
    assert!(matches!(rec.delete(id), Err(Error::RecordingNotFound)));
    let job = rec.begin(id, Some(ROOMY), 10_000).unwrap();
    std::fs::write(&job.path, b"ts").unwrap();
    rec.finish(id, &report(CaptureEnd::Finished, 2, 0), 11_300)
        .unwrap();
    rec.delete(id).unwrap();
    assert!(!job.path.exists());
    assert!(rec.recording(id).unwrap().is_none());
}

#[test]
fn a_missing_volume_is_flagged() {
    let dir = temp_dir("unplugged");
    let mut rec = store(&dir);
    let id = rec.schedule(&request(1, "A", 10_000, 11_000), 0).unwrap();
    std::fs::remove_dir_all(&dir).unwrap();
    assert!(rec.recording(id).unwrap().unwrap().volume_missing);
}

#[test]
fn the_schedule_survives_reopening() {
    let dir = temp_dir("reopen");
    let db = dir.join("recordings.db");
    let id = {
        let mut rec = Recordings::open(&db).unwrap();
        rec.set_directory(&dir).unwrap();
        rec.schedule(&request(1, "A", 10_000, 11_000), 0).unwrap()
    };
    let rec = Recordings::open(&db).unwrap();
    assert_eq!(rec.recording(id).unwrap().unwrap().programmes.len(), 1);
    assert_eq!(rec.directory().unwrap(), Some(dir));
}

// --- Copie du flux (wiremock) ------------------------------------------------

fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs() as i64
}

fn client(server: &MockServer) -> XtreamClient {
    XtreamClient::new(&Credentials {
        server_url: server.uri(),
        username: "u".into(),
        password: "p".into(),
    })
    .unwrap()
}

fn fast() -> CaptureOptions {
    CaptureOptions {
        first_retry: Duration::from_millis(20),
        max_retry: Duration::from_millis(50),
        max_failures: 1_000,
        stall: Duration::from_secs(2),
        probe: Arc::new(|_| Some(ROOMY)),
        ..CaptureOptions::default()
    }
}

fn job(dir: &Path, secs: i64) -> CaptureJob {
    CaptureJob {
        recording_id: 1,
        stream_id: 42,
        path: dir.join("1.ts"),
        until: now() + secs,
    }
}

#[tokio::test]
async fn the_stream_is_copied_until_the_end_and_reconnected_in_one_file() {
    let server = MockServer::start().await;
    // Chaque connexion livre un morceau puis se ferme : la copie se
    // reconnecte et complète le même fichier.
    Mock::given(method("GET"))
        .and(path("/live/u/p/42.ts"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(vec![0x47u8; 188]))
        .mount(&server)
        .await;
    let dir = temp_dir("capture");
    let job = job(&dir, 2);
    std::fs::write(&job.path, [1u8; 10]).unwrap();
    let control = CaptureControl::new();
    let report = capture(&client(&server), &job, &control, &fast()).await;
    assert_eq!(report.end, CaptureEnd::Finished);
    let size = std::fs::metadata(&job.path).unwrap().len();
    assert_eq!(report.bytes, size);
    assert_eq!(report.written + 10, size);
    assert_eq!(control.bytes(), size);
    assert!(report.written >= 188 * 2, "{report:?}");
    assert_eq!(report.written % 188, 0);
    assert!(now() >= job.until);
}

#[tokio::test]
async fn too_many_failures_give_up() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(403))
        .expect(3)
        .mount(&server)
        .await;
    let dir = temp_dir("give-up");
    let options = CaptureOptions {
        max_failures: 3,
        ..fast()
    };
    let report = capture(
        &client(&server),
        &job(&dir, 60),
        &CaptureControl::new(),
        &options,
    )
    .await;
    assert_eq!(report.end, CaptureEnd::ConnectionLost);
    assert_eq!(report.bytes, 0);
}

#[tokio::test]
async fn waits_between_attempts_grow() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(503))
        .mount(&server)
        .await;
    let dir = temp_dir("backoff");
    let options = CaptureOptions {
        first_retry: Duration::from_millis(100),
        max_retry: Duration::from_secs(10),
        max_failures: 4,
        ..fast()
    };
    let started = std::time::Instant::now();
    capture(
        &client(&server),
        &job(&dir, 60),
        &CaptureControl::new(),
        &options,
    )
    .await;
    // 100 + 200 + 400 ms entre les 4 essais.
    assert!(started.elapsed() >= Duration::from_millis(700));
    assert_eq!(server.received_requests().await.unwrap().len(), 4);
}

#[tokio::test]
async fn stop_ends_the_copy_and_keeps_the_file() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_bytes(vec![0x47u8; 188])
                .set_delay(Duration::from_secs(30)),
        )
        .mount(&server)
        .await;
    let dir = temp_dir("stop");
    let job = job(&dir, 60);
    let control = CaptureControl::new();
    let stopper = control.clone();
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(200)).await;
        stopper.stop();
    });
    let started = std::time::Instant::now();
    let report = capture(&client(&server), &job, &control, &fast()).await;
    assert_eq!(report.end, CaptureEnd::Stopped);
    assert!(started.elapsed() < Duration::from_secs(5));
    assert!(job.path.exists());
}

#[tokio::test]
async fn the_copy_stops_cleanly_at_the_reserve() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(vec![0x47u8; 188 * 100]))
        .mount(&server)
        .await;
    let dir = temp_dir("reserve");
    let checks = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let counter = checks.clone();
    let options = CaptureOptions {
        space_check_bytes: 1,
        // Place suffisante au départ, puis réserve atteinte.
        probe: Arc::new(move |_| {
            let n = counter.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            Some(VolumeSpace {
                total_bytes: 8 * GB,
                free_bytes: if n == 0 { 5 * GB } else { GB },
            })
        }),
        ..fast()
    };
    let job = job(&dir, 60);
    let report = capture(&client(&server), &job, &CaptureControl::new(), &options).await;
    assert_eq!(report.end, CaptureEnd::OutOfSpace);
    assert!(report.bytes > 0);
    assert_eq!(std::fs::metadata(&job.path).unwrap().len(), report.bytes);
}

#[tokio::test]
async fn a_stalled_stream_is_reconnected() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_bytes(vec![0x47u8; 188])
                .set_delay(Duration::from_secs(30)),
        )
        .mount(&server)
        .await;
    let dir = temp_dir("stall");
    let options = CaptureOptions {
        stall: Duration::from_millis(200),
        max_failures: 3,
        ..fast()
    };
    let started = std::time::Instant::now();
    let report = capture(
        &client(&server),
        &job(&dir, 60),
        &CaptureControl::new(),
        &options,
    )
    .await;
    assert_eq!(report.end, CaptureEnd::ConnectionLost);
    assert!(started.elapsed() < Duration::from_secs(5));
    assert_eq!(server.received_requests().await.unwrap().len(), 3);
}

#[tokio::test]
async fn the_end_of_a_running_copy_can_move() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_bytes(vec![0x47u8; 188])
                .set_delay(Duration::from_secs(30)),
        )
        .mount(&server)
        .await;
    let dir = temp_dir("until");
    let job = job(&dir, 60);
    let control = CaptureControl::new();
    let mover = control.clone();
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(200)).await;
        mover.set_until(now() - 1);
    });
    let started = std::time::Instant::now();
    let report = capture(&client(&server), &job, &control, &fast()).await;
    assert_eq!(report.end, CaptureEnd::Finished);
    assert!(started.elapsed() < Duration::from_secs(5));
}
