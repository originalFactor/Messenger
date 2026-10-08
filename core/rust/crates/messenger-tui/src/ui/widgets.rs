//! Shared ratatui widgets: the status bar, the tab strip, scrollable line
//! panes, list panes, and the modal chrome.

use ratatui::layout::{Alignment, Constraint, Direction, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Borders, Clear, Paragraph, Wrap};
use ratatui::Frame;

use crate::app::{Confirm, Form, View};

/// Top tab strip: `Messenger ─ F1 Conversations … F6 Settings`.
pub fn tab_strip(frame: &mut Frame, area: Rect, active: View) {
    let mut spans = vec![Span::styled(
        " Messenger ",
        Style::default().add_modifier(Modifier::BOLD).fg(Color::Indexed(45)),
    )];
    for view in View::all() {
        let style = if view == active {
            Style::default()
                .bg(Color::Indexed(45))
                .fg(Color::Black)
                .add_modifier(Modifier::BOLD)
        } else {
            Style::default().fg(Color::Gray)
        };
        spans.push(Span::styled(
            format!(" F{} {} ", view.index() + 1, view.label()),
            style,
        ));
    }
    frame.render_widget(Paragraph::new(Line::from(spans)), area);
}

/// Bottom status bar: agent/model, cloud account, store path, spinner.
pub fn status_bar(frame: &mut Frame, area: Rect, text: &str, generating: bool, spinner: usize) {
    let frame_char = ["⠋", "⠙", "⠹", "⠸"][spinner % 4];
    let mut spans: Vec<Span<'static>> = Vec::new();
    if generating {
        spans.push(Span::styled(
            format!(" {frame_char} "),
            Style::default().fg(Color::Yellow),
        ));
    }
    spans.push(Span::styled(
        format!(" {text} "),
        Style::default().fg(Color::Gray),
    ));
    frame.render_widget(
        Paragraph::new(Line::from(spans)).style(Style::default().bg(Color::Indexed(235))),
        area,
    );
}

/// One-line hint under the tab strip.
pub fn hint(frame: &mut Frame, area: Rect, text: &str) {
    frame.render_widget(
        Paragraph::new(Line::from(Span::styled(
            text,
            Style::default().fg(Color::DarkGray),
        ))),
        area,
    );
}

/// A bordered list pane with a selection highlight.
pub fn list_pane<'a>(
    frame: &mut Frame,
    area: Rect,
    title: &str,
    items: Vec<Line<'a>>,
    selection: usize,
    focused: bool,
) {
    let border = if focused { Color::Indexed(45) } else { Color::DarkGray };
    let inner = Block::default()
        .borders(Borders::ALL)
        .border_style(Style::default().fg(border))
        .title(Span::styled(
            format!(" {title} "),
            Style::default().add_modifier(Modifier::BOLD),
        ));
    let height = area.height.saturating_sub(2) as usize;
    let offset = if selection >= height && height > 0 {
        selection + 1 - height
    } else {
        0
    };
    let lines: Vec<Line<'a>> = items
        .into_iter()
        .enumerate()
        .map(|(index, line)| {
            if index == selection {
                Line::from(
                    line.spans
                        .into_iter()
                        .map(|span| {
                            Span::styled(
                                span.content,
                                span.style.bg(Color::Indexed(238)).add_modifier(Modifier::BOLD),
                            )
                        })
                        .collect::<Vec<_>>(),
                )
            } else {
                line
            }
        })
        .skip(offset)
        .collect();
    frame.render_widget(Paragraph::new(lines).block(inner), area);
}

/// Body area of a bordered pane (inside the border).
pub fn inner(area: Rect) -> Rect {
    Rect {
        x: area.x + 1,
        y: area.y + 1,
        width: area.width.saturating_sub(2),
        height: area.height.saturating_sub(2),
    }
}

/// Centered modal rectangle of `percent_x` × `height`.
pub fn centered_rect(percent_x: u16, height: u16, area: Rect) -> Rect {
    let vertical = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(area.height.saturating_sub(height) / 2),
            Constraint::Length(height.min(area.height)),
            Constraint::Min(0),
        ])
        .split(area);
    Layout::default()
        .direction(Direction::Horizontal)
        .constraints([
            Constraint::Percentage((100 - percent_x) / 2),
            Constraint::Percentage(percent_x),
            Constraint::Percentage((100 - percent_x) / 2),
        ])
        .split(vertical[1])[1]
}

