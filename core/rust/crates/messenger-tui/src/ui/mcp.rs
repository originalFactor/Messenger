//! F5: MCP servers.

use ratatui::layout::Rect;
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::Frame;

use crate::app::App;
use messenger_mcp::McpTransportType;

pub fn draw(frame: &mut Frame, area: Rect, app: &mut App) {
    let connected = app.engine.mcp_tools().len();
    let items: Vec<Line<'static>> = if app.mcp_servers.is_empty() {
        vec![Line::from(Span::styled(
            "(no MCP servers — press n to add one)",
            Style::default().fg(Color::DarkGray),
        ))]
    } else {
        app.mcp_servers
            .iter()
            .map(|server| {
                let marker = if server.is_enabled { "[x]" } else { "[ ]" };
                let transport = match server.transport_type {
                    McpTransportType::STDIO => {
                        format!("stdio: {} {}", server.command, server.args.join(" "))
                    }
                    McpTransportType::SSE => format!("sse: {}", server.url),
                };
                Line::from(vec![
                    Span::styled(
                        format!("{marker} "),
                        if server.is_enabled {
                            Style::default().fg(Color::Green)
                        } else {
                            Style::default().fg(Color::DarkGray)
                        },
                    ),
                    Span::styled(
                        server.name.clone(),
                        Style::default().add_modifier(Modifier::BOLD),
                    ),
                    Span::styled(
                        format!("  {}", transport.trim()),
                        Style::default().fg(Color::DarkGray),
                    ),
                ])
            })
            .collect()
    };
    super::widgets::list_pane(
        frame,
        area,
        &format!(
            "MCP servers ({} configured, {} connected tools)",
            app.mcp_servers.len(),
            connected
        ),
        items,
        app.mcp_selection,
        true,
    );
}
