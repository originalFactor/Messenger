//! F6: settings — local rendering switches, the workspace, and the cloud
//! account block.

use ratatui::layout::{Constraint, Direction, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Borders, Paragraph, Wrap};
use ratatui::Frame;

use crate::app::{App, SettingRow};

pub fn draw(frame: &mut Frame, area: Rect, app: &mut App) {
    let chunks = Layout::default()
        .direction(Direction::Horizontal)
        .constraints([Constraint::Percentage(55), Constraint::Percentage(45)])
        .split(area);

    draw_settings(frame, chunks[0], app);
    draw_account(frame, chunks[1], app);
}

fn draw_settings(frame: &mut Frame, area: Rect, app: &App) {
    let rows = SettingRow::all();
    let value_style = Style::default().fg(Color::White);
    let mut lines: Vec<Line<'static>> = Vec::new();
    for (index, row) in rows.iter().enumerate() {
        let focused = index == app.setting_selection;
        let label_style = if focused {
            Style::default()
                .fg(Color::Indexed(45))
                .add_modifier(Modifier::BOLD)
        } else {
            Style::default().fg(Color::Gray)
        };
        let (label, value) = match row {
            SettingRow::Theme => ("Theme", app.config.theme.clone()),
            SettingRow::ShowThink => (
                "Think blocks",
                if app.config.show_think { "expanded" } else { "collapsed" }.into(),
            ),
            SettingRow::ShowToolDetails => (
                "Tool card bodies",
                if app.config.show_tool_details { "expanded" } else { "collapsed" }.into(),
            ),
            SettingRow::AutoScroll => (
                "Auto-scroll",
                if app.config.auto_scroll { "on" } else { "off" }.into(),
            ),
            SettingRow::Workspace => ("Workspace directory", app.config.workspace_dir.clone()),
            SettingRow::ServerUrl => {
                let url = app
                    .engine
                    .store
                    .kv_get(messenger_sync::KV_SERVER_URL)
                    .ok()
                    .flatten()
                    .unwrap_or_else(|| messenger_sync::DEFAULT_CLOUD_SERVER_URL.to_string());
                ("Cloud server", url)
            }
            SettingRow::Session => (
                "Session",
                if app.cloud_user.is_some() { "signed in" } else { "signed out" }.into(),
            ),
        };
        lines.push(Line::from(vec![
            Span::styled(format!("{} ", if focused { "▸" } else { " " }), label_style),
            Span::styled(format!("{label:<22}"), label_style),
            Span::styled(value, value_style),
        ]));
    }
    lines.push(Line::raw(""));
    lines.push(Line::from(Span::styled(
        "Store: ".to_string() + &app.store_path.to_string_lossy(),
        Style::default().fg(Color::DarkGray),
    )));
    lines.push(Line::from(Span::styled(
        "Config: ".to_string() + &app.config_path.to_string_lossy(),
        Style::default().fg(Color::DarkGray),
    )));

    frame.render_widget(
        Paragraph::new(lines)
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .border_style(Style::default().fg(Color::Indexed(45)))
                    .title(Span::styled(" Settings ", Style::default().add_modifier(Modifier::BOLD))),
            )
            .wrap(Wrap { trim: false }),
        area,
    );
}

fn draw_account(frame: &mut Frame, area: Rect, app: &App) {
    let mut lines: Vec<Line<'static>> = Vec::new();
    match &app.cloud_user {
        Some(user) => {
            lines.push(Line::from(vec![
                Span::styled("Signed in as ", Style::default().fg(Color::Gray)),
                Span::styled(
                    user.email.clone(),
                    Style::default().add_modifier(Modifier::BOLD),
                ),
            ]));
            lines.push(Line::from(vec![
                Span::styled("Role: ", Style::default().fg(Color::Gray)),
                Span::styled(user.role.clone(), Style::default().fg(Color::Indexed(45))),
            ]));
            let balance = user
                .quota_balance
                .map(|value| value.to_string())
                .unwrap_or_else(|| "-".into());
            lines.push(Line::from(vec![
                Span::styled("Quota: ", Style::default().fg(Color::Gray)),
                Span::styled(balance, Style::default().fg(Color::Green)),
                Span::styled(
                    match user.quota_expires_at {
                        Some(expiry) => format!("  expires {expiry}"),
                        None => "  no expiry".to_string(),
                    },
                    Style::default().fg(Color::DarkGray),
                ),
            ]));
            for (name, balance, expiry) in &user.entitlements {
                lines.push(Line::from(Span::styled(
                    format!(
                        "  • {name}: {balance}{}",
                        expiry
                            .map(|value| format!(" (expires {value})"))
                            .unwrap_or_default()
                    ),
                    Style::default().fg(Color::DarkGray),
                )));
            }
            lines.push(Line::raw(""));
            lines.push(Line::from(Span::styled(
                "l sign out · s sync now · r redeem card",
                Style::default().fg(Color::DarkGray),
            )));
            lines.push(Line::from(Span::styled(
                "p change password · x delete account",
                Style::default().fg(Color::DarkGray),
            )));
        }
        None => {
            lines.push(Line::from(Span::styled(
                "Signed out",
                Style::default().add_modifier(Modifier::BOLD),
            )));
            lines.push(Line::raw(""));
            lines.push(Line::from(Span::styled(
                "l sign in · Enter on Cloud server to change the URL",
                Style::default().fg(Color::DarkGray),
            )));
            lines.push(Line::raw(""));
            lines.push(Line::from(Span::styled(
                "Signing in seeds the built-in cloud AI provider and pulls the \
                 account's agents, conversations and providers.",
                Style::default().fg(Color::DarkGray),
            )));
        }
    }
    frame.render_widget(
        Paragraph::new(lines)
            .block(
                Block::default()
                    .borders(Borders::ALL)
                    .border_style(Style::default().fg(Color::DarkGray))
                    .title(Span::styled(" Account ", Style::default().add_modifier(Modifier::BOLD))),
            )
            .wrap(Wrap { trim: false }),
        area,
    );
}
