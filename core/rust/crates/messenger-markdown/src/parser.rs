//! Incremental streaming Markdown block parser.
//! (TARGET.md §4, §5)
//!
//! Feeds continuous streaming text into a [`Document`], generating minimal
//! [`DocumentDiff`]s so platform renderers only invalidate active blocks.

use messenger_document::{Block, BlockId, BlockStatus, Document, DocumentDiff};

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
}

pub struct IncrementalParser {
    state: ParserState,
    buffer: String,
}

impl IncrementalParser {
    pub fn new() -> Self {
        Self {
            state: ParserState::Idle,
            buffer: String::new(),
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
                } else {
                    text.push('\n');
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

                // Normal Paragraph
                let id = doc.next_id();
                let inlines = parse_inlines(line);
                let block = Block::Paragraph {
                    id,
                    inlines,
                    status: BlockStatus::Streaming,
                };
                diffs.push(doc.append(block));
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
            ParserState::Idle => {}
        }
    }
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
}
