//! Index d'un enregistrement MPEG-TS pour le lire dans un navigateur (HLS).
//!
//! Un `.ts` brut n'a ni durée ni index, et l'enregistrement recolle des flux
//! après chaque reconnexion : l'horloge (PCR) saute, parfois des octets sont
//! perdus. On lit donc le fichier une fois et on en tire une liste de segments
//! (position et longueur en octets, durée) : la page les joue avec hls.js, qui
//! donne une vraie barre de progression et franchit les coupures.
//!
//! Règles :
//! - un segment commence à un PAT suivi de son PMT, juste avant une image clé
//!   (H.264 : image IDR, sans quoi le décodeur n'a pas ses références) ;
//! - il dure environ [`TARGET_SECS`] ;
//! - une perte de synchro ou un saut du PCR coupe : le segment en cours est
//!   fermé avant la dernière image (peut-être incomplète ou déjà du flux
//!   suivant), le suivant est marqué « discontinuité ».
//!
//! N'utilise que la bibliothèque standard.

use std::fmt::Write as _;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

const PACKET: usize = 188;
const TARGET_SECS: f64 = 6.0;
/// Le PCR repart de zéro tous les 2^33 / 90 kHz.
const PCR_WRAP_SECS: f64 = 8_589_934_592.0 / 90_000.0;
const CHUNK: usize = PACKET * 90_000;
const MIN_SEGMENT_SECS: f64 = 0.2;
/// Écart maximal entre le PAT/PMT et l'image clé qui le suit.
const MAX_PSI_DISTANCE: u64 = 4_000_000;
/// Octets vidéo lus après l'image clé pour savoir si elle est IDR.
const PROBE_BYTES: usize = 4096;
const PROBE_PACKETS: u32 = 40;
const FORMAT: &str = "hls1";

/// Un morceau du fichier, lisible seul.
#[derive(Debug, Clone, PartialEq)]
pub struct Segment {
    pub start: u64,
    pub len: u64,
    pub secs: f64,
    /// L'horloge ne continue pas depuis le segment précédent.
    pub discontinuity: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Index {
    /// Taille du fichier au moment de la lecture.
    pub size: u64,
    pub segments: Vec<Segment>,
}

impl Index {
    #[cfg(test)]
    pub fn duration_secs(&self) -> f64 {
        self.segments.iter().map(|s| s.secs).sum()
    }

    /// Liste de lecture HLS : chaque segment est une plage d'octets du fichier `media_url`.
    pub fn playlist(&self, media_url: &str) -> String {
        let longest = self.segments.iter().map(|s| s.secs).fold(0.0, f64::max);
        let mut out = String::from("#EXTM3U\n#EXT-X-VERSION:4\n");
        let _ = writeln!(out, "#EXT-X-TARGETDURATION:{}", longest.ceil().max(1.0) as u64);
        out.push_str("#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n");
        for (i, s) in self.segments.iter().enumerate() {
            if s.discontinuity && i > 0 {
                out.push_str("#EXT-X-DISCONTINUITY\n");
            }
            let _ = writeln!(
                out,
                "#EXTINF:{:.3},\n#EXT-X-BYTERANGE:{}@{}\n{media_url}",
                s.secs, s.len, s.start
            );
        }
        out.push_str("#EXT-X-ENDLIST\n");
        out
    }

    /// Forme texte pour le garder à côté de l'enregistrement.
    pub fn to_text(&self) -> String {
        let mut out = format!("{FORMAT} {}\n", self.size);
        for s in &self.segments {
            let _ = writeln!(out, "{} {} {} {}", s.start, s.len, s.secs, u8::from(s.discontinuity));
        }
        out
    }

