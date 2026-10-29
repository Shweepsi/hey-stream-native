//! Les paniques du cœur parviennent à l'observateur de l'app.

use std::sync::{Arc, Mutex};

use heystream::{PanicObserver, observe_panics};

#[derive(Default)]
struct Recorder(Mutex<Vec<(String, String)>>);

impl PanicObserver for Recorder {
    fn on_panic(&self, message: String, location: String) {
        self.0.lock().unwrap().push((message, location));
    }
}

#[test]
fn panics_reach_the_observer_with_their_location() {
    let first = Arc::new(Recorder::default());
    observe_panics(first.clone());
    let line = line!() + 1;
    let caught = std::panic::catch_unwind(|| panic!("cache {}", "illisible"));
    assert!(caught.is_err());
    let seen = first.0.lock().unwrap().clone();
    assert_eq!(
        seen,
        vec![(
            "cache illisible".to_owned(),
            format!("ffi/tests/panics.rs:{line}")
        )]
    );

    // Un second observateur remplace le premier.
    let second = Arc::new(Recorder::default());
    observe_panics(second.clone());
    let _ = std::panic::catch_unwind(|| panic!("encore"));
    assert_eq!(first.0.lock().unwrap().len(), 1);
    assert_eq!(second.0.lock().unwrap()[0].0, "encore");
}