/// The help overlay.
pub fn help_overlay(frame: &mut Frame, area: Rect) {
    let lines = vec![
        Line::from(Span::styled(
            "Messenger TUI — keys",
            Style::default().add_modifier(Modifier::BOLD),
        )),
        Line::raw(""),
        Line::raw("F1–F6         switch view"),
        Line::raw("?             toggle this help"),
        Line::raw("Ctrl+C        quit (restores the terminal)"),
        Line::raw(ESC_HELP),
        Line::raw("↑/↓, k/j      move selection · scroll the chat"),
        Line::raw("PgUp/PgDn     scroll the chat by a page"),
        Line::raw("Ctrl+U/Ctrl+D scroll the chat by a page"),
        Line::raw("g / G         chat: top / bottom"),
        Line::raw("Enter         send · open · submit"),
        Line::raw("Alt+Enter     newline in a multiline field"),
        Line::raw("Ctrl+S        submit a form from anywhere"),
        Line::raw("n             new conversation / agent / provider / MCP server"),
        Line::raw("r             rename the selected conversation"),
        Line::raw("d             delete the selected row"),
        Line::raw("a             chat: switch Agent · providers: add model · agents: edit"),
        Line::raw("w             chat: toggle read-only / writable Agent mode"),
        Line::raw(""),
        Line::from(Span::styled(
            "In the chat view every printable key types into the message box, so its \
             commands are Ctrl-modified: Ctrl+N new · Ctrl+A agent · Ctrl+W mode.",
            Style::default().fg(Color::DarkGray),
        )),
        Line::raw("s             providers: fetch models · settings: sync now"),
        Line::raw("space         toggle a boolean · enable/disable a model or MCP server"),
        Line::raw("l             settings: sign in / sign out"),
        Line::raw("r             settings: redeem a card"),
        Line::raw("p             settings: change password"),
        Line::raw("x             settings: delete the account"),
        Line::raw("Ctrl+T        toggle think blocks"),
        Line::raw("Ctrl+O        toggle tool card bodies"),
        Line::raw(""),
        Line::from(Span::styled(
            "Notes: math renders as source (terminals cannot stack fractions). \
             Context Window is editable per model — the TUI has no models.dev metadata.",
            Style::default().fg(Color::DarkGray),
        )),
    ];
    let popup = centered_rect(76, 30, area);
    frame.render_widget(Clear, popup);
    frame.render_widget(
        Paragraph::new(lines)
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .title(" help ")
                    .border_style(Style::default().fg(Color::Indexed(45))),
            )
            .wrap(Wrap { trim: false }),
        popup,
    );
}

const ESC_HELP: &str = "Esc           close a modal · cancel the running turn";

/// The form modal: fields with a focused highlight and per-type rendering.
pub fn form_modal(frame: &mut Frame, area: Rect, form: &Form) {
    let height = (form.fields.len() as u16 * 2 + 4).min(area.height.saturating_sub(2));
    let popup = centered_rect(70, height, area);
    frame.render_widget(Clear, popup);

    let mut lines: Vec<Line<'static>> = Vec::new();
    for (index, field) in form.fields.iter().enumerate() {
        let focused = index == form.focus;
        let label_style = if focused {
            Style::default()
                .fg(Color::Indexed(45))
                .add_modifier(Modifier::BOLD)
        } else {
            Style::default().fg(Color::Gray)
        };
        lines.push(Line::from(vec![
            Span::styled(format!("{} ", if focused { "▸" } else { " " }), label_style),
            Span::styled(field.label().to_string(), label_style),
        ]));
        let value_style = if focused {
            Style::default().bg(Color::Indexed(238)).fg(Color::White)
        } else {
            Style::default().fg(Color::White)
        };
        let value = match field {
            crate::app::Field::Text {
                value, secret, multiline, ..
            } => {
                let shown = if *secret {
                    "•".repeat(value.chars().count())
                } else {
                    value.clone()
                };
                if *multiline {
                    shown.replace('\n', "⏎ ")
                } else {
                    shown
                }
            }
            crate::app::Field::Bool { value, .. } => {
                if *value {
                    "[x]".to_string()
                } else {
                    "[ ]".to_string()
                }
            }
            crate::app::Field::Choice {
                options, selected, ..
            } => format!(
                "◂ {} ▸",
                options.get(*selected).cloned().unwrap_or_default()
            ),
        };
        lines.push(Line::from(Span::styled(format!("   {value}"), value_style)));
    }
    lines.push(Line::raw(""));
    lines.push(Line::from(Span::styled(
        "Tab next · space toggle · Enter submit/advance · Ctrl+S submit · Esc cancel",
        Style::default().fg(Color::DarkGray),
    )));

    frame.render_widget(
        Paragraph::new(lines)
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .title(Span::styled(
                        format!(" {} ", form.title),
                        Style::default().add_modifier(Modifier::BOLD),
                    ))
                    .border_style(Style::default().fg(Color::Indexed(45))),
            )
            .wrap(Wrap { trim: false }),
        popup,
    );
}

/// The confirmation modal.
pub fn confirm_modal(frame: &mut Frame, area: Rect, confirm: &Confirm) {
    let popup = centered_rect(60, 8, area);
    frame.render_widget(Clear, popup);
    let mut lines = vec![
        Line::raw(confirm.message.clone()),
        Line::raw(""),
    ];
    if let Some(expected) = &confirm.requires_typing {
        lines.push(Line::from(vec![
            Span::raw("Type "),
            Span::styled(
                expected.clone(),
                Style::default().add_modifier(Modifier::BOLD),
            ),
            Span::raw(" to confirm: "),
            Span::styled(confirm.typed.clone(), Style::default().fg(Color::Yellow)),
        ]));
    }
    lines.push(Line::from(Span::styled(
        "Enter confirm · Esc cancel",
        Style::default().fg(Color::DarkGray),
    )));
    frame.render_widget(
        Paragraph::new(lines)
            .alignment(Alignment::Left)
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .title(Span::styled(
                        format!(" {} ", confirm.title),
                        Style::default().add_modifier(Modifier::BOLD),
                    ))
                    .border_style(Style::default().fg(Color::Yellow)),
            )
            .wrap(Wrap { trim: false }),
        popup,
    );
}