    pub fn from_text(text: &str) -> Option<Index> {
        let mut lines = text.lines();
        let head = lines.next()?;
        let size = head.strip_prefix(FORMAT)?.trim().parse().ok()?;
        let mut segments = Vec::new();
        for line in lines {
            let mut it = line.split_whitespace();
            segments.push(Segment {
                start: it.next()?.parse().ok()?,
                len: it.next()?.parse().ok()?,
                secs: it.next()?.parse().ok()?,
                discontinuity: it.next()? == "1",
            });
        }
        Some(Index { size, segments })
    }
}

/// Index d'un fichier : relu depuis `cache` s'il correspond à la taille du
/// fichier, sinon calculé (et gardé dans `cache` si on le donne).
pub fn build(path: &Path, cache: Option<&Path>) -> Result<Index, String> {
    let size = std::fs::metadata(path)
        .map_err(|e| format!("Fichier introuvable : {e}"))?
        .len();
    if let Some(cached) = cache
        .and_then(|c| std::fs::read_to_string(c).ok())
        .and_then(|t| Index::from_text(&t))
        .filter(|i| i.size == size)
    {
        return Ok(cached);
    }
    let index = scan(path).map_err(|e| format!("Lecture du fichier impossible : {e}"))?;
    if index.segments.is_empty() {
        return Err(
            "Aucune image clé exploitable dans ce fichier : télécharge-le et ouvre-le avec VLC."
                .to_owned(),
        );
    }
    if let Some(cache) = cache {
        if let Some(dir) = cache.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        let _ = std::fs::write(cache, index.to_text());
    }
    Ok(index)
}

/// Lit le fichier. Sans image IDR du tout (flux qui n'en a pas), on recommence
/// en acceptant les autres images clés.
pub fn scan(path: &Path) -> std::io::Result<Index> {
    let strict = scan_with(path, false, CHUNK)?;
    if strict.segments.is_empty() {
        return scan_with(path, true, CHUNK);
    }
    Ok(strict)
}

fn read_full(file: &mut File, buf: &mut [u8]) -> std::io::Result<usize> {
    let mut total = 0;
    while total < buf.len() {
        let n = file.read(&mut buf[total..])?;
        if n == 0 {
            break;
        }
        total += n;
    }
    Ok(total)
}

fn scan_with(path: &Path, accept_non_idr: bool, chunk: usize) -> std::io::Result<Index> {
    let mut file = File::open(path)?;
    let size = file.metadata()?.len();
    let mut scanner = Scanner::new(accept_non_idr);
    let mut buf = vec![0u8; chunk.max(PACKET * 8)];
    // Position dans le fichier de buf[0], et octets déjà lus mais pas encore traités.
    let mut base: u64 = 0;
    let mut carry = 0usize;
    let mut eof = false;
    loop {
        let mut got = 0;
        if !eof {
            file.seek(SeekFrom::Start(base + carry as u64))?;
            got = read_full(&mut file, &mut buf[carry..])?;
            eof = got == 0;
        }
        let len = carry + got;
        let mut i = 0;
        while i + PACKET <= len {
            if buf[i] != 0x47 {
                if scanner.lost_at.is_none() {
                    scanner.lost_at = Some(base + i as u64);
                }
                // Reprise : trois octets de synchro à 188 d'intervalle.
                let mut j = i + 1;
                while j + 2 * PACKET < len
                    && !(buf[j] == 0x47 && buf[j + PACKET] == 0x47 && buf[j + 2 * PACKET] == 0x47)
                {
                    j += 1;
                }
                if j + 2 * PACKET >= len {
                    // Rien dans ce qui est chargé : on jette tout sauf la fin et on lit la suite.
                    i = if eof { len } else { i.max(len.saturating_sub(2 * PACKET)) };
                    break;
                }
                scanner.lose_sync(base + j as u64);
                i = j;
                continue;
            }
            scanner.packet(base + i as u64, &buf[i..i + PACKET]);
            i += PACKET;
        }
        if eof {
            scanner.finish();
            break;
        }
        carry = len - i;
        buf.copy_within(i..len, 0);
        base += i as u64;
    }
    Ok(Index {
        size,
        segments: scanner.segments,
    })
}

struct Open {
    start: u64,
    clock: f64,
    discontinuity: bool,
}

/// Image clé en cours d'examen : on lit ses premiers octets pour savoir si c'est une IDR.
struct Probe {
    psi: u64,
    clock: f64,
    bytes: Vec<u8>,
    packets: u32,
}

struct Scanner {
    accept_non_idr: bool,
    segments: Vec<Segment>,
    pmt_pid: Option<u16>,
    pcr_pid: Option<u16>,
    video_pid: Option<u16>,
    video_type: u8,
    have_pmt: bool,
    pat_candidate: Option<u64>,
    /// Dernier PAT suivi de son PMT.
    psi_off: Option<u64>,
    /// Les PAT/PMT d'avant cette position ne comptent plus (autre flux).
    run_floor: u64,
    /// Secondes de flux lues, hors sauts du PCR.
    clock: f64,
    prev_pcr: Option<f64>,
    discontinuity_next: bool,
    resync_since_pcr: bool,
    open: Option<Open>,
    last_good_end: u64,
    lost_at: Option<u64>,
    last_video_pusi: Option<u64>,
    prev_pcr_off: u64,
    pusi_at_prev_pcr: Option<u64>,
    probe: Option<Probe>,
}

/// Premier type de NAL « tranche » (1 à 5) dans ces octets.
fn slice_type(bytes: &[u8]) -> Option<u8> {
    bytes.windows(4).find_map(|w| {
        let kind = w[3] & 0x1f;
        (w[0] == 0 && w[1] == 0 && w[2] == 1 && (1..=5).contains(&kind)).then_some(kind)
    })
}

impl Scanner {
    fn new(accept_non_idr: bool) -> Self {
        Scanner {
            accept_non_idr,
            segments: Vec::new(),
            pmt_pid: None,
            pcr_pid: None,
            video_pid: None,
            video_type: 0,
            have_pmt: false,
            pat_candidate: None,
            psi_off: None,
            run_floor: 0,
            clock: 0.0,
            prev_pcr: None,
            discontinuity_next: true,
            resync_since_pcr: false,
            open: None,
            last_good_end: 0,
            lost_at: None,
            last_video_pusi: None,
            prev_pcr_off: 0,
            pusi_at_prev_pcr: None,
            probe: None,
        }
    }

