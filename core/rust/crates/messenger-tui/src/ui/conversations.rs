//! F1: conversation list, with a Projects section on top. A project IS a
//! workspace: its conversations are the only ones that can call the terminal
//! and workspace tools.

use ratatui::layout::Rect;
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::Frame;

use crate::app::App;

pub fn draw(frame: &mut Frame, area: Rect, app: &mut App) {
    let width = area.width.saturating_sub(4) as usize;
    let mut items: Vec<Line<'static>> = Vec::new();

    if !app.projects.is_empty() {
        items.push(Line::from(Span::styled(
            "Projects",
            Style::default().fg(Color::Cyan),
        )));
        for (index, project) in app.projects.iter().enumerate() {
            let selected = app.list_showing_projects && index == app.project_selection;
            items.push(Line::from(vec![
                Span::styled(
                    if selected { "> " } else { "  " }.to_string(),
                    Style::default().fg(Color::Cyan),
                ),
                Span::styled(
                    project.name.clone(),
                    if selected {
                        Style::default()
                            .fg(Color::Cyan)
                            .add_modifier(Modifier::BOLD)
                    } else {
                        Style::default().fg(Color::White)
                    },
                ),
                Span::styled(
                    format!("  {}  ", project.workspace),
                    Style::default().fg(Color::DarkGray),
                ),
            ]));
        }
        items.push(Line::default());
    }

    let placeholder = if app.conversation_filter.is_some() {
        "(no conversations match the filter)"
    } else if app.projects.is_empty() {
        "(no conversations yet — press n, p for a project)"
    } else {
        ""
    };
    if !placeholder.is_empty() {
        items.push(Line::from(Span::styled(
            placeholder,
            Style::default().fg(Color::DarkGray),
        )));
    }

    for (index, conversation) in app.conversations.iter().enumerate() {
        let selected = !app.list_showing_projects && index == app.conversation_selection;
        let preview: String = conversation
            .last_message
            .clone()
            .unwrap_or_default()
            .chars()
            .take(width.saturating_sub(4))
            .collect();
        let project_badge = conversation
            .project_id
            .as_ref()
            .and_then(|id| {
                app.projects
                    .iter()
                    .find(|project| &project.id == id)
                    .map(|project| format!("[{}] ", project.name))
            })
            .unwrap_or_default();
        items.push(Line::from(vec![
            Span::styled(
                if selected { "> " } else { "  " }.to_string(),
                Style::default().fg(Color::White),
            ),
            Span::styled(
                project_badge,
                Style::default().fg(Color::Cyan),
            ),
            Span::styled(
                conversation.title.clone(),
                if selected {
                    Style::default().add_modifier(Modifier::BOLD)
                } else {
                    Style::default().fg(Color::White)
                },
            ),
            Span::styled(
                format!("  {}  ", relative_time(conversation.updated_at)),
                Style::default().fg(Color::DarkGray),
            ),
            Span::styled(preview, Style::default().fg(Color::Gray)),
        ]));
    }

    let title = match app.conversation_filter.as_deref() {
        Some(filter) => format!("Conversations ({filter})"),
        None => format!(
            "Conversations ({})  ·  Projects ({})",
            app.conversations.len(),
            app.projects.len()
        ),
    };
    // One combined list, so the highlighted row is whichever block the
    // selection is in.
    let offset = if app.list_showing_projects { 0 } else { app.projects.len() + 2 };
    let index = offset + app.conversation_selection.max(0);
    super::widgets::list_pane(
        frame,
        area,
        &title,
        items,
        if app.list_showing_projects {
            app.project_selection + 1
        } else {
            index
        },
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