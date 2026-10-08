//! F4: providers list + the selected provider's models (40/60 split).

use ratatui::layout::{Constraint, Direction, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::Frame;

use crate::app::App;

pub fn draw(frame: &mut Frame, area: Rect, app: &mut App) {
    let chunks = Layout::default()
        .direction(Direction::Horizontal)
        .constraints([Constraint::Percentage(40), Constraint::Percentage(60)])
        .split(area);

    draw_providers(frame, chunks[0], app);
    draw_models(frame, chunks[1], app);
}

fn draw_providers(frame: &mut Frame, area: Rect, app: &mut App) {
    let items: Vec<Line<'static>> = if app.providers.is_empty() {
        vec![Line::from(Span::styled(
            "(no providers — press n)",
            Style::default().fg(Color::DarkGray),
        ))]
    } else {
        app.providers
            .iter()
            .map(|provider| {
                let builtin = provider.id == messenger_sync::BUILTIN_PROVIDER_ID;
                Line::from(vec![
                    Span::styled(
                        provider.name.clone(),
                        Style::default().add_modifier(Modifier::BOLD),
                    ),
                    if builtin {
                        Span::styled(" [cloud]", Style::default().fg(Color::Indexed(45)))
                    } else {
                        Span::raw("")
                    },
                    Span::styled(
                        format!("  {}", provider.base_url),
                        Style::default().fg(Color::DarkGray),
                    ),
                ])
            })
            .collect()
    };
    super::widgets::list_pane(
        frame,
        area,
        &format!("Providers ({})", app.providers.len()),
        items,
        app.provider_selection,
        !app.provider_focus_models,
    );
}

fn draw_models(frame: &mut Frame, area: Rect, app: &mut App) {
    let items: Vec<Line<'static>> = if app.models.is_empty() {
        vec![Line::from(Span::styled(
            "(no models — press s to fetch, a to add)",
            Style::default().fg(Color::DarkGray),
        ))]
    } else {
        app.models
            .iter()
            .map(|model| {
                let marker = if model.is_enabled { "[x]" } else { "[ ]" };
                let context = if model.context_window > 0 {
                    format!("ctx {}", model.context_window)
                } else {
                    "ctx ∞".to_string()
                };
                Line::from(vec![
                    Span::styled(
                        format!("{marker} "),
                        if model.is_enabled {
                            Style::default().fg(Color::Green)
                        } else {
                            Style::default().fg(Color::DarkGray)
                        },
                    ),
                    Span::styled(
                        model.display_name.clone(),
                        Style::default().add_modifier(Modifier::BOLD),
                    ),
                    Span::styled(
                        format!("  {}  {context}", model.model_id),
                        Style::default().fg(Color::DarkGray),
                    ),
                ])
            })
            .collect()
    };
    super::widgets::list_pane(
        frame,
        area,
        &format!("Models ({})", app.models.len()),
        items,
        app.model_selection,
        app.provider_focus_models,
    );
}
