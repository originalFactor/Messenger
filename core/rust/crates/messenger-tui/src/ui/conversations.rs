//! F1: conversation list.

use ratatui::layout::Rect;
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::Frame;

use crate::app::App;

pub fn draw(frame: &mut Frame, area: Rect, app: &mut App) {
    let width = area.width.saturating_sub(4) as usize;
    let items: Vec<Line<'static>> = if app.conversations.is_empty() {
        let placeholder = if app.conversation_filter.is_some() {
            "(no conversations match the filter)"
        } else {
            "(no conversations yet — press n)"
        };
        vec![Line::from(Span::styled(
            placeholder,
            Style::default().fg(Color::DarkGray),
        ))]
    } else {
        app.conversations
            .iter()
            .map(|conversation| {
                let preview: String = conversation
                    .last_message
                    .clone()
                    .unwrap_or_default()
                    .chars()
                    .take(width.saturating_sub(4))
                    .collect();
                Line::from(vec![
                    Span::styled(
                        conversation.title.clone(),
                        Style::default().add_modifier(Modifier::BOLD),
                    ),
                    Span::styled(
                        format!("  {}  ", relative_time(conversation.updated_at)),
                        Style::default().fg(Color::DarkGray),
                    ),
                    Span::styled(preview, Style::default().fg(Color::Gray)),
                ])
            })
            .collect()
    };
    let title = match app.conversation_filter.as_deref() {
        Some(filter) => format!("Conversations ({filter})"),
        None => format!("Conversations ({})", app.conversations.len()),
    };
    super::widgets::list_pane(
        frame,
        area,
        &title,
        items,
        app.conversation_selection,
        true,
    );
}

/// A compact age label for list rows.
fn relative_time(timestamp_ms: i64) -> String {
    let now = messenger_store::now_ms();
    let seconds = ((now - timestamp_ms).max(0)) / 1000;
    match seconds {
        0..=59 => "now".to_string(),
        60..=3599 => format!("{}m", seconds / 60),
        3600..=86_399 => format!("{}h", seconds / 3600),
        _ => format!("{}d", seconds / 86_400),
    }
}
