//! Incremental streaming Markdown block parser.
//! (TARGET.md §4, §5)
//!
//! Feeds continuous streaming text into a [`Document`], generating minimal
//! [`DocumentDiff`]s so platform renderers only invalidate active blocks.

use messenger_document::{Block, BlockId, BlockStatus, Document, DocumentDiff, Inline};

use crate::inlines::parse_inlines;

#[derive(Debug)]
enum ParserState {
    Idle,
    InParagraph {
        id: BlockId,
        text: String,
    },
    InCodeBlock {
        id: BlockId,
        language: Option<String>,
        code: String,
    },
    InMath {
        id: BlockId,
        formula: String,
    },
    InThink {
        id: BlockId,
        content: String,
    },
    InToolCall {
        id: BlockId,
        raw_json: String,
    },
    InQuote {
        id: BlockId,
        text: String,
    },
    /// A line that looks like a pipe-table row is held for one line of
    /// lookahead: only a delimiter row (`|---|---|`) turns it into a table.
    /// Anything else restores the previous context untouched — the held line
    /// re-enters it verbatim (paragraph continuation or standalone paragraph).
    MaybeTable {
        header: String,
        /// (id, text, eager_line_open) of the paragraph that was open when the
        /// `|` line arrived, so it can resume exactly where it paused.
        paragraph: Option<(BlockId, String, bool)>,
    },
    InTable {
        id: BlockId,
        head: Vec<String>,
        rows: Vec<Vec<String>>,
    },
}

pub struct IncrementalParser {
    state: ParserState,
    buffer: String,
    /// True right after `update_active_chunk` eagerly promoted a non-blank
    /// partial line into a streaming paragraph WITHOUT its terminating
    /// newline. The next continuation line must not prepend a soft-break
    /// (the model sent it as the same line).
    eager_line_open: bool,
}

impl IncrementalParser {
    pub fn new() -> Self {
        Self {
            state: ParserState::Idle,
            buffer: String::new(),
            eager_line_open: false,
        }
    }

    /// Feed an incoming token delta. Returns all document diffs generated.
    pub fn feed(&mut self, text: &str, doc: &mut Document) -> Vec<DocumentDiff> {
        self.buffer.push_str(text);
        let mut diffs = Vec::new();
        self.process_buffer(doc, &mut diffs);
        diffs
    }

    /// Flush any remaining buffered content (called when the stream is Done).
    pub fn flush(&mut self, doc: &mut Document) -> Vec<DocumentDiff> {
        let mut diffs = Vec::new();
        if !self.buffer.is_empty() {
            let remaining = std::mem::take(&mut self.buffer);
            self.feed_direct(&remaining, doc, &mut diffs);
        }
        self.finalize_active_state(doc, &mut diffs);
        diffs
    }

    fn process_buffer(&mut self, doc: &mut Document, diffs: &mut Vec<DocumentDiff>) {
        while let Some(newline_pos) = self.buffer.find('\n') {
            let line: String = self.buffer.drain(..=newline_pos).collect();
            let line_trimmed = line.trim_end_matches(['\n', '\r']);
            self.process_line(line_trimmed, doc, diffs);
        }

        // Process remaining trailing chunk for active streaming block update
        if !self.buffer.is_empty() {
            self.update_active_chunk(doc, diffs);
        }
    }

    fn feed_direct(&mut self, chunk: &str, doc: &mut Document, diffs: &mut Vec<DocumentDiff>) {
        for line in chunk.lines() {
            self.process_line(line, doc, diffs);
        }
    }

