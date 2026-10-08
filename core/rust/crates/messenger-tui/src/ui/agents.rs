//! F3: the Agent list.

use ratatui::layout::Rect;
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::Frame;

use crate::app::App;

pub fn draw(frame: &mut Frame, area: Rect, app: &mut App) {
    let current = crate::store_ops::current_agent(&app.engine.store)
        .ok()
        .flatten()
        .map(|agent| agent.id);
    let items: Vec<Line<'static>> = if app.agents.is_empty() {
        vec![Line::from(Span::styled(
            "(no agents — press n)",
            Style::default().fg(Color::DarkGray),
        ))]
    } else {
        app.agents
            .iter()
            .map(|agent| {
                let marker = if Some(&agent.id) == current.as_ref() {
                    "● "
                } else {
                    "  "
                };
                let badge = if agent.is_default {
                    Span::styled(
                        " [default]",
                        Style::default().fg(Color::Indexed(45)).add_modifier(Modifier::BOLD),
                    )
                } else if agent.role == messenger_sync::ROLE_TITLE {
                    Span::styled(
                        " [title]",
                        Style::default().fg(Color::Magenta).add_modifier(Modifier::BOLD),
                    )
                } else {
                    Span::raw("")
                };
                let detail = if agent.description.trim().is_empty() {
                    agent
                        .system_prompt
                        .lines()
                        .next()
                        .unwrap_or("")
                        .to_string()
                } else {
                    agent.description.clone()
                };
                let detail: String = detail.chars().take(60).collect();
                Line::from(vec![
                    Span::styled(marker, Style::default().fg(Color::Green)),
                    Span::styled(agent.name.clone(), Style::default().add_modifier(Modifier::BOLD)),
                    badge,
                    Span::styled(
                        format!("  {}", detail),
                        Style::default().fg(Color::Gray),
                    ),
                    Span::styled(
                        format!(
                            "   tools:{} model:{}",
                            if agent.tools_enabled { "on" } else { "off" },
                            agent
                                .default_model_id
                                .clone()
                                .unwrap_or_else(|| "-".into())
                        ),
                        Style::default().fg(Color::DarkGray),
                    ),
                ])
            })
            .collect()
    };
    super::widgets::list_pane(
        frame,
        area,
        &format!("Agents ({})", app.agents.len()),
        items,
        app.agent_selection,
        true,
    );
}