    /// Ferme le segment en cours à `end_off`, ou avant `trim_to` s'il est plus tôt.
    fn close(&mut self, end_off: u64, end_clock: f64, trim_to: Option<u64>) {
        let Some(open) = self.open.take() else {
            return;
        };
        let mut end = end_off;
        if let Some(t) = trim_to
            && t > open.start
            && t < end
        {
            end = t;
        }
        let secs = end_clock - open.clock;
        if secs >= MIN_SEGMENT_SECS && end > open.start {
            self.segments.push(Segment {
                start: open.start,
                len: end - open.start,
                secs,
                discontinuity: open.discontinuity,
            });
        }
    }

    fn finish(&mut self) {
        self.close(self.last_good_end, self.clock, None);
    }

    /// Synchro perdue à `lost_at`, retrouvée à `resume`.
    fn lose_sync(&mut self, resume: u64) {
        let lost = self.lost_at.take().unwrap_or(resume);
        self.close(lost, self.clock, self.last_video_pusi);
        self.probe = None;
        self.run_floor = resume;
        self.psi_off = None;
        self.pat_candidate = None;
        self.discontinuity_next = true;
        self.resync_since_pcr = true;
    }

    fn start_segment(&mut self, psi: u64, clock: f64) {
        let new = Open {
            start: psi,
            clock,
            discontinuity: self.discontinuity_next,
        };
        match &self.open {
            None => {}
            Some(open) if clock - open.clock + 1e-6 >= TARGET_SECS => self.close(psi, clock, None),
            Some(_) => return,
        }
        self.open = Some(new);
        self.discontinuity_next = false;
    }

    fn parse_pat(&mut self, p: &[u8], sect: usize) {
        if self.pmt_pid.is_some() || sect + 12 > PACKET {
            return;
        }
        let sec_len = (usize::from(p[sect + 1] & 0x0f) << 8) | usize::from(p[sect + 2]);
        let end = (sect + 3 + sec_len).saturating_sub(4).min(PACKET);
        let mut q = sect + 8;
        while q + 4 <= end {
            let program = (u16::from(p[q]) << 8) | u16::from(p[q + 1]);
            if program != 0 {
                self.pmt_pid = Some((u16::from(p[q + 2] & 0x1f) << 8) | u16::from(p[q + 3]));
                return;
            }
            q += 4;
        }
    }