    fn process_line(&mut self, line: &str, doc: &mut Document, diffs: &mut Vec<DocumentDiff>) {
        match &mut self.state {
            ParserState::InCodeBlock { id, code, language } => {
                let id = *id;
                let lang = language.clone();
                if line.trim().starts_with("```") {
                    // Close code block
                    let mut current_code = code.clone();
                    if current_code.ends_with('\n') {
                        current_code.pop();
                    }
                    if let Some(diff) = doc.finalize(id) {
                        diffs.push(diff);
                    }
                    self.state = ParserState::Idle;
                } else {
                    code.push_str(line);
                    code.push('\n');
                    let block = Block::CodeBlock {
                        id,
                        language: lang,
                        code: code.clone(),
                        status: BlockStatus::Streaming,
                    };
                    if let Some(diff) = doc.update(block) {
                        diffs.push(diff);
                    }
                }
            }

            ParserState::InMath { id, formula } => {
                let id = *id;
                if line.trim().ends_with("$$") {
                    let cleaned = line.trim().trim_end_matches("$$").trim();
                    if !cleaned.is_empty() {
                        formula.push_str(cleaned);
                    }
                    let block = Block::Math {
                        id,
                        formula: formula.clone(),
                        status: BlockStatus::Finalized,
                    };
                    if let Some(diff) = doc.update(block) {
                        diffs.push(diff);
                    }
                    if let Some(diff) = doc.finalize(id) {
                        diffs.push(diff);
                    }
                    self.state = ParserState::Idle;
                } else {
                    formula.push_str(line);
                    formula.push('\n');
                    let block = Block::Math {
                        id,
                        formula: formula.clone(),
                        status: BlockStatus::Streaming,
                    };
                    if let Some(diff) = doc.update(block) {
                        diffs.push(diff);
                    }
                }
            }

            ParserState::InThink { id, content } => {
                let id = *id;
                if let Some(end_pos) = line.find("</think>") {
                    let before = &line[..end_pos];
                    content.push_str(before);
                    let block = Block::Think {
                        id,
                        content: content.clone(),
                        status: BlockStatus::Finalized,
                    };
                    if let Some(diff) = doc.update(block) {
                        diffs.push(diff);
                    }
                    if let Some(diff) = doc.finalize(id) {
                        diffs.push(diff);
                    }
                    self.state = ParserState::Idle;
                    let after = &line[end_pos + 8..];
                    if !after.trim().is_empty() {
                        self.process_line(after, doc, diffs);
                    }
                } else {
                    content.push_str(line);
                    content.push('\n');
                    let block = Block::Think {
                        id,
                        content: content.clone(),
                        status: BlockStatus::Streaming,
                    };
                    if let Some(diff) = doc.update(block) {
                        diffs.push(diff);
                    }
                }
            }

            ParserState::InToolCall { id, raw_json } => {
                let id = *id;
                if let Some(end_pos) = line.find("</tool_call>") {
                    let before = &line[..end_pos];
                    raw_json.push_str(before);
                    let (call_id, name, args, out, is_err) = parse_tool_call_json(raw_json);
                    let block = Block::ToolCall {
                        id,
                        call_id,
                        name,
                        arguments: args,
                        output: out,
                        is_error: is_err,
                        status: BlockStatus::Finalized,
                    };
                    if let Some(diff) = doc.update(block) {
                        diffs.push(diff);
                    }
                    if let Some(diff) = doc.finalize(id) {
                        diffs.push(diff);
                    }
                    self.state = ParserState::Idle;
                    let after = &line[end_pos + 12..];
                    if !after.trim().is_empty() {
                        self.process_line(after, doc, diffs);
                    }
                } else {
                    raw_json.push_str(line);
                }
            }

            ParserState::InQuote { id, text } => {
                let id = *id;
                if let Some(quote_content) = line.strip_prefix("> ") {
                    text.push_str(quote_content);
                    text.push('\n');
                    let block = Block::Quote {
                        id,
                        text: text.clone(),
                        status: BlockStatus::Streaming,
                    };
                    if let Some(diff) = doc.update(block) {
                        diffs.push(diff);
                    }
                } else if line.is_empty() {
                    if let Some(diff) = doc.finalize(id) {
                        diffs.push(diff);
                    }
                    self.state = ParserState::Idle;
                } else {
                    if let Some(diff) = doc.finalize(id) {
                        diffs.push(diff);
                    }
                    self.state = ParserState::Idle;
                    self.process_line(line, doc, diffs);
                }
            }

            ParserState::MaybeTable { header, paragraph } => {
                let header = std::mem::take(header);
                let saved = paragraph.take();
                if is_table_delimiter(line) {
                    let id = doc.next_id();
                    let head = parse_table_row(&header);
                    let block = Block::Table {
                        id,
                        head: head.clone(),
                        rows: Vec::new(),
                        status: BlockStatus::Streaming,
                    };
                    diffs.push(doc.append(block));
                    self.state = ParserState::InTable {
                        id,
                        head,
                        rows: Vec::new(),
                    };
                } else if let Some((pid, mut text, eager)) = saved {
                    // Not a table: re-attach the held line to the paused
                    // paragraph (mirrors the InParagraph continuation arm
                    // exactly — never re-routed through process_line, which
                    // would detect the row again and loop).
                    if !eager {
                        text.push('\n');
                    }
                    text.push_str(&header);
                    let block = Block::Paragraph {
                        id: pid,
                        inlines: parse_inlines(&text),
                        status: BlockStatus::Streaming,
                    };
                    if let Some(diff) = doc.update(block) {
                        diffs.push(diff);
                    }
                    self.eager_line_open = false;
                    self.state = ParserState::InParagraph { id: pid, text };
                    self.process_line(line, doc, diffs);
                } else {
                    // The held line stands alone as a paragraph after all.
                    let pid = doc.next_id();
                    let block = Block::Paragraph {
                        id: pid,
                        inlines: parse_inlines(&header),
                        status: BlockStatus::Streaming,
                    };
                    diffs.push(doc.append(block));
                    self.eager_line_open = false;
                    self.state = ParserState::InParagraph {
                        id: pid,
                        text: header,
                    };
                    self.process_line(line, doc, diffs);
                }
            }

            ParserState::InTable { id, head, rows } => {
                let id = *id;
                if line.trim().is_empty() {
                    let block = Block::Table {
                        id,
                        head: head.clone(),
                        rows: rows.clone(),
                        status: BlockStatus::Finalized,
                    };
                    if let Some(diff) = doc.update(block) {
                        diffs.push(diff);
                    }
                    if let Some(diff) = doc.finalize(id) {
                        diffs.push(diff);
                    }
                    self.state = ParserState::Idle;
                } else if looks_like_table_row(line) {
                    rows.push(parse_table_row(line));
                    let block = Block::Table {
                        id,
                        head: head.clone(),
                        rows: rows.clone(),
                        status: BlockStatus::Streaming,
                    };
                    if let Some(diff) = doc.update(block) {
                        diffs.push(diff);
                    }
                } else {
                    // Table ended without a blank line — finalize and let the
                    // line start whatever comes next.
                    let block = Block::Table {
                        id,
                        head: head.clone(),
                        rows: rows.clone(),
                        status: BlockStatus::Finalized,
                    };
                    if let Some(diff) = doc.update(block) {
                        diffs.push(diff);
                    }
                    if let Some(diff) = doc.finalize(id) {
                        diffs.push(diff);
                    }
                    self.state = ParserState::Idle;
                    self.process_line(line, doc, diffs);
                }
            }

            ParserState::InParagraph { id, text } => {
                let id = *id;
                if line.trim().is_empty() {
                    // Blank line finalizes the paragraph
                    if let Some(diff) = doc.finalize(id) {
                        diffs.push(diff);
                    }
                    self.state = ParserState::Idle;
                } else if line.starts_with("```")
                    || line.starts_with("$$")
                    || line.starts_with("<think>")
                    || line.starts_with("<tool_call>")
                    || line.starts_with("# ")
                    || line.starts_with("## ")
                    || line.starts_with("### ")
                    || line.starts_with("> ")
                {
                    // New block type interrupted the paragraph
                    if let Some(diff) = doc.finalize(id) {
                        diffs.push(diff);
                    }
                    self.state = ParserState::Idle;
                    self.process_line(line, doc, diffs);
                } else if !self.eager_line_open && looks_like_table_row(line) {
                    // Could be a pipe-table header — hold one line of lookahead;
                    // the paragraph resumes untouched if no delimiter follows.
                    let saved = (id, text.clone(), self.eager_line_open);
                    self.eager_line_open = false;
                    self.state = ParserState::MaybeTable {
                        header: line.to_string(),
                        paragraph: Some(saved),
                    };
                } else {
                    if !self.eager_line_open {
                        text.push('\n');
                    }
                    self.eager_line_open = false;
                    text.push_str(line);
                    let block = Block::Paragraph {
                        id,
                        inlines: parse_inlines(text),
                        status: BlockStatus::Streaming,
                    };
                    if let Some(diff) = doc.update(block) {
                        diffs.push(diff);
                    }
                }
            }

            ParserState::Idle => {
                let trimmed = line.trim();
                if trimmed.is_empty() {
                    return;
                }

                // Check for think tag: <think>
                if let Some(start_pos) = line.find("<think>") {
                    let before = &line[..start_pos];
                    if !before.trim().is_empty() {
                        self.process_line(before, doc, diffs);
                    }
                    let after = &line[start_pos + 7..];
                    let id = doc.next_id();
                    let block = Block::Think {
                        id,
                        content: String::new(),
                        status: BlockStatus::Streaming,
                    };
                    diffs.push(doc.append(block));
                    self.state = ParserState::InThink {
                        id,
                        content: String::new(),
                    };
                    if !after.is_empty() {
                        self.process_line(after, doc, diffs);
                    }
                    return;
                }

                // Check for tool call tag: <tool_call>
                if let Some(start_pos) = line.find("<tool_call>") {
                    let before = &line[..start_pos];
                    if !before.trim().is_empty() {
                        self.process_line(before, doc, diffs);
                    }
                    let after = &line[start_pos + 11..];
                    let id = doc.next_id();
                    let block = Block::ToolCall {
                        id,
                        call_id: String::new(),
                        name: "running...".into(),
                        arguments: String::new(),
                        output: None,
                        is_error: false,
                        status: BlockStatus::Streaming,
                    };
                    diffs.push(doc.append(block));
                    self.state = ParserState::InToolCall {
                        id,
                        raw_json: String::new(),
                    };
                    if !after.is_empty() {
                        self.process_line(after, doc, diffs);
                    }
                    return;
                }

                // Code block fence: ```[lang]
                if trimmed.starts_with("```") {
                    let lang = trimmed[3..].trim();
                    let language = if lang.is_empty() {
                        None
                    } else {
                        Some(lang.to_string())
                    };
                    let id = doc.next_id();
                    let block = Block::CodeBlock {
                        id,
                        language: language.clone(),
                        code: String::new(),
                        status: BlockStatus::Streaming,
                    };
                    diffs.push(doc.append(block));
                    self.state = ParserState::InCodeBlock {
                        id,
                        language,
                        code: String::new(),
                    };
                    return;
                }

                // Display Math block: $$
                if trimmed.starts_with("$$") {
                    let id = doc.next_id();
                    let formula = trimmed[2..].trim_end_matches("$$").trim().to_string();
                    if trimmed.len() > 4 && trimmed.ends_with("$$") {
                        // Single-line $$formula$$
                        let block = Block::Math {
                            id,
                            formula,
                            status: BlockStatus::Finalized,
                        };
                        diffs.push(doc.append(block));
                    } else {
                        let block = Block::Math {
                            id,
                            formula: formula.clone(),
                            status: BlockStatus::Streaming,
                        };
                        diffs.push(doc.append(block));
                        self.state = ParserState::InMath { id, formula };
                    }
                    return;
                }

                // Horizontal rule: --- or ***
                if trimmed == "---" || trimmed == "***" {
                    let id = doc.next_id();
                    diffs.push(doc.append(Block::Divider { id }));
                    return;
                }

                // Heading: # .. ######
                if let Some((level, text)) = parse_heading(trimmed) {
                    let id = doc.next_id();
                    let block = Block::Heading {
                        id,
                        level,
                        text: text.to_string(),
                        status: BlockStatus::Finalized,
                    };
                    diffs.push(doc.append(block));
                    return;
                }

                // Blockquote: > text
                if let Some(quote_content) = line.strip_prefix("> ") {
                    let id = doc.next_id();
                    let block = Block::Quote {
                        id,
                        text: format!("{quote_content}\n"),
                        status: BlockStatus::Streaming,
                    };
                    diffs.push(doc.append(block));
                    self.state = ParserState::InQuote {
                        id,
                        text: format!("{quote_content}\n"),
                    };
                    return;
                }

                // Pipe-table candidate: hold one line of lookahead so a
                // delimiter row can promote it into a table (see MaybeTable).
                if looks_like_table_row(line) {
                    self.state = ParserState::MaybeTable {
                        header: line.to_string(),
                        paragraph: None,
                    };
                    return;
                }

                // Normal Paragraph
                let id = doc.next_id();
                let inlines = parse_inlines(line);
                let block = Block::Paragraph {
                    id,
                    inlines,
                    status: BlockStatus::Streaming,
                };
                diffs.push(doc.append(block));
                self.eager_line_open = false;
                self.state = ParserState::InParagraph {
                    id,
                    text: line.to_string(),
                };
            }
        }
    }

