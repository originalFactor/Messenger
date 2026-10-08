//! View rendering: one module per top-level view plus the shared widgets.

pub mod agents;
pub mod chat;
pub mod conversations;
pub mod mcp;
pub mod providers;
pub mod settings;
pub mod widgets;

use ratatui::layout::{Constraint, Direction, Layout, Rect};
use ratatui::Frame;

use crate::app::{App, View};

/// Draw the whole frame for the app's current state.
pub fn draw(frame: &mut Frame, app: &mut App) {
    let area = frame.area();
    let chunks = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(1), // tab strip
            Constraint::Min(3),    // view body
            Constraint::Length(1), // hint
            Constraint::Length(1), // status bar
        ])
        .split(area);

    widgets::tab_strip(frame, chunks[0], app.view);

    let body = chunks[1];
    match app.view {
        View::Conversations => conversations::draw(frame, body, app),
        View::Chat => chat::draw(frame, body, app),
        View::Agents => agents::draw(frame, body, app),
        View::Providers => providers::draw(frame, body, app),
        View::Mcp => mcp::draw(frame, body, app),
        View::Settings => settings::draw(frame, body, app),
    }

    widgets::hint(frame, chunks[2], &hint_for(app.view));
    widgets::status_bar(
        frame,
        chunks[3],
        &app.status,
        app.chat.is_generating,
        app.spinner,
    );

    if app.help {
        widgets::help_overlay(frame, area);
    }
    if let Some(form) = app.form.clone() {
        widgets::form_modal(frame, area, &form);
    }
    if let Some(confirm) = app.confirm.clone() {
        widgets::confirm_modal(frame, area, &confirm);
    }
}

fn hint_for(view: View) -> String {
    match view {
        View::Conversations => {
            "Enter open · n new · r rename · d delete · / filter · F2 chat".to_string()
        }
        View::Chat => {
            "Enter send · Alt+Enter newline · Esc cancel · ^W mode · ^A agent · ^N new".to_string()
        }
        View::Agents => "Enter/a edit · n new · s select · d delete".to_string(),
        View::Providers => {
            "Tab focus · Enter edit · n provider · a model · s fetch models · space enable".to_string()
        }
        View::Mcp => "Enter/a edit · n new · space enable · d delete".to_string(),
        View::Settings => "Enter/space toggle · l sign in/out · s sync · r redeem · p password".to_string(),
    }
}

/// Body rectangle inside a bordered pane.
pub use widgets::inner as body_inner;

/// Reserve the top line of a body rect for a title bar.
pub fn split_title(body: Rect) -> (Rect, Rect) {
    let chunks = Layout::default()
        .direction(Direction::Vertical)
        .constraints([Constraint::Length(1), Constraint::Min(1)])
        .split(body);
    (chunks[0], chunks[1])
}
