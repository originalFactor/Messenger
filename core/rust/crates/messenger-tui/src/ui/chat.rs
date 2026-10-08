//! F2: the chat view — conversation title, scrollable transcript, input
//! editor, and the live streaming tail.

use ratatui::layout::{Constraint, Direction, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Borders, Paragraph};
use ratatui::Frame;

use crate::app::App;

pub fn draw(frame: &mut Frame, area: Rect, app: &mut App) {
    let input_lines = app.chat.input.lines().count().clamp(1, 8) as u16;
    let chunks = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(1),               // title bar
            Constraint::Min(3),                  // transcript
            Constraint::Length(input_lines + 2), // input editor
        ])
        .split(area);

    draw_title(frame, chunks[0], app);

    let transcript = chunks[1];
    let width = transcript.width.saturating_sub(2);
    let mut lines: Vec<Line<'static>> = Vec::new();
    for index in 0..app.chat.messages.len() {
        let message_lines = app.rendered_message(index, width);
        if !lines.is_empty() {
            lines.push(Line::default());
        }
        let role = app.chat.messages[index].role.clone();
        lines.extend(prefix_role(&role, message_lines, width));
    }
    if let Some(live) = app.live_lines(width) {
        if !lines.is_empty() {
            lines.push(Line::default());
        }
        lines.extend(prefix_role("assistant", live, width));
    } else if app.chat.is_generating {
        lines.push(Line::from(Span::styled(
            "assistant is thinking…",
            Style::default().fg(Color::DarkGray).add_modifier(Modifier::ITALIC),
        )));
    }
    if let Some(error) = app.chat.error.clone() {
        lines.push(Line::default());
        lines.push(Line::from(Span::styled(
            format!("⚠ {error}"),
            Style::default().fg(Color::Red),
        )));
    }

    // Scroll offset counts from the bottom (`follow` keeps the tail visible).
    let view_height = transcript.height.saturating_sub(2) as usize;
    let total = lines.len();
    let offset = if app.chat.follow {
        total.saturating_sub(view_height)
    } else {
        total
            .saturating_sub(view_height)
            .saturating_sub(app.chat.scroll as usize)
    };
    let visible: Vec<Line<'static>> = lines.into_iter().skip(offset).take(view_height).collect();

    frame.render_widget(
        Paragraph::new(visible).block(
            Block::default()
                .borders(Borders::ALL)
                .border_style(Style::default().fg(Color::DarkGray))
                .title(Span::styled(" transcript ", Style::default().fg(Color::DarkGray))),
        ),
        transcript,
    );

    draw_input(frame, chunks[2], app);
}

fn draw_title(frame: &mut Frame, area: Rect, app: &App) {
    let title = app
        .chat
        .conversation_id
        .as_deref()
        .and_then(|id| app.engine.store.get_conversation(id).ok().flatten())
        .map(|conversation| conversation.title)
        .unwrap_or_else(|| "(no conversation)".into());
    let mode = if app.conversation_writable() {
        Span::styled(" writable ", Style::default().bg(Color::Red).fg(Color::Black))
    } else {
        Span::styled(" read-only ", Style::default().bg(Color::Indexed(238)))
    };
    let agent = crate::store_ops::current_agent(&app.engine.store)
        .ok()
        .flatten()
        .map(|agent| agent.name)
        .unwrap_or_else(|| "-".into());
    frame.render_widget(
        Paragraph::new(Line::from(vec![
            Span::styled(" Chat ", Style::default().fg(Color::DarkGray)),
            Span::styled(title, Style::default().add_modifier(Modifier::BOLD)),
            Span::styled(
                format!("   agent: {agent}"),
                Style::default().fg(Color::Gray),
            ),
            Span::raw("   "),
            mode,
        ])),
        area,
    );
}

fn draw_input(frame: &mut Frame, area: Rect, app: &App) {
    frame.render_widget(
        Paragraph::new(app.chat.input.clone()).block(
            Block::default()
                .borders(Borders::ALL)
                .border_style(Style::default().fg(Color::Indexed(45)))
                .title(Span::styled(
                    " message ",
                    Style::default().fg(Color::DarkGray),
                )),
        ),
        area,
    );
}

/// Label each transcript block with its role so the speaker stays readable
/// in a terminal that has no bubbles.
fn prefix_role(role: &str, lines: Vec<Line<'static>>, _width: u16) -> Vec<Line<'static>> {
    let (label, color) = match role {
        "user" => ("you", Color::Indexed(45)),
        "assistant" => ("agent", Color::Green),
        "tool" => ("tool", Color::Yellow),
        other => (other, Color::DarkGray),
    };
    lines
        .into_iter()
        .map(|line| {
            let mut spans = vec![Span::styled(
                format!("{label:>5} │ "),
                Style::default().fg(color),
            )];
            spans.extend(line.spans);
            Line::from(spans)
        })
        .collect()
}