    fn update_active_chunk(&mut self, doc: &mut Document, diffs: &mut Vec<DocumentDiff>) {
        let chunk = &self.buffer;
        match &mut self.state {
            ParserState::Idle => {
                // Eager streaming: open a paragraph as soon as a non-blank
                // partial line is buffered so renderers grow text
                // token-by-token instead of waiting for the line terminator.
                // The buffer is consumed into the paragraph state; when the
                // real newline arrives, `process_line` sees the line's
                // remainder and continues the same paragraph (no duplicate).
                // Lines that start a non-paragraph block (```, #, >, …) — or
                // are still a PREFIX of such a starter — never eager-open;
                // they keep the old buffer-until-newline behavior so the
                // real block handler sees them intact.
                if can_eager_open_paragraph(&self.buffer) {
                    let chunk = std::mem::take(&mut self.buffer);
                    let id = doc.next_id();
                    let block = Block::Paragraph {
                        id,
                        inlines: parse_inlines(&chunk),
                        status: BlockStatus::Streaming,
                    };
                    diffs.push(doc.append(block));
                    self.eager_line_open = true;
                    self.state = ParserState::InParagraph { id, text: chunk };
                }
            }
            ParserState::InParagraph { id, text } => {
                let id = *id;
                let mut combined = text.clone();
                combined.push_str(chunk);
                let block = Block::Paragraph {
                    id,
                    inlines: parse_inlines(&combined),
                    status: BlockStatus::Streaming,
                };
                if let Some(diff) = doc.update(block) {
                    diffs.push(diff);
                }
            }
            ParserState::InCodeBlock { id, code, language } => {
                let id = *id;
                let mut combined = code.clone();
                combined.push_str(chunk);
                let block = Block::CodeBlock {
                    id,
                    language: language.clone(),
                    code: combined,
                    status: BlockStatus::Streaming,
                };
                if let Some(diff) = doc.update(block) {
                    diffs.push(diff);
                }
            }
            ParserState::InMath { id, formula } => {
                let id = *id;
                let mut combined = formula.clone();
                combined.push_str(chunk);
                let block = Block::Math {
                    id,
                    formula: combined,
                    status: BlockStatus::Streaming,
                };
                if let Some(diff) = doc.update(block) {
                    diffs.push(diff);
                }
            }
            ParserState::InThink { id, content } => {
                let id = *id;
                let mut combined = content.clone();
                combined.push_str(chunk);
                let block = Block::Think {
                    id,
                    content: combined,
                    status: BlockStatus::Streaming,
                };
                if let Some(diff) = doc.update(block) {
                    diffs.push(diff);
                }
            }
            _ => {}
        }
    }