    fn parse_pmt(&mut self, p: &[u8], sect: usize) {
        if self.have_pmt || sect + 12 > PACKET {
            return;
        }
        let sec_len = (usize::from(p[sect + 1] & 0x0f) << 8) | usize::from(p[sect + 2]);
        self.pcr_pid = Some((u16::from(p[sect + 8] & 0x1f) << 8) | u16::from(p[sect + 9]));
        let info = (usize::from(p[sect + 10] & 0x0f) << 8) | usize::from(p[sect + 11]);
        let end = (sect + 3 + sec_len).saturating_sub(4).min(PACKET);
        let mut q = sect + 12 + info;
        while q + 5 <= end {
            let kind = p[q];
            let pid = (u16::from(p[q + 1] & 0x1f) << 8) | u16::from(p[q + 2]);
            if matches!(kind, 0x01 | 0x02 | 0x1b | 0x24) && self.video_pid.is_none() {
                self.video_pid = Some(pid);
                self.video_type = kind;
            }
            q += 5 + ((usize::from(p[q + 3] & 0x0f) << 8) | usize::from(p[q + 4]));
        }
        self.have_pmt = true;
    }

    fn packet(&mut self, off: u64, p: &[u8]) {
        let pusi = p[1] & 0x40 != 0;
        let pid = (u16::from(p[1] & 0x1f) << 8) | u16::from(p[2]);
        let control = (p[3] >> 4) & 3;
        let has_af = control & 2 != 0;
        let af_len = if has_af { usize::from(p[4]) } else { 0 };
        let payload = (control & 1 != 0)
            .then(|| 4 + if has_af { 1 + af_len } else { 0 })
            .filter(|&o| o < PACKET);
        let is_video = self.video_pid == Some(pid);
        self.last_good_end = off + PACKET as u64;
        if is_video && pusi {
            self.last_video_pusi = Some(off);
        }

        if pid == 0 && pusi && let Some(o) = payload {
            self.pat_candidate = Some(off);
            self.parse_pat(p, o + 1 + usize::from(p[o]));
        } else if self.pmt_pid == Some(pid) && pusi && let Some(o) = payload {
            self.parse_pmt(p, o + 1 + usize::from(p[o]));
            if let Some(pat) = self.pat_candidate.take() {
                self.psi_off = Some(pat);
            }
        }

        if has_af && af_len >= 1 {
            let flags = p[5];
            if self.pcr_pid == Some(pid) && flags & 0x10 != 0 && af_len >= 7 {
                let base = (u64::from(u32::from_be_bytes([p[6], p[7], p[8], p[9]])) << 1)
                    | u64::from(p[10] >> 7);
                let ext = (u32::from(p[10] & 1) << 8) | u32::from(p[11]);
                self.pcr(off, base as f64 / 90_000.0 + f64::from(ext) / 27_000_000.0);
            }
            if is_video && pusi && flags & 0x40 != 0 {
                self.keyframe(off);
            }
        }

        if is_video && let Some(o) = payload {
            self.feed_probe(p, o, pusi);
        }
    }

    fn pcr(&mut self, off: u64, t: f64) {
        if let Some(prev) = self.prev_pcr {
            let mut d = t - prev;
            if d < -47_000.0 {
                d += PCR_WRAP_SECS;
            }
            if !(-0.0001..=1.0).contains(&d) {
                // Saut de l'horloge. Sans perte d'octets avant, la coupure est ici : on s'arrête au
                // dernier PCR de l'ancien flux (ce qui le suit peut déjà être du nouveau).
                if !self.resync_since_pcr {
                    self.probe = None;
                    self.close(self.prev_pcr_off, self.clock, self.pusi_at_prev_pcr);
                    self.run_floor = self.prev_pcr_off + PACKET as u64;
                    self.psi_off = None;
                    self.pat_candidate = None;
                    self.discontinuity_next = true;
                }
            } else {
                self.clock += d;
            }
        }
        self.prev_pcr = Some(t);
        self.resync_since_pcr = false;
        self.prev_pcr_off = off;
        self.pusi_at_prev_pcr = self.last_video_pusi;
    }

