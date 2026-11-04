use xtream_core::{MAX_RETRIES, RetryAction, StopReason, playback_retry};

fn retry(delay_secs: u32) -> RetryAction {
    RetryAction::Retry { delay_secs }
}

#[test]
fn no_status_retries_after_1_2_4_8_8_8_seconds_then_stops() {
    let delays: Vec<RetryAction> = (0..MAX_RETRIES).map(|n| playback_retry(None, n)).collect();
    assert_eq!(
        delays,
        [retry(1), retry(2), retry(4), retry(8), retry(8), retry(8)]
    );
    assert_eq!(
        playback_retry(None, MAX_RETRIES),
        RetryAction::Stop(StopReason::Failed)
    );
    // Bien au-delà : toujours arrêté, sans dépassement.
    assert_eq!(
        playback_retry(None, u32::MAX),
        RetryAction::Stop(StopReason::Failed)
    );
}

#[test]
fn subscription_in_use_never_retries() {
    for attempt in [0, 1, 5, 6, 100] {
        assert_eq!(
            playback_retry(Some(509), attempt),
            RetryAction::Stop(StopReason::SubscriptionInUse)
        );
    }
}

#[test]
fn refused_account_never_retries() {
    for status in [401, 403] {
        for attempt in [0, 1, 5, 6] {
            assert_eq!(
                playback_retry(Some(status), attempt),
                RetryAction::Stop(StopReason::AccountRefused),
                "{status} après {attempt} relances"
            );
        }
    }
}

#[test]
fn not_found_gets_a_single_retry() {
    assert_eq!(playback_retry(Some(404), 0), retry(1));
    for attempt in [1, 2, 5, 6] {
        assert_eq!(
            playback_retry(Some(404), attempt),
            RetryAction::Stop(StopReason::NotFound)
        );
    }
}

#[test]
fn other_codes_follow_the_usual_backoff() {
    // Pannes passagères du fournisseur, et codes qu'on ne distingue pas.
    for status in [400, 408, 429, 500, 502, 503, 504, 200, 0, 999] {
        assert_eq!(playback_retry(Some(status), 0), retry(1), "{status}");
        assert_eq!(playback_retry(Some(status), 3), retry(8), "{status}");
        assert_eq!(
            playback_retry(Some(status), MAX_RETRIES),
            RetryAction::Stop(StopReason::Failed),
            "{status}"
        );
    }
}