    fn finalize_active_state(&mut self, doc: &mut Document, diffs: &mut Vec<DocumentDiff>) {
        match std::mem::replace(&mut self.state, ParserState::Idle) {
            ParserState::InParagraph { id, text } => {
                let block = Block::Paragraph {
                    id,
                    inlines: parse_inlines(&text),
                    status: BlockStatus::Finalized,
                };
                if let Some(diff) = doc.update(block) {
                    diffs.push(diff);
                }
                if let Some(diff) = doc.finalize(id) {
                    diffs.push(diff);
                }
            }
            ParserState::InCodeBlock { id, language, code } => {
                let block = Block::CodeBlock {
                    id,
                    language,
                    code,
                    status: BlockStatus::Finalized,
                };
                if let Some(diff) = doc.update(block) {
                    diffs.push(diff);
                }
                if let Some(diff) = doc.finalize(id) {
                    diffs.push(diff);
                }
            }
            ParserState::InMath { id, formula } => {
                let block = Block::Math {
                    id,
                    formula,
                    status: BlockStatus::Finalized,
                };
                if let Some(diff) = doc.update(block) {
                    diffs.push(diff);
                }
                if let Some(diff) = doc.finalize(id) {
                    diffs.push(diff);
                }
            }
            ParserState::InThink { id, content } => {
                let block = Block::Think {
                    id,
                    content,
                    status: BlockStatus::Finalized,
                };
                if let Some(diff) = doc.update(block) {
                    diffs.push(diff);
                }
                if let Some(diff) = doc.finalize(id) {
                    diffs.push(diff);
                }
            }
            ParserState::InToolCall { id, raw_json } => {
                let (call_id, name, args, out, is_err) = parse_tool_call_json(&raw_json);
                let block = Block::ToolCall {
                    id,
                    call_id,
                    name,
                    arguments: args,
                    output: out,
                    is_error: is_err,
                    status: BlockStatus::Finalized,
                };
                if let Some(diff) = doc.update(block) {
                    diffs.push(diff);
                }
                if let Some(diff) = doc.finalize(id) {
                    diffs.push(diff);
                }
            }
            ParserState::InQuote { id, text } => {
                let block = Block::Quote {
                    id,
                    text,
                    status: BlockStatus::Finalized,
                };
                if let Some(diff) = doc.update(block) {
                    diffs.push(diff);
                }
                if let Some(diff) = doc.finalize(id) {
                    diffs.push(diff);
                }
            }
            ParserState::InTable { id, head, rows } => {
                let block = Block::Table {
                    id,
                    head,
                    rows,
                    status: BlockStatus::Finalized,
                };
                if let Some(diff) = doc.update(block) {
                    diffs.push(diff);
                }
                if let Some(diff) = doc.finalize(id) {
                    diffs.push(diff);
                }
            }
            ParserState::MaybeTable { header, paragraph } => {
                match paragraph {
                    Some((pid, mut text, eager)) => {
                        // The held line re-joins its paused paragraph, which
                        // then finalizes normally.
                        if !eager {
                            text.push('\n');
                        }
                        text.push_str(&header);
                        let block = Block::Paragraph {
                            id: pid,
                            inlines: parse_inlines(&text),
                            status: BlockStatus::Finalized,
                        };
                        if let Some(diff) = doc.update(block) {
                            diffs.push(diff);
                        }
                        if let Some(diff) = doc.finalize(pid) {
                            diffs.push(diff);
                        }
                    }
                    None => {
                        // The held line was never part of a block — emit it
                        // as a standalone finalized paragraph.
                        let pid = doc.next_id();
                        let block = Block::Paragraph {
                            id: pid,
                            inlines: parse_inlines(&header),
                            status: BlockStatus::Finalized,
                        };
                        diffs.push(doc.append(block));
                    }
                }
            }
            ParserState::Idle => {}
        }
    }
}

