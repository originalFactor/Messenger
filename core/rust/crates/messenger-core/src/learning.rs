//! Offline SM-2 v1 scheduler. UTC milliseconds and injected `now` avoid timezone arithmetic.
use messenger_store::{
    learning::{Result, ReviewAttempt, ReviewRating, ReviewState, ReviewSubmission},
    Store,
};
const DAY_MS: i64 = 86_400_000;

pub fn schedule(previous: &ReviewState, rating: ReviewRating, now: i64) -> ReviewState {
    let quality = rating.quality();
    let (repetitions, interval_days) = if quality < 3 {
        (0, 1)
    } else {
        let days = match previous.repetitions {
            0 => 1,
            1 => 6,
            _ => ((previous.interval_days as f64) * previous.ease)
                .round()
                .clamp(1.0, 36500.0) as i64,
        };
        (previous.repetitions.saturating_add(1), days)
    };
    let delta = 5 - quality;
    let ease = (previous.ease + 0.1 - (delta as f64) * (0.08 + (delta as f64) * 0.02)).max(1.3);
    ReviewState {
        card_id: previous.card_id.clone(),
        version: previous.version.saturating_add(1),
        interval_days,
        repetitions,
        ease,
        due_at: now.saturating_add(interval_days.saturating_mul(DAY_MS)),
        last_reviewed_at: Some(now),
        algorithm: "sm2-v1".into(),
    }
}
pub fn submit_review(
    store: &Store,
    submission: ReviewSubmission,
    now: i64,
) -> Result<ReviewAttempt> {
    store.submit_learning_review(submission, now, schedule)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn state() -> ReviewState {
        ReviewState {
            card_id: "card".into(),
            version: 0,
            interval_days: 0,
            repetitions: 0,
            ease: 2.5,
            due_at: 0,
            last_reviewed_at: None,
            algorithm: "sm2-v1".into(),
        }
    }
    #[test]
    fn four_ratings_have_explicit_quality_and_ease() {
        for (rating, quality, ease, repetitions) in [
            (ReviewRating::Again, 1, 1.96, 0),
            (ReviewRating::Hard, 3, 2.36, 1),
            (ReviewRating::Good, 4, 2.5, 1),
            (ReviewRating::Easy, 5, 2.6, 1),
        ] {
            assert_eq!(rating.quality(), quality);
            let s = schedule(&state(), rating, 123);
            assert!((s.ease - ease).abs() < 1e-9);
            assert_eq!(s.repetitions, repetitions);
            assert_eq!(s.due_at, 123 + DAY_MS);
        }
    }
    #[test]
    fn intervals_reset_and_overdue_review_starts_at_actual_time() {
        let first = schedule(&state(), ReviewRating::Good, 0);
        let second = schedule(&first, ReviewRating::Good, 100 * DAY_MS);
        assert_eq!(second.interval_days, 6);
        assert_eq!(second.due_at, 106 * DAY_MS);
        let third = schedule(&second, ReviewRating::Good, 106 * DAY_MS);
        assert_eq!(third.interval_days, 15);
        let reset = schedule(&third, ReviewRating::Again, 121 * DAY_MS);
        assert_eq!((reset.repetitions, reset.interval_days), (0, 1));
        let mut s = reset;
        for _ in 0..100 {
            s = schedule(&s, ReviewRating::Again, 0);
        }
        assert_eq!(s.ease, 1.3);
        // A UTC day stays 24h across local DST changes.
        assert_eq!(
            schedule(&state(), ReviewRating::Good, 1_710_054_000_000).due_at - 1_710_054_000_000,
            DAY_MS
        );
    }
}