    fn keyframe(&mut self, off: u64) {
        let Some(psi) = self.psi_off.filter(|&p| p >= self.run_floor && off - p < MAX_PSI_DISTANCE)
        else {
            return;
        };
        if self.video_type == 0x1b && !self.accept_non_idr {
            self.probe = Some(Probe {
                psi,
                clock: self.clock,
                bytes: Vec::new(),
                packets: 0,
            });
        } else {
            self.start_segment(psi, self.clock);
        }
    }

    /// Ajoute les octets vidéo du paquet à l'examen en cours ; une fois la première
    /// tranche vue, le segment ne démarre que si c'est une image IDR.
    fn feed_probe(&mut self, p: &[u8], mut o: usize, pusi: bool) {
        let Some(probe) = self.probe.as_mut() else {
            return;
        };
        if pusi {
            o += 9 + p.get(o + 8).map_or(0, |&n| usize::from(n));
        }
        if o < PACKET {
            probe.bytes.extend_from_slice(&p[o..]);
        }
        probe.packets += 1;
        let kind = slice_type(&probe.bytes);
        if kind.is_some() || probe.bytes.len() > PROBE_BYTES || probe.packets > PROBE_PACKETS {
            let (psi, clock) = (probe.psi, probe.clock);
            self.probe = None;
            if kind == Some(5) {
                self.start_segment(psi, clock);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const VIDEO: u16 = 0x100;

    fn pcr_bytes(t: f64) -> [u8; 6] {
        let base = (t * 90_000.0).round() as u64 & 0x1_ffff_ffff;
        [
            (base >> 25) as u8,
            (base >> 17) as u8,
            (base >> 9) as u8,
            (base >> 1) as u8,
            (((base & 1) << 7) as u8) | 0x7e,
            0,
        ]
    }

    /// Paquet TS : en-tête d'adaptation si `flags` (avec le PCR si on le donne), sinon la charge seule.
    fn packet(pid: u16, pusi: bool, flags: Option<u8>, pcr: Option<f64>, payload: &[u8]) -> Vec<u8> {
        let mut p = vec![0xffu8; PACKET];
        p[0] = 0x47;
        p[1] = (u8::from(pusi) << 6) | ((pid >> 8) as u8 & 0x1f);
        p[2] = pid as u8;
        if flags.is_none() && payload.len() == PACKET - 4 {
            p[3] = 0x10;
            p[4..].copy_from_slice(payload);
            return p;
        }
        let mut body = vec![flags.unwrap_or(0)];
        if let Some(t) = pcr {
            body.extend_from_slice(&pcr_bytes(t));
        }
        let af_len = PACKET - 5 - payload.len();
        assert!(af_len >= body.len(), "charge trop longue");
        p[3] = if payload.is_empty() { 0x20 } else { 0x30 };
        p[4] = af_len as u8;
        p[5..5 + body.len()].copy_from_slice(&body);
        p[5 + af_len..].copy_from_slice(payload);
        p
    }

    fn pat() -> Vec<u8> {
        let mut payload = vec![0x00, 0x00, 0xb0, 0x0d, 0x00, 0x01, 0xc1, 0x00, 0x00];
        payload.extend_from_slice(&[0x00, 0x01, 0xf0, 0x00, 0, 0, 0, 0]);
        packet(0, true, None, None, &payload)
    }

    fn pmt() -> Vec<u8> {
        let mut payload = vec![0x00, 0x02, 0xb0, 0x17, 0x00, 0x01, 0xc1, 0x00, 0x00, 0xe1, 0x00, 0xf0, 0x00];
        payload.extend_from_slice(&[0x1b, 0xe1, 0x00, 0xf0, 0x00]);
        payload.extend_from_slice(&[0x0f, 0xe1, 0x01, 0xf0, 0x00]);
        payload.extend_from_slice(&[0, 0, 0, 0]);
        packet(0x1000, true, None, None, &payload)
    }

    /// Image clé : en-tête PES, AUD, SPS, PPS puis une tranche IDR (ou non IDR).
    fn key(t: f64, idr: bool) -> Vec<u8> {
        let mut payload = vec![0, 0, 1, 0xe0, 0, 0, 0x80, 0, 0];
        payload.extend_from_slice(&[0, 0, 0, 1, 0x09, 0xf0]);
        payload.extend_from_slice(&[0, 0, 0, 1, 0x67, 0x42, 0xc0, 0x1e]);
        payload.extend_from_slice(&[0, 0, 0, 1, 0x68, 0xce, 0x38, 0x80]);
        payload.extend_from_slice(&[0, 0, 1, if idr { 0x65 } else { 0x41 }, 0x88, 0x84]);
        packet(VIDEO, true, Some(0x50), Some(t), &payload)
    }

    /// Deux secondes de flux : PAT, PMT, image clé, un PCR toutes les 0,1 s.
    fn gop(out: &mut Vec<u8>, t0: f64, idr: bool) {
        out.extend(pat());
        out.extend(pmt());
        out.extend(key(t0, idr));
        for k in 1..20 {
            out.extend(packet(VIDEO, false, Some(0x10), Some(t0 + f64::from(k) * 0.1), &[]));
            for _ in 0..5 {
                out.extend(packet(VIDEO, false, None, None, &[0xaa; PACKET - 4]));
            }
        }
    }

    fn write_temp(name: &str, data: &[u8]) -> std::path::PathBuf {
        let path = std::env::temp_dir().join(format!("heystream-hls-test-{}-{name}.ts", std::process::id()));
        std::fs::write(&path, data).unwrap();
        path
    }

    #[test]
    fn continuous_stream_is_cut_every_six_seconds() {
        let mut data = Vec::new();
        for g in 0..10 {
            gop(&mut data, 100.0 + f64::from(g) * 2.0, true);
        }
        let path = write_temp("continu", &data);
        let index = scan_with(&path, false, CHUNK).unwrap();
        std::fs::remove_file(&path).ok();
        let s = &index.segments;
        assert_eq!(s.len(), 4, "{s:?}");
        assert!(s[..3].iter().all(|x| (x.secs - 6.0).abs() < 0.25), "{s:?}");
        assert!(s.iter().skip(1).all(|x| !x.discontinuity));
        // Contigus, du début du fichier à la fin.
        assert_eq!(s[0].start, 0);
        for pair in s.windows(2) {
            assert_eq!(pair[0].start + pair[0].len, pair[1].start);
        }
        let last = s.last().unwrap();
        assert_eq!(last.start + last.len, data.len() as u64);
        assert!((index.duration_secs() - 19.9).abs() < 0.3, "{}", index.duration_secs());
        // Chaque segment commence par un PAT.
        for x in s {
            assert_eq!(data[x.start as usize], 0x47);
            assert_eq!(data[x.start as usize + 1] & 0x40, 0x40);
            assert_eq!(data[x.start as usize + 2], 0);
        }
    }

    #[test]
    fn clock_jump_cuts_and_marks_a_discontinuity() {
        let mut data = Vec::new();
        for g in 0..4 {
            gop(&mut data, 1000.0 + f64::from(g) * 2.0, true);
        }
        let jump_at = data.len() as u64;
        for g in 0..4 {
            gop(&mut data, 50_000.0 + f64::from(g) * 2.0, true);
        }
        let path = write_temp("saut", &data);
        let index = scan_with(&path, false, CHUNK).unwrap();
        std::fs::remove_file(&path).ok();
        let s = &index.segments;
        assert!(s.iter().all(|x| x.start + x.len <= jump_at || x.start >= jump_at), "un segment enjambe le saut : {s:?}");
        assert_eq!(s.iter().filter(|x| x.discontinuity).count(), 2, "{s:?}");
        assert!(s.iter().any(|x| x.start >= jump_at && x.discontinuity));
        // Le PCR qui boucle à 2^33 / 90 kHz n'est pas un saut.
        let mut wrap = Vec::new();
        for g in 0..6 {
            let t = 95_443.0 - 4.0 + f64::from(g) * 2.0;
            gop(&mut wrap, if g >= 3 { t - PCR_WRAP_SECS } else { t }, true);
        }
        let path = write_temp("boucle", &wrap);
        let index = scan_with(&path, false, CHUNK).unwrap();
        std::fs::remove_file(&path).ok();
        assert_eq!(index.segments.iter().filter(|x| x.discontinuity).count(), 1, "{:?}", index.segments);
    }

    #[test]
    fn segments_start_on_idr_frames_only() {
        let mut data = Vec::new();
        for g in 0..10 {
            gop(&mut data, 10.0 + f64::from(g) * 2.0, g % 2 == 0);
        }
        let path = write_temp("idr", &data);
        let index = scan_with(&path, false, CHUNK).unwrap();
        assert!(!index.segments.is_empty());
        for x in &index.segments {
            // L'image clé d'un segment qui démarre est une IDR : GOP pairs seulement.
            let gop_bytes = (3 + 19 * 6) * PACKET;
            assert_eq!(x.start as usize % gop_bytes, 0);
            assert_eq!((x.start as usize / gop_bytes) % 2, 0, "{x:?}");
        }
        // Aucune IDR : on accepte alors les autres images clés.
        let mut none = Vec::new();
        for g in 0..10 {
            gop(&mut none, 10.0 + f64::from(g) * 2.0, false);
        }
        let none_path = write_temp("sans-idr", &none);
        assert!(scan_with(&none_path, false, CHUNK).unwrap().segments.is_empty());
        assert!(!scan(&none_path).unwrap().segments.is_empty());
        std::fs::remove_file(&path).ok();
        std::fs::remove_file(&none_path).ok();
    }

    #[test]
    fn long_garbage_is_skipped_and_cuts_the_stream() {
        let mut data = Vec::new();
        for g in 0..4 {
            gop(&mut data, 100.0 + f64::from(g) * 2.0, true);
        }
        let garbage_at = data.len() as u64;
        data.extend(std::iter::repeat_n(0x11u8, 100_003));
        let resume_at = data.len() as u64;
        for g in 0..4 {
            gop(&mut data, 200.0 + f64::from(g) * 2.0, true);
        }
        let path = write_temp("debris", &data);
        // Fenêtre de lecture plus petite que les débris : la reprise doit traverser plusieurs lectures.
        let index = scan_with(&path, false, PACKET * 200).unwrap();
        std::fs::remove_file(&path).ok();
        let s = &index.segments;
        assert!(s.iter().all(|x| x.start + x.len <= garbage_at || x.start >= resume_at), "{s:?}");
        let after: Vec<_> = s.iter().filter(|x| x.start >= resume_at).collect();
        assert!(!after.is_empty(), "rien après les débris : {s:?}");
        assert!(after[0].discontinuity);
    }

    #[test]
    fn playlist_and_text_round_trip() {
        let index = Index {
            size: 1000,
            segments: vec![
                Segment { start: 0, len: 400, secs: 6.0, discontinuity: true },
                Segment { start: 400, len: 300, secs: 5.5, discontinuity: false },
                Segment { start: 800, len: 200, secs: 1.25, discontinuity: true },
            ],
        };
        assert_eq!(Index::from_text(&index.to_text()), Some(index.clone()));
        let list = index.playlist("/files/1/2");
        assert!(list.starts_with("#EXTM3U\n"));
        assert!(list.contains("#EXT-X-TARGETDURATION:6\n"));
        assert!(list.contains("#EXTINF:6.000,\n#EXT-X-BYTERANGE:400@0\n/files/1/2\n"));
        assert!(list.ends_with("#EXT-X-ENDLIST\n"));
        // Pas de marque de discontinuité avant le premier segment, une seule avant le dernier.
        assert_eq!(list.matches("#EXT-X-DISCONTINUITY\n").count(), 1);
        assert!(Index::from_text("autre 12\n").is_none());
    }
}