/// Whether a non-blank partial line may be eagerly promoted into a streaming
/// paragraph. Lines that start (or could still grow into) a non-paragraph
/// block starter are excluded — they wait for the newline so the real block
/// handler sees them intact.
fn can_eager_open_paragraph(buffer: &str) -> bool {
    if buffer.trim().is_empty() {
        return false;
    }
    const STARTERS: [&str; 14] = [
        "```", "$$", "<think>", "<tool_call>", "# ", "## ", "### ", "#### ", "##### ", "###### ",
        "> ", "---", "***", "|",
    ];
    STARTERS.iter().all(|s| !buffer.starts_with(s) && !s.starts_with(buffer))
}

/// A line shaped like a pipe-table row: starts with `|` and carries at least
/// one more pipe. Plain sentences that merely CONTAIN a pipe never match.
fn looks_like_table_row(line: &str) -> bool {
    let t = line.trim();
    t.starts_with('|') && t.matches('|').count() >= 2
}

/// GitHub-style delimiter row: pipes, dashes, colons and spaces only, with at
/// least one dash and one pipe (`|---|---|`, `| :---: |`, `--- | ---`).
fn is_table_delimiter(line: &str) -> bool {
    let t = line.trim();
    if t.is_empty() || !t.contains('-') || !t.contains('|') {
        return false;
    }
    t.chars().all(|c| matches!(c, '|' | '-' | ':' | ' ' | '\t'))
}

/// Split a pipe row into trimmed cells; outer pipes are optional. Cell text is
/// flattened from inline markup (bold/italic/code/…) to its plain text.
fn parse_table_row(line: &str) -> Vec<String> {
    let t = line.trim();
    let t = t.strip_prefix('|').unwrap_or(t);
    let t = t.strip_suffix('|').unwrap_or(t);
    t.split('|').map(|cell| plain_cell(cell.trim())).collect()
}

/// Concatenate the human-visible text of parsed inlines (drop markup markers).
fn plain_cell(text: &str) -> String {
    let mut out = String::new();
    for inline in parse_inlines(text) {
        match inline {
            Inline::Text { text }
            | Inline::Bold { text }
            | Inline::Italic { text }
            | Inline::Strikethrough { text }
            | Inline::Link { text, .. } => out.push_str(&text),
            Inline::Code { code } => out.push_str(&code),
            Inline::Math { formula } => out.push_str(&formula),
        }
    }
    out
}

fn parse_heading(line: &str) -> Option<(u8, &str)> {
    if let Some(text) = line.strip_prefix("# ") {
        Some((1, text))
    } else if let Some(text) = line.strip_prefix("## ") {
        Some((2, text))
    } else if let Some(text) = line.strip_prefix("### ") {
        Some((3, text))
    } else if let Some(text) = line.strip_prefix("#### ") {
        Some((4, text))
    } else if let Some(text) = line.strip_prefix("##### ") {
        Some((5, text))
    } else if let Some(text) = line.strip_prefix("###### ") {
        Some((6, text))
    } else {
        None
    }
}

fn parse_tool_call_json(raw: &str) -> (String, String, String, Option<String>, bool) {
    if let Ok(v) = serde_json::from_str::<serde_json::Value>(raw) {
        let call_id = v["callId"].as_str().or(v["call_id"].as_str()).unwrap_or("").to_string();
        let name = v["name"].as_str().unwrap_or("tool").to_string();
        let args = v["arguments"].as_str().unwrap_or("").to_string();
        let output = v["output"].as_str().map(str::to_string);
        let is_error = v["isError"].as_bool().or(v["is_error"].as_bool()).unwrap_or(false);
        (call_id, name, args, output, is_error)
    } else {
        (String::new(), "tool".to_string(), raw.to_string(), None, false)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_streaming_paragraph_and_code() {
        let mut doc = Document::new();
        let mut parser = IncrementalParser::new();

        // Feed paragraph token by token
        parser.feed("Hello ", &mut doc);
        parser.feed("world!\n\n", &mut doc);

        assert_eq!(doc.blocks().len(), 1);
        assert!(doc.blocks()[0].is_finalized());

        // Feed code block
        parser.feed("```rust\n", &mut doc);
        parser.feed("fn main() {\n", &mut doc);
        parser.feed("    println!(\"hi\");\n", &mut doc);
        parser.feed("}\n```\n", &mut doc);

        assert_eq!(doc.blocks().len(), 2);
        assert!(doc.blocks()[1].is_finalized());
        if let Block::CodeBlock { language, code, .. } = &doc.blocks()[1] {
            assert_eq!(language.as_deref(), Some("rust"));
            assert!(code.contains("fn main()"));
        } else {
            panic!("expected code block");
        }
    }

    /// Concatenate the human-visible text of a paragraph's inlines.
    fn inline_text(inlines: &[messenger_document::Inline]) -> String {
        inlines
            .iter()
            .map(|i| match i {
                messenger_document::Inline::Text { text }
                | messenger_document::Inline::Bold { text }
                | messenger_document::Inline::Italic { text }
                | messenger_document::Inline::Strikethrough { text }
                | messenger_document::Inline::Link { text, .. } => text.clone(),
                messenger_document::Inline::Code { code } => code.clone(),
                messenger_document::Inline::Math { formula } => formula.clone(),
            })
            .collect()
    }

    /// Single-character feeds must grow a STREAMING paragraph immediately —
    /// renderers paint each token as it arrives, they never wait for the
    /// line terminator (which single-line replies never contain).
    #[test]
    fn eager_paragraph_streams_per_token() {
        let mut doc = Document::new();
        let mut parser = IncrementalParser::new();

        // First token already materializes a streaming paragraph
        let diffs = parser.feed("Hi", &mut doc);
        assert!(!diffs.is_empty(), "first token must emit a diff");
        assert_eq!(doc.blocks().len(), 1);
        assert!(!doc.blocks()[0].is_finalized());

        // Continuation without newline keeps updating the same block
        let diffs = parser.feed(" there", &mut doc);
        assert!(!diffs.is_empty());
        assert_eq!(doc.blocks().len(), 1);
        if let Block::Paragraph { inlines, .. } = &doc.blocks()[0] {
            assert_eq!(inline_text(inlines), "Hi there");
        } else {
            panic!("expected paragraph");
        }

        // The terminating newline finalizes; no stray soft-break was injected
        parser.feed("\n\n", &mut doc);
        assert!(doc.blocks()[0].is_finalized());
        if let Block::Paragraph { inlines, .. } = &doc.blocks()[0] {
            assert_eq!(inline_text(inlines), "Hi there");
        }
    }

    /// A partial block starter ("# Ti") is never eager-opened as a paragraph —
    /// it buffers until the newline and lands in its real block handler.
    #[test]
    fn eager_paragraph_corrects_into_heading() {
        let mut doc = Document::new();
        let mut parser = IncrementalParser::new();

        parser.feed("# Ti", &mut doc);
        assert_eq!(doc.blocks().len(), 0); // starter prefix stays buffered
        parser.feed("tle\n", &mut doc);

        assert_eq!(doc.blocks().len(), 1);
        assert!(doc.blocks()[0].is_finalized());
        assert!(matches!(doc.blocks()[0], Block::Heading { level: 1, .. }));
    }

    #[test]
    fn parse_think_and_math() {
        let mut doc = Document::new();
        let mut parser = IncrementalParser::new();

        parser.feed("<think>\nThinking step 1\n</think>\n\n", &mut doc);
        assert_eq!(doc.blocks().len(), 1);
        assert!(matches!(doc.blocks()[0], Block::Think { .. }));
        assert!(doc.blocks()[0].is_finalized());

        parser.feed("$$\n\\int_0^1 x dx\n$$\n", &mut doc);
        assert_eq!(doc.blocks().len(), 2);
        assert!(matches!(doc.blocks()[1], Block::Math { .. }));
        assert!(doc.blocks()[1].is_finalized());
    }

    #[test]
    fn parse_streaming_table() {
        let mut doc = Document::new();
        let mut parser = IncrementalParser::new();

        parser.feed("Intro\n\n", &mut doc);
        parser.feed("| Tool | Purpose |\n", &mut doc);
        // Header alone must not become a block yet (lookahead holds it)
        assert_eq!(doc.blocks().len(), 1, "held header must not emit a block");
        parser.feed("|---|---|\n", &mut doc);
        assert_eq!(doc.blocks().len(), 2);
        assert!(matches!(doc.blocks()[1], Block::Table { .. }));
        assert!(!doc.blocks()[1].is_finalized());
        parser.feed("| **terminal** | Run shell |\n", &mut doc);
        parser.feed("| glob | Find files |\n", &mut doc);
        parser.feed("\n", &mut doc);

        assert_eq!(doc.blocks().len(), 2);
        assert!(doc.blocks()[1].is_finalized());
        if let Block::Table { head, rows, .. } = &doc.blocks()[1] {
            assert_eq!(head, &vec!["Tool".to_string(), "Purpose".to_string()]);
            assert_eq!(
                rows,
                &vec![
                    vec!["terminal".to_string(), "Run shell".to_string()],
                    vec!["glob".to_string(), "Find files".to_string()],
                ]
            );
        } else {
            panic!("expected table");
        }
    }

    /// Partial `|` lines buffer instead of eager-opening a paragraph; the
    /// header streams into a table only once the delimiter row arrives.
    #[test]
    fn table_header_streaming_via_eager_guard() {
        let mut doc = Document::new();
        let mut parser = IncrementalParser::new();

        parser.feed("| Too", &mut doc);
        assert_eq!(doc.blocks().len(), 0, "partial pipe line must buffer");
        parser.feed("l |\n", &mut doc);
        assert_eq!(doc.blocks().len(), 0);
        parser.feed("|---|\n", &mut doc);
        assert_eq!(doc.blocks().len(), 1);
        if let Block::Table { head, .. } = &doc.blocks()[0] {
            assert_eq!(head, &vec!["Tool".to_string()]);
        } else {
            panic!("expected table");
        }
    }

    /// A `|`-starting line with no delimiter row after it stays a normal
    /// paragraph, held line included, and following text resumes the same block.
    #[test]
    fn pipe_line_without_delimiter_stays_paragraph() {
        let mut doc = Document::new();
        let mut parser = IncrementalParser::new();

        parser.feed("| just | words\n", &mut doc);
        assert_eq!(doc.blocks().len(), 0); // held
        parser.feed("more words\n", &mut doc);
        parser.feed("\n", &mut doc);

        assert_eq!(doc.blocks().len(), 1);
        assert!(doc.blocks()[0].is_finalized());
        if let Block::Paragraph { inlines, .. } = &doc.blocks()[0] {
            assert_eq!(inline_text(inlines), "| just | words\nmore words");
        } else {
            panic!("expected paragraph");
        }
    }

    /// A pipe row interrupting an open paragraph: the paragraph resumes
    /// untouched when no delimiter follows.
    #[test]
    fn pipe_row_inside_paragraph_resumes_paragraph() {
        let mut doc = Document::new();
        let mut parser = IncrementalParser::new();

        parser.feed("before the pipes\n", &mut doc);
        parser.feed("| x | y |\n", &mut doc);
        parser.feed("after the pipes\n", &mut doc);
        parser.feed("\n", &mut doc);

        assert_eq!(doc.blocks().len(), 1);
        if let Block::Paragraph { inlines, .. } = &doc.blocks()[0] {
            assert_eq!(inline_text(inlines), "before the pipes\n| x | y |\nafter the pipes");
        } else {
            panic!("expected paragraph");
        }
    }

    /// A table cut off by a non-row line finalizes and the line re-routes.
    #[test]
    fn table_interrupted_by_paragraph() {
        let mut doc = Document::new();
        let mut parser = IncrementalParser::new();

        parser.feed("| a | b |\n|---|---|\n| 1 | 2 |\n", &mut doc);
        parser.feed("trailing text\n", &mut doc);
        parser.feed("\n", &mut doc);

        assert_eq!(doc.blocks().len(), 2);
        assert!(matches!(doc.blocks()[0], Block::Table { .. }));
        assert!(doc.blocks()[0].is_finalized());
        assert!(matches!(doc.blocks()[1], Block::Paragraph { .. }));
        assert!(doc.blocks()[1].is_finalized());
    }
}
